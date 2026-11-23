use crate::{
    credentials::{CredentialError, CredentialStatus, CredentialStore, save_login},
    models::{
        AppError, EpisodeDetails, MediaDetails, MediaItem, MediaKind, MediaRef, SeasonDetails,
    },
    provider::{Credentials, normalize_base, sanitize_artwork_url},
    state::AppState,
};
use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{sync::Mutex, time::Duration};
use url::Url;

fn unavailable() -> AppError {
    AppError::storage("Online library state is unavailable. Please retry.", true)
}
fn malformed() -> AppError {
    AppError::provider("The resolver returned an invalid response.", true)
}
pub fn is_online(id: &str) -> bool {
    id.starts_with("online:")
}
pub fn scope_from_id(id: &str) -> Result<&str, AppError> {
    let (scope, upstream) = id.rsplit_once(':').ok_or_else(malformed)?;
    if !scope.starts_with("online:")
        || scope.len() != 71
        || !scope[7..].bytes().all(|b| b.is_ascii_hexdigit())
        || !valid_upstream(upstream)
    {
        return Err(AppError::validation("Invalid online title identity."));
    }
    Ok(scope)
}
fn valid_upstream(id: &str) -> bool {
    id.starts_with('/')
        && id.len() > 1
        && id.len() < 128
        && id[1..].bytes().all(|b| b.is_ascii_alphanumeric())
}

#[derive(Clone)]
pub struct ResolverSession {
    pub base: Url,
    credentials: Credentials,
    client: reqwest::Client,
}

pub struct ResolvedOnline {
    pub url: String,
    pub referer: Option<String>,
}

impl ResolverSession {
    pub fn new(base: &str, client_id: &str, client_secret: &str) -> Result<Self, AppError> {
        let base = normalize_base(base)?;
        if client_id.trim().is_empty()
            || client_secret.is_empty()
            || client_id.contains(['\r', '\n'])
            || client_secret.contains(['\r', '\n'])
        {
            return Err(AppError::validation("Enter a client ID and client secret."));
        }
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(100))
            .build()
            .map_err(|_| unavailable())?;
        Ok(Self {
            credentials: Credentials {
                base_url: base.to_string(),
                username: client_id.trim().into(),
                password: client_secret.into(),
            },
            base,
            client,
        })
    }
    pub fn scope(&self) -> String {
        format!("online:{:x}", Sha256::digest(self.base.as_str().as_bytes()))
    }
    pub fn title_id(&self, upstream: &str) -> Result<String, AppError> {
        if !valid_upstream(upstream) {
            return Err(malformed());
        }
        Ok(format!("{}:{upstream}", self.scope()))
    }
    pub fn upstream_id<'a>(&self, id: &'a str) -> Result<&'a str, AppError> {
        if scope_from_id(id)? != self.scope() {
            return Err(AppError::validation(
                "This title belongs to a different online library.",
            ));
        }
        Ok(id.rsplit_once(':').ok_or_else(malformed)?.1)
    }
    async fn get(&self, path: &str, query: &[(&str, &str)]) -> Result<Value, AppError> {
        let mut url = self.base.join(path).map_err(|_| malformed())?;
        if !query.is_empty() {
            url.query_pairs_mut().extend_pairs(query.iter().copied());
        }
        let response = self.client.get(url)
            .header("CF-Access-Client-Id", &self.credentials.username)
            .header("CF-Access-Client-Secret", &self.credentials.password)
            .send().await.map_err(|_| AppError::network("Unable to reach the online library. Check your connection and resolver address.", true))?;
        let status = response.status().as_u16();
        match status {
            401 | 403 => {
                return Err(AppError::unauthenticated(
                    "The online library rejected these credentials. Check the client ID and secret.",
                ));
            }
            404 | 410 => {
                return Err(AppError::provider(
                    "This online title or stream is no longer available.",
                    false,
                ));
            }
            429 => {
                return Err(AppError::network(
                    "The online library is busy. Please try again shortly.",
                    true,
                ));
            }
            300..=399 => {
                return Err(AppError::unauthenticated(
                    "The resolver redirected the request. Check its address and Cloudflare service access policy.",
                ));
            }
            200..=299 | 502 => {}
            _ => {
                return Err(AppError::provider(
                    "The resolver could not complete this request. Please retry.",
                    true,
                ));
            }
        }
        if response
            .content_length()
            .is_some_and(|n| n > 4 * 1024 * 1024)
        {
            return Err(malformed());
        }
        // Bound decoded responses as well as declared Content-Length.
        let mut response = response;
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| malformed())? {
            if bytes.len() + chunk.len() > 4 * 1024 * 1024 {
                return Err(malformed());
            }
            bytes.extend_from_slice(&chunk);
        }
        if status == 502 {
            // The resolver wraps upstream host failures in HTTP 502. Recognize
            // known availability errors without exposing arbitrary upstream text.
            let body: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
            let message = body["error"].as_str().unwrap_or("");
            if message.contains("file is gone (410)") {
                return Err(AppError::provider(
                    "This online title or stream is no longer available.",
                    false,
                ));
            }
            if message.contains("no implemented host on this page") {
                return Err(AppError::provider(
                    "No supported video host is available for this online title.",
                    false,
                ));
            }
            return Err(AppError::provider(
                "The resolver could not complete this request. Please retry.",
                true,
            ));
        }
        serde_json::from_slice(&bytes).map_err(|_| malformed())
    }
    pub async fn health(&self) -> Result<(), AppError> {
        let health = self.get("health", &[]).await?;
        if health["status"] != "ok" || health["service"] != "marquee-resolver" {
            return Err(malformed());
        }
        Ok(())
    }
    pub async fn search(&self, query: &str) -> Result<Vec<MediaItem>, AppError> {
        let query = query.trim();
        if query.chars().count() < 3 || query.len() > 200 {
            return Err(AppError::validation("Search using 3–200 characters."));
        }
        let response = self.get("search", &[("q", query)]).await?;
        let rows = response["results"].as_array().ok_or_else(malformed)?;
        rows.iter()
            .take(200)
            .map(|row| self.map_item(row))
            .collect()
    }
    pub async fn details(&self, media_ref: &MediaRef) -> Result<MediaDetails, AppError> {
        let response = self
            .get("show", &[("id", self.upstream_id(&media_ref.id)?)])
            .await?;
        let details = self.map_details(&response)?;
        if details.media_ref != *media_ref {
            return Err(malformed());
        }
        Ok(details)
    }
    pub async fn resolve(&self, id: &str) -> Result<ResolvedOnline, AppError> {
        let response = self
            .get("resolve", &[("id", self.upstream_id(id)?)])
            .await?;
        // Prefer the resolver's same-origin proxy: minted CDN links only answer the
        // IP that minted them, so a viewer on another network needs the bytes relayed.
        // Re-root the proxy path onto this session's base so it goes through the
        // exact address we already reach (LAN, Tailscale, or tunnel).
        let url = match response["streamUrl"].as_str() {
            Some(stream) => {
                let parsed = Url::parse(stream).map_err(|_| malformed())?;
                let mut proxied = self.base.clone();
                // Keep any base path prefix (e.g. a `/t/<token>/` path token) and
                // append the proxy route, so the request goes through the exact
                // address and auth shape this session already uses.
                let prefix = self.base.path().trim_end_matches('/');
                proxied.set_path(&format!("{prefix}{}", parsed.path()));
                proxied.set_query(parsed.query());
                proxied.to_string()
            }
            None => response["url"].as_str().ok_or_else(malformed)?.to_string(),
        };
        let parsed = Url::parse(&url).map_err(|_| malformed())?;
        if !matches!(parsed.scheme(), "http" | "https")
            || parsed.host_str().is_none()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            return Err(malformed());
        }
        // Through the proxy the resolver applies the upstream referer itself; sending
        // it from the player would only leak a host URL toward our own endpoint.
        let referer = if response["streamUrl"].is_string() {
            None
        } else {
            sanitize_artwork_url(response["referer"].as_str())
        };
        Ok(ResolvedOnline {
            url: parsed.to_string(),
            referer,
        })
    }
    pub fn map_item(&self, raw: &Value) -> Result<MediaItem, AppError> {
        let kind = match raw["kind"].as_str() {
            Some("movie") => MediaKind::Movie,
            Some("tv" | "series") => MediaKind::Series,
            _ => return Err(malformed()),
        };
        let name = raw["name"]
            .as_str()
            .filter(|s| !s.trim().is_empty())
            .ok_or_else(malformed)?
            .to_string();
        Ok(MediaItem {
            media_ref: MediaRef {
                kind,
                id: self.title_id(raw["id"].as_str().ok_or_else(malformed)?)?,
            },
            name,
            category_id: "online".into(),
            category_name: Some("Online Library".into()),
            poster_url: sanitize_artwork_url(raw["poster"].as_str()),
            backdrop_url: None,
            year: raw["year"]
                .as_str()
                .filter(|s| !s.is_empty())
                .map(String::from),
            rating: None,
            container_extension: None,
            added: None,
            progress: None,
            episode_id: None,
            position_ms: None,
            duration_ms: None,
            rank: None,
        })
    }
    pub fn map_details(&self, raw: &Value) -> Result<MediaDetails, AppError> {
        let item = self.map_item(raw)?;
        let mut seasons = Vec::new();
        for season in raw["seasons"].as_array().into_iter().flatten().take(200) {
            let number = season["number"]
                .as_i64()
                .filter(|n| *n >= 0 && *n <= i32::MAX as i64)
                .ok_or_else(malformed)? as i32;
            let mut episodes = Vec::new();
            for ep in season["episodes"]
                .as_array()
                .ok_or_else(malformed)?
                .iter()
                .take(1000)
            {
                let ep_number = ep["number"]
                    .as_i64()
                    .filter(|n| *n > 0 && *n <= i32::MAX as i64)
                    .ok_or_else(malformed)? as i32;
                episodes.push(EpisodeDetails {
                    id: self.title_id(ep["id"].as_str().ok_or_else(malformed)?)?,
                    season: number,
                    number: ep_number,
                    title: ep["name"].as_str().unwrap_or("Episode").into(),
                    container_extension: None,
                    image_url: None,
                    plot: ep["plot"].as_str().map(String::from),
                    duration_minutes: None,
                    duration_ms: None,
                    rating: None,
                    progress: None,
                    position_ms: None,
                });
            }
            episodes.sort_by_key(|ep| ep.number);
            seasons.push(SeasonDetails { number, episodes });
        }
        seasons.sort_by_key(|s| s.number);
        Ok(MediaDetails {
            media_ref: item.media_ref,
            name: item.name,
            category_id: item.category_id,
            category_name: item.category_name,
            poster_url: item.poster_url,
            backdrop_url: None,
            plot: raw["plot"].as_str().map(String::from),
            genre: None,
            year: item.year,
            release_date: None,
            rating: None,
            duration_minutes: None,
            cast: None,
            seasons: Some(seasons),
            related: vec![],
            in_my_list: false,
            resume_position_ms: None,
            resume_duration_ms: None,
            resume_episode_id: None,
        })
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OnlineStatus {
    pub connected: bool,
    pub base_url: Option<String>,
    pub credential_status: CredentialStatus,
    pub warning: Option<String>,
    pub development_import: bool,
}

struct OnlineConnection {
    restored: bool,
    session: Option<ResolverSession>,
    generation: u64,
    status: CredentialStatus,
    warning: Option<String>,
}
pub struct OnlineService {
    connection: Mutex<OnlineConnection>,
    secrets: Box<dyn CredentialStore>,
}
impl Default for OnlineService {
    fn default() -> Self {
        Self::with_secrets(Box::new(OnlineCredentials))
    }
}
impl OnlineService {
    pub(crate) fn with_secrets(secrets: Box<dyn CredentialStore>) -> Self {
        Self {
            connection: Mutex::new(OnlineConnection {
                restored: false,
                session: None,
                generation: 0,
                status: CredentialStatus::None,
                warning: None,
            }),
            secrets,
        }
    }
    pub fn session(&self) -> Result<(ResolverSession, u64), AppError> {
        let connection = self.connection.lock().map_err(|_| unavailable())?;
        Ok((
            connection.session.clone().ok_or_else(|| {
                AppError::unauthenticated("Connect your online library to watch this title.")
            })?,
            connection.generation,
        ))
    }
    pub fn is_current(&self, scope: &str, generation: u64) -> bool {
        self.session()
            .is_ok_and(|(session, current)| current == generation && session.scope() == scope)
    }
}

struct OnlineCredentials;
impl OnlineCredentials {
    fn entry(&self) -> Result<keyring::Entry, CredentialError> {
        keyring::Entry::new("app.marquee.desktop", "online-resolver").map_err(|_| CredentialError)
    }
}
impl CredentialStore for OnlineCredentials {
    fn load(&self) -> Result<Option<Credentials>, CredentialError> {
        match self.entry()?.get_password() {
            Ok(value) => serde_json::from_str(&value)
                .map(Some)
                .map_err(|_| CredentialError),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(_) => Err(CredentialError),
        }
    }
    fn save(&self, credentials: &Credentials) -> Result<(), CredentialError> {
        self.entry()?
            .set_password(&serde_json::to_string(credentials).map_err(|_| CredentialError)?)
            .map_err(|_| CredentialError)
    }
    fn delete(&self) -> Result<(), CredentialError> {
        match self.entry()?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(_) => Err(CredentialError),
        }
    }
}

impl AppState {
    pub fn online_status(&self) -> Result<OnlineStatus, AppError> {
        self.with_store(|store| {
            let mut connection = self.online.connection.lock().map_err(|_| unavailable())?;
            if !connection.restored {
                if let Some(marker) = store.preference("online_remembered")? {
                    match self.online.secrets.load() {
                        Ok(Some(saved)) => {
                            if let Ok(session) = ResolverSession::new(&saved.base_url, &saved.username, &saved.password)
                                && session.scope() == marker && store.preference("online_scope")?.as_deref() == Some(marker.as_str()) {
                                connection.session = Some(session);
                                connection.status = CredentialStatus::Remembered;
                            }
                        },
                        Ok(None) => {},
                        Err(_) => connection.warning = Some("Secure storage is unavailable. Reconnect your online library for this session.".into()),
                    }
                }
                connection.restored = true;
            }
            Ok(OnlineStatus { connected: connection.session.is_some(), base_url: store.preference("online_base_url")?, credential_status: connection.status,
                warning: connection.warning.clone(), development_import: cfg!(debug_assertions) })
        })
    }
    pub async fn connect_online(
        &self,
        base: &str,
        id: &str,
        secret: &str,
        remember: bool,
    ) -> Result<(), AppError> {
        let _gate = self.change_gate.lock().await;
        let session = ResolverSession::new(base, id, secret)?;
        session.health().await?;
        self.with_store(|store| {
            store.ensure_online_scope(&session)?;
            store.clear_preference("online_remembered")?;
            let status = save_login(self.online.secrets.as_ref(), &session.credentials, remember);
            if status == CredentialStatus::Remembered { store.set_preference("online_remembered", &session.scope())?; }
            let mut connection = self.online.connection.lock().map_err(|_| unavailable())?;
            connection.generation += 1;
            connection.session = Some(session);
            connection.restored = true;
            connection.status = status;
            connection.warning = if remember && status == CredentialStatus::Session { Some("Secure credential storage is unavailable. This online login lasts until Marquee closes.".into()) } else { None };
            Ok(())
        })
    }
    pub async fn disconnect_online(&self) -> Result<(), AppError> {
        let _gate = self.change_gate.lock().await;
        self.with_store(|store| {
            store.clear_preference("online_remembered")?;
            let mut connection = self.online.connection.lock().map_err(|_| unavailable())?;
            connection.generation += 1;
            connection.session = None;
            connection.restored = true;
            connection.status = CredentialStatus::None;
            connection.warning = None;
            self.online.secrets.delete().map_err(|_| AppError::storage("Disconnected. Unlock secure storage and retry Disconnect to remove its saved entry.", true))
        })
    }
    pub async fn search_online(&self, query: &str) -> Result<Vec<MediaItem>, AppError> {
        let (session, generation) = self.online.session()?;
        let items = session.search(query).await?;
        self.with_store(|store| {
            if !self.online.is_current(&session.scope(), generation) {
                return Err(AppError::unauthenticated(
                    "Online connection changed. Search again.",
                ));
            }
            for item in &items {
                store.cache_online_item(&session.scope(), item, None)?;
            }
            Ok(items)
        })
    }
    pub async fn online_details(&self, media_ref: &MediaRef) -> Result<MediaDetails, AppError> {
        let scope = self.online_scope_for(media_ref)?;
        let cached = self.with_store(|s| s.get_item(&scope, media_ref.kind, &media_ref.id))?;
        let details = if let Ok((session, generation)) = self.online.session() {
            match session.details(media_ref).await {
                Ok(details) => {
                    self.with_store(|s| {
                        if !self.online.is_current(&scope, generation) {
                            return Err(AppError::unauthenticated(
                                "Online connection changed. Please retry.",
                            ));
                        }
                        let item = details_item(&details);
                        s.cache_online_item(&scope, &item, Some(&details))
                    })?;
                    details
                }
                Err(error) => {
                    if error.code == "unauthenticated" {
                        return Err(error);
                    }
                    cached
                        .and_then(|(_, json)| json)
                        .and_then(|json| serde_json::from_str(&json).ok())
                        .ok_or(error)?
                }
            }
        } else {
            cached
                .and_then(|(_, json)| json)
                .and_then(|json| serde_json::from_str(&json).ok())
                .ok_or_else(|| {
                    AppError::unauthenticated(
                        "Reconnect your online library to load title details.",
                    )
                })?
        };
        self.with_store(|s| {
            if s.preference("online_scope")?.as_deref() != Some(scope.as_str()) {
                return Err(AppError::validation("Online library changed."));
            }
            let profile = s
                .active_profile()?
                .ok_or_else(|| AppError::validation("Select a profile."))?;
            let mut details = details;
            details.in_my_list =
                s.is_favorite(&scope, &profile, media_ref.kind.as_str(), &media_ref.id)?;
            let entries =
                s.item_history_entries(&scope, &profile, media_ref.kind, &media_ref.id)?;
            if let Some(entry) = entries.iter().find(|e| !e.completed) {
                details.resume_position_ms = Some(entry.position_ms);
                details.resume_duration_ms = Some(entry.duration_ms);
                details.resume_episode_id = if entry.episode_id.is_empty() {
                    None
                } else {
                    Some(entry.episode_id.clone())
                };
            }
            for ep in details
                .seasons
                .iter_mut()
                .flatten()
                .flat_map(|s| &mut s.episodes)
            {
                if let Some(entry) = entries.iter().find(|e| e.episode_id == ep.id) {
                    ep.position_ms = Some(entry.position_ms);
                    ep.progress = (entry.duration_ms > 0).then(|| {
                        (entry.position_ms as f64 / entry.duration_ms as f64).clamp(0., 1.)
                    });
                }
            }
            Ok(details)
        })
    }
    pub fn online_scope_for(&self, media_ref: &MediaRef) -> Result<String, AppError> {
        let scope = scope_from_id(&media_ref.id)?;
        if media_ref.kind == MediaKind::Live
            || self
                .with_store(|s| s.preference("online_scope"))?
                .as_deref()
                != Some(scope)
        {
            return Err(AppError::validation(
                "This title belongs to a different online library.",
            ));
        }
        Ok(scope.into())
    }
}

pub fn details_item(details: &MediaDetails) -> MediaItem {
    MediaItem {
        media_ref: details.media_ref.clone(),
        name: details.name.clone(),
        category_id: "online".into(),
        category_name: Some("Online Library".into()),
        poster_url: details.poster_url.clone(),
        backdrop_url: details.backdrop_url.clone(),
        year: details.year.clone(),
        rating: details.rating,
        container_extension: None,
        added: None,
        progress: None,
        episode_id: None,
        position_ms: None,
        duration_ms: None,
        rank: None,
    }
}

#[cfg(debug_assertions)]
pub fn development_credentials() -> Result<Credentials, AppError> {
    let output = std::process::Command::new("git")
        .args(["rev-parse", "--path-format=absolute", "--git-common-dir"])
        .current_dir(env!("CARGO_MANIFEST_DIR"))
        .output()
        .map_err(|_| AppError::validation("Unable to locate the main checkout."))?;
    if !output.status.success() {
        return Err(AppError::validation("Unable to locate the main checkout."));
    }
    let git_dir = std::path::PathBuf::from(String::from_utf8_lossy(&output.stdout).trim());
    let root = git_dir
        .parent()
        .ok_or_else(|| AppError::validation("Unable to locate the main checkout."))?;
    let text = std::fs::read_to_string(root.join(".env"))
        .map_err(|_| AppError::validation("The main checkout has no readable .env file."))?;
    let read = |key: &str| {
        text.lines()
            .filter_map(|line| {
                line.trim()
                    .strip_prefix("export ")
                    .unwrap_or(line.trim())
                    .split_once('=')
            })
            .find(|(name, _)| name.trim() == key)
            .map(|(_, value)| value.trim().trim_matches(['\'', '"']).to_string())
            .filter(|v| !v.is_empty())
            .ok_or_else(|| {
                AppError::validation("The development .env needs CLIENT_ID and CLIENT_SECRET.")
            })
    };
    Ok(Credentials {
        base_url: String::new(),
        username: read("CLIENT_ID")?,
        password: read("CLIENT_SECRET")?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Clone, Default)]
    struct MemorySecrets(std::sync::Arc<Mutex<Option<Credentials>>>);
    impl CredentialStore for MemorySecrets {
        fn load(&self) -> Result<Option<Credentials>, CredentialError> {
            Ok(self.0.lock().unwrap().clone())
        }
        fn save(&self, value: &Credentials) -> Result<(), CredentialError> {
            *self.0.lock().unwrap() = Some(value.clone());
            Ok(())
        }
        fn delete(&self) -> Result<(), CredentialError> {
            *self.0.lock().unwrap() = None;
            Ok(())
        }
    }
    struct LockedSecrets;
    impl CredentialStore for LockedSecrets {
        fn load(&self) -> Result<Option<Credentials>, CredentialError> {
            Err(CredentialError)
        }
        fn save(&self, _: &Credentials) -> Result<(), CredentialError> {
            Err(CredentialError)
        }
        fn delete(&self) -> Result<(), CredentialError> {
            Err(CredentialError)
        }
    }

    #[test]
    fn remembered_online_login_restores_and_failed_auth_preserves_previous_connection() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            for status in ["200 OK", "401 Unauthorized"] {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut bytes = [0; 8192];
                let _ = stream.read(&mut bytes).unwrap();
                let body = r#"{"status":"ok","service":"marquee-resolver"}"#;
                write!(
                    stream,
                    "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                )
                .unwrap();
            }
        });
        let path =
            std::env::temp_dir().join(format!("marquee-online-{}.sqlite", uuid::Uuid::new_v4()));
        let secrets = MemorySecrets::default();
        let mut state = AppState::new(path.clone(), Box::new(MemorySecrets::default()));
        state.online = OnlineService::with_secrets(Box::new(secrets.clone()));
        tauri::async_runtime::block_on(async {
            state
                .connect_online(&base, "id", "good-secret", true)
                .await
                .unwrap();
            let (session, generation) = state.online.session().unwrap();
            assert!(
                state
                    .connect_online(&base, "id", "bad-secret", true)
                    .await
                    .is_err()
            );
            assert!(state.online.is_current(&session.scope(), generation));
        });
        assert_eq!(
            state.online_status().unwrap().credential_status,
            CredentialStatus::Remembered
        );
        let profile = state.profiles_list().unwrap()[0].id.clone();
        let session = state.online.session().unwrap().0;
        let details = session.map_details(&serde_json::json!({"id":"/m12345","kind":"movie","name":"Survives restart","seasons":[]})).unwrap();
        state
            .with_store(|store| {
                store.cache_online_item(&session.scope(), &details_item(&details), Some(&details))
            })
            .unwrap();
        state.favorite_set(&details.media_ref, true).unwrap();
        drop(state);
        let mut state = AppState::new(path.clone(), Box::new(MemorySecrets::default()));
        state.online = OnlineService::with_secrets(Box::new(secrets.clone()));
        assert!(state.bootstrap().unwrap().online.unwrap().connected);
        assert_eq!(
            state.favorites_list(0, 20).unwrap().items[0].name,
            "Survives restart"
        );
        assert_eq!(
            state.with_store(|s| s.active_profile()).unwrap().as_deref(),
            Some(profile.as_str())
        );
        tauri::async_runtime::block_on(state.disconnect_online()).unwrap();
        assert!(secrets.load().unwrap().is_none());
        assert!(!state.online_status().unwrap().connected);
        let cached =
            tauri::async_runtime::block_on(state.online_details(&details.media_ref)).unwrap();
        assert!(cached.in_my_list);
        assert_eq!(cached.name, "Survives restart");
        drop(state);
        server.join().unwrap();
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn locked_online_keyring_reports_session_only_and_never_writes_credentials_to_database() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut bytes = [0; 8192];
            let _ = stream.read(&mut bytes).unwrap();
            let body = r#"{"status":"ok","service":"marquee-resolver"}"#;
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            )
            .unwrap();
        });
        let path =
            std::env::temp_dir().join(format!("marquee-online-{}.sqlite", uuid::Uuid::new_v4()));
        let mut state = AppState::new(path.clone(), Box::new(MemorySecrets::default()));
        state.online = OnlineService::with_secrets(Box::new(LockedSecrets));
        tauri::async_runtime::block_on(state.connect_online(
            &base,
            "private-client-id",
            "private-client-secret",
            true,
        ))
        .unwrap();
        let status = state.online_status().unwrap();
        assert_eq!(status.credential_status, CredentialStatus::Session);
        assert!(status.warning.unwrap().contains("until Marquee closes"));
        drop(state);
        let bytes = std::fs::read(&path).unwrap();
        assert!(!String::from_utf8_lossy(&bytes).contains("private-client"));
        std::fs::remove_file(path).unwrap();
        server.join().unwrap();
    }

    #[test]
    #[cfg(debug_assertions)]
    #[ignore = "requires explicit MARQUEE_RESOLVER_SMOKE_URL and main checkout development credentials"]
    fn live_resolver_health_search_details_and_resolution() {
        let base = std::env::var("MARQUEE_RESOLVER_SMOKE_URL")
            .expect("set MARQUEE_RESOLVER_SMOKE_URL explicitly");
        let credentials = development_credentials().unwrap();
        tauri::async_runtime::block_on(async {
            let session =
                ResolverSession::new(&base, &credentials.username, &credentials.password).unwrap();
            session.health().await.expect("live health failed");
            let items = session
                .search("inception")
                .await
                .expect("live search failed");
            assert!(!items.is_empty(), "live search returned no titles");
            let movie = items
                .iter()
                .find(|i| i.media_ref.kind == MediaKind::Movie)
                .expect("search must include a movie");
            let details = session
                .details(&movie.media_ref)
                .await
                .expect("live details failed");
            assert!(!details.name.is_empty());
            // Resolution depends on upstream availability; explicitly enable this additional call.
            if std::env::var_os("MARQUEE_RESOLVER_SMOKE_RESOLVE").is_some() {
                let stream = session
                    .resolve(&movie.media_ref.id)
                    .await
                    .expect("live stream resolution failed");
                assert!(stream.url.starts_with("http"));
            }
        });
    }

    #[test]
    fn resolve_prefers_the_resolver_stream_proxy_on_its_own_base() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut bytes = [0; 8192];
            let _ = stream.read(&mut bytes).unwrap();
            // The resolver names its public host in streamUrl; the client must ignore
            // that host and route through the base it actually reached.
            let body = r#"{"url":"https://cdn.test/raw.mp4","streamUrl":"https://public.example/stream?t=signed","referer":"https://www.wootly.ch/?v=abc"}"#;
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            )
            .unwrap();
        });
        let session = ResolverSession::new(&base, "id", "secret").unwrap();
        tauri::async_runtime::block_on(async {
            let resolved = session
                .resolve(&session.title_id("/m12345").unwrap())
                .await
                .unwrap();
            assert_eq!(
                resolved.url,
                format!("{base}/stream?t=signed"),
                "must use the proxy on our own base, not the public host"
            );
            assert!(resolved.referer.is_none(), "proxy applies its own referer");
        });
        server.join().unwrap();
    }

    #[test]
    fn validates_origin_and_namespaces_title_identity() {
        let a = ResolverSession::new("https://example.test/", "id", "secret").unwrap();
        let b = ResolverSession::new("https://other.test", "id", "secret").unwrap();
        assert_eq!(a.base.as_str(), "https://example.test/");
        assert_ne!(a.scope(), b.scope());
        let id = a.title_id("/m12345").unwrap();
        assert_eq!(a.upstream_id(&id).unwrap(), "/m12345");
        assert!(b.upstream_id(&id).is_err());
        for bad in [
            "file:///tmp/test",
            "https://user:pass@example.test",
            "https://example.test?key=secret",
            "https://example.test/#secret",
        ] {
            assert!(ResolverSession::new(bad, "id", "secret").is_err());
        }
        assert!(a.title_id("https://evil.test/video").is_err());
    }

    #[test]
    fn maps_series_episodes_without_signed_urls() {
        let session = ResolverSession::new("https://example.test", "id", "secret").unwrap();
        let raw = serde_json::json!({"id":"/t12345","kind":"tv","name":"Example","year":"2025","plot":"A story","seasons":[{"number":2,"episodes":[{"id":"/e12345","name":"Return","number":3,"season":2}]}]});
        let details = session.map_details(&raw).unwrap();
        assert_eq!(details.media_ref.kind, crate::models::MediaKind::Series);
        let episode = &details.seasons.unwrap()[0].episodes[0];
        assert_eq!(episode.title, "Return");
        assert_eq!((episode.season, episode.number), (2, 3));
        assert_eq!(session.upstream_id(&episode.id).unwrap(), "/e12345");
    }

    #[test]
    fn http_authentication_and_errors_are_sanitized() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            for (status, body) in [
                ("200 OK", r#"{"status":"ok","service":"marquee-resolver"}"#),
                ("401 Unauthorized", "private-secret"),
                ("302 Found", ""),
                (
                    "502 Bad Gateway",
                    r#"{"error":"host reports the file is gone (410): private-secret"}"#,
                ),
            ] {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut bytes = [0; 8192];
                let n = stream.read(&mut bytes).unwrap();
                let request = String::from_utf8_lossy(&bytes[..n]).to_lowercase();
                assert!(request.contains("cf-access-client-id: test-id"));
                assert!(request.contains("cf-access-client-secret: private-secret"));
                write!(stream,"HTTP/1.1 {status}\r\nContent-Length: {}\r\nLocation: https://elsewhere.test/\r\nConnection: close\r\n\r\n{body}",body.len()).unwrap();
            }
        });
        tauri::async_runtime::block_on(async {
            let session = ResolverSession::new(&base, "test-id", "private-secret").unwrap();
            session.health().await.unwrap();
            let error = session.health().await.unwrap_err();
            assert_eq!(error.code, "unauthenticated");
            assert!(!error.message.contains("private-secret"));
            assert!(session.health().await.is_err());
            let gone = session.health().await.unwrap_err();
            assert!(!gone.retryable);
            assert!(gone.message.contains("no longer available"));
            assert!(!gone.message.contains("private-secret"));
        });
        server.join().unwrap();
    }
}
