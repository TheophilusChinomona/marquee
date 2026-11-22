use crate::models::{AppError, EpgProgram, EpisodeDetails, MediaItem, MediaKind, SeasonDetails};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::fmt;
use std::time::Duration;
use url::Url;

fn invalid(message: &str) -> AppError {
    AppError::validation(message)
}

pub fn normalize_base(value: &str) -> Result<Url, AppError> {
    let mut base = Url::parse(value.trim())
        .map_err(|_| invalid("Enter a complete HTTP or HTTPS provider address."))?;
    if !["http", "https"].contains(&base.scheme())
        || base.host_str().is_none()
        || !base.username().is_empty()
        || base.password().is_some()
        || base.query().is_some()
        || base.fragment().is_some()
    {
        return Err(invalid(
            "Use an HTTP or HTTPS provider address without embedded credentials, query, or fragment.",
        ));
    }
    if !base.path().ends_with('/') {
        let path = format!("{}/", base.path());
        base.set_path(&path);
    }
    Ok(base)
}

pub fn sanitize_artwork_url(raw: Option<&str>) -> Option<String> {
    let trimmed = raw?.trim();
    if trimmed.is_empty() {
        return None;
    }
    let parsed = Url::parse(trimmed).ok()?;
    if matches!(parsed.scheme(), "http" | "https") && parsed.host_str().is_some() {
        Some(parsed.to_string())
    } else {
        None
    }
}

pub fn normalize_search_name(name: &str) -> String {
    name.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

pub fn extract_year(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    if bytes.len() < 4 {
        return None;
    }
    for i in 0..=bytes.len() - 4 {
        let slice = &bytes[i..i + 4];
        if slice.iter().all(|b| b.is_ascii_digit()) {
            let before_ok = i == 0 || !bytes[i - 1].is_ascii_digit();
            let after_ok = i + 4 == bytes.len() || !bytes[i + 4].is_ascii_digit();
            if before_ok && after_ok {
                let year_str = std::str::from_utf8(slice).ok()?;
                if let Ok(y) = year_str.parse::<u16>() {
                    if (1900..=2099).contains(&y) {
                        return Some(year_str.to_string());
                    }
                }
            }
        }
    }
    None
}

fn json_string(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => {
            let trimmed = s.trim();
            if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("null") {
                None
            } else {
                Some(trimmed.to_string())
            }
        }
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

fn json_f64(value: &Value) -> Option<f64> {
    match value {
        Value::Number(n) => n.as_f64().filter(|v| v.is_finite() && *v > 0.0),
        Value::String(s) => s
            .trim()
            .parse::<f64>()
            .ok()
            .filter(|v| v.is_finite() && *v > 0.0),
        _ => None,
    }
}

fn json_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        Value::String(s) => s.trim().parse::<i64>().ok(),
        _ => None,
    }
}

fn decode_response_bytes(bytes: &[u8]) -> String {
    match std::str::from_utf8(bytes) {
        Ok(s) => s.to_string(),
        Err(_) => {
            // Windows-1252 fallback (matching Android XtreamClient.decode).
            const CP1252_HIGH: [char; 32] = [
                '\u{20AC}', '\u{0081}', '\u{201A}', '\u{0192}', '\u{201E}', '\u{2026}',
                '\u{2020}', '\u{2021}', '\u{02C6}', '\u{2030}', '\u{0160}', '\u{2039}',
                '\u{0152}', '\u{008D}', '\u{017D}', '\u{008F}', '\u{0090}', '\u{2018}',
                '\u{2019}', '\u{201C}', '\u{201D}', '\u{2022}', '\u{2013}', '\u{2014}',
                '\u{02DC}', '\u{2122}', '\u{0161}', '\u{203A}', '\u{0153}', '\u{009D}',
                '\u{017E}', '\u{0178}',
            ];
            let mut out = String::with_capacity(bytes.len());
            for &b in bytes {
                if b < 0x80 {
                    out.push(b as char);
                } else if b < 0xA0 {
                    out.push(CP1252_HIGH[(b - 0x80) as usize]);
                } else {
                    out.push(b as char);
                }
            }
            out
        }
    }
}

fn decode_base64(input: &str) -> Option<Vec<u8>> {
    let mut cleaned = Vec::with_capacity(input.len());
    for b in input.bytes() {
        if b.is_ascii_whitespace() {
            continue;
        }
        cleaned.push(b);
    }
    if cleaned.len() < 4 || !cleaned.len().is_multiple_of(4) {
        return None;
    }
    let val = |c: u8| -> Option<u8> {
        match c {
            b'A'..=b'Z' => Some(c - b'A'),
            b'a'..=b'z' => Some(c - b'a' + 26),
            b'0'..=b'9' => Some(c - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            _ => None,
        }
    };
    let mut out = Vec::with_capacity((cleaned.len() / 4) * 3);
    for chunk in cleaned.chunks_exact(4) {
        let a = val(chunk[0])?;
        let b = val(chunk[1])?;
        out.push((a << 2) | (b >> 4));
        if chunk[2] != b'=' {
            let c = val(chunk[2])?;
            out.push((b << 4) | (c >> 2));
            if chunk[3] != b'=' {
                let d = val(chunk[3])?;
                out.push((c << 6) | d);
            }
        } else if chunk[3] != b'=' {
            return None;
        }
    }
    Some(out)
}

pub fn decode_epg_text(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.len() >= 4
        && trimmed
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'/' | b'=' | b' ' | b'\n' | b'\r' | b'\t'))
    {
        if let Some(bytes) = decode_base64(trimmed) {
            if let Ok(decoded) = String::from_utf8(bytes) {
                let printable = decoded
                    .chars()
                    .all(|c| c == '\t' || c == '\n' || c == '\r' || !c.is_control());
                if printable && !decoded.trim().is_empty() {
                    return decoded.trim().to_string();
                }
            }
        }
    }
    trimmed.to_string()
}

/// Parses unix seconds/millis or `YYYY-MM-DD HH:MM:SS` (UTC) into epoch milliseconds.
pub fn parse_epg_timestamp(obj: &Value, ts_key: &str, text_key: &str) -> Option<i64> {
    if let Some(ts) = json_i64(&obj[ts_key]) {
        if ts > 0 {
            return Some(if ts > 1_000_000_000_000 { ts } else { ts * 1000 });
        }
    }
    let text = json_string(&obj[text_key])?;
    if let Ok(ts) = text.parse::<i64>() {
        if ts > 0 {
            return Some(if ts > 1_000_000_000_000 { ts } else { ts * 1000 });
        }
    }
    parse_datetime_utc_ms(&text)
}

fn parse_datetime_utc_ms(text: &str) -> Option<i64> {
    // Supports "YYYY-MM-DD HH:MM:SS" or "YYYY-MM-DDTHH:MM:SS" with optional timezone offset or Z
    let s = text.trim();
    if s.len() < 19 {
        return None;
    }
    let b = s.as_bytes();
    if b[4] != b'-' || b[7] != b'-' || (b[10] != b' ' && b[10] != b'T') || b[13] != b':' || b[16] != b':' {
        return None;
    }
    let year: i64 = s[0..4].parse().ok()?;
    let month: i64 = s[5..7].parse().ok()?;
    let day: i64 = s[8..10].parse().ok()?;
    let hour: i64 = s[11..13].parse().ok()?;
    let min: i64 = s[14..16].parse().ok()?;
    let sec: i64 = s[17..19].parse().ok()?;
    if !(1970..=2200).contains(&year)
        || !(1..=12).contains(&month)
        || !(1..=31).contains(&day)
        || hour > 23
        || min > 59
        || sec > 59
    {
        return None;
    }
    let days = days_from_civil(year, month, day);
    let mut total_secs = days * 86_400 + hour * 3600 + min * 60 + sec;

    // Optional timezone suffix like "+02:00", "-0500", "Z"
    let rest = s[19..].trim();
    if rest.len() >= 5 && (rest.starts_with('+') || rest.starts_with('-')) {
        let sign = if rest.starts_with('+') { 1i64 } else { -1i64 };
        let digits: String = rest[1..].chars().filter(|c| c.is_ascii_digit()).collect();
        if digits.len() >= 4 {
            if let (Ok(oh), Ok(om)) = (digits[0..2].parse::<i64>(), digits[2..4].parse::<i64>()) {
                if oh <= 14 && om <= 59 {
                    total_secs -= sign * (oh * 3600 + om * 60);
                }
            }
        }
    }
    Some(total_secs * 1000)
}

/// Howard Hinnant's civil-from-days inverse algorithm (days since 1970-01-01).
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (month + if month > 2 { -3 } else { 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Credentials {
    pub base_url: String,
    pub username: String,
    pub password: String,
}

impl fmt::Debug for Credentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Credentials")
            .field("base_url", &self.base_url)
            .field("username", &"[REDACTED]")
            .field("password", &"[REDACTED]")
            .finish()
    }
}

#[derive(Debug, Clone)]
pub struct ParsedCategory {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone)]
pub struct ParsedCatalogItem {
    pub kind: MediaKind,
    pub id: String,
    pub name: String,
    pub normalized_name: String,
    pub category_id: String,
    pub poster: Option<String>,
    pub backdrop: Option<String>,
    pub year: Option<String>,
    pub rating: Option<f64>,
    pub extension: Option<String>,
    pub added: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedDetailsPayload {
    pub plot: Option<String>,
    pub genre: Option<String>,
    pub release_date: Option<String>,
    pub rating: Option<f64>,
    pub duration_minutes: Option<i32>,
    pub cast: Option<String>,
    pub backdrop_url: Option<String>,
    pub poster_url: Option<String>,
    pub seasons: Option<Vec<SeasonDetails>>,
}

#[derive(Clone)]
pub struct ResolvedStream {
    pub url: String,
    pub media: MediaItem,
    pub episode_id: Option<String>,
    pub episode_title: Option<String>,
}

#[derive(Clone)]
pub struct ProviderSession {
    pub credentials: Credentials,
    pub base: Url,
    client: reqwest::Client,
}

impl fmt::Debug for ProviderSession {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ProviderSession")
            .field("base", &self.base.as_str())
            .finish()
    }
}

impl ProviderSession {
    pub fn new(base: &str, username: &str, password: &str) -> Result<Self, AppError> {
        let base = normalize_base(base)?;
        if username.trim().is_empty()
            || password.is_empty()
            || username.len() > 4096
            || password.len() > 4096
        {
            return Err(invalid("Enter a valid username and password."));
        }
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(30))
            .connect_timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::none())
            .user_agent("Marquee/0.1.0")
            .build()
            .map_err(|_| AppError::network("Unable to initialize provider networking.", true))?;
        Ok(Self {
            credentials: Credentials {
                base_url: base.as_str().into(),
                username: username.into(),
                password: password.into(),
            },
            base,
            client,
        })
    }

    pub fn account_key(&self) -> String {
        let mut hash = Sha256::new();
        hash.update(self.base.as_str().as_bytes());
        hash.update([0]);
        hash.update(self.credentials.username.as_bytes());
        format!("{:x}", hash.finalize())
    }

    pub fn api_url(&self, action: &str, parameters: &[(&str, &str)]) -> Result<Url, AppError> {
        let mut url = self
            .base
            .join("player_api.php")
            .map_err(|_| invalid("Invalid provider address."))?;
        let mut query = url.query_pairs_mut();
        query
            .append_pair("username", &self.credentials.username)
            .append_pair("password", &self.credentials.password);
        if !action.is_empty() {
            query.append_pair("action", action);
        }
        for (key, value) in parameters {
            query.append_pair(key, value);
        }
        drop(query);
        Ok(url)
    }

    pub fn stream_url(&self, kind: &str, id: &str, extension: &str) -> Result<Url, AppError> {
        if !["movie", "series", "live"].contains(&kind) || id.is_empty() {
            return Err(invalid("Invalid media identity."));
        }
        let extension = if extension.is_empty() {
            if kind == "live" { "ts" } else { "mp4" }
        } else {
            extension
        };
        if extension.len() > 10 || !extension.bytes().all(|b| b.is_ascii_alphanumeric()) {
            return Err(invalid("Unsupported media extension."));
        }
        let mut url = self.base.clone();
        url.path_segments_mut()
            .map_err(|_| invalid("Invalid provider address."))?
            .pop_if_empty()
            .push(kind)
            .push(&self.credentials.username)
            .push(&self.credentials.password)
            .push(&format!("{id}.{extension}"));
        Ok(url)
    }

    /// Performs a GET against Xtream `player_api.php` with bounded retries (max 2)
    /// for transient network, 429, and 5xx errors only. Never retries 401/403.
    pub async fn request(&self, action: &str, parameters: &[(&str, &str)]) -> Result<Value, AppError> {
        let url = self.api_url(action, parameters)?;
        let max_retries = 2usize;

        for attempt in 0..=max_retries {
            let response = match self.client.get(url.clone()).send().await {
                Ok(resp) => resp,
                Err(_) => {
                    if attempt < max_retries {
                        tokio::time::sleep(Duration::from_millis(250 * (1 << attempt))).await;
                        continue;
                    }
                    return Err(AppError::network(
                        "Provider request failed. Check the connection and try again.",
                        true,
                    ));
                }
            };

            let status = response.status();
            if status == 401 || status == 403 {
                return Err(AppError::unauthenticated(
                    "Provider rejected these credentials.",
                ));
            }
            if status == 429 || status.is_server_error() {
                if attempt < max_retries {
                    let retry_after_secs = response
                        .headers()
                        .get(reqwest::header::RETRY_AFTER)
                        .and_then(|v| v.to_str().ok())
                        .and_then(|s| s.trim().parse::<u64>().ok())
                        .unwrap_or(0)
                        .min(5);
                    let delay = if retry_after_secs > 0 {
                        Duration::from_secs(retry_after_secs)
                    } else {
                        Duration::from_millis(300 * (1 << attempt))
                    };
                    tokio::time::sleep(delay).await;
                    continue;
                }
                return Err(AppError::network(
                    "Provider is temporarily unavailable. Please try again shortly.",
                    true,
                ));
            }
            if !status.is_success() {
                return Err(AppError::network(
                    "Provider returned an unsuccessful response.",
                    true,
                ));
            }

            let mut response = response;
            let mut bytes = Vec::new();
            let mut interrupted = false;
            loop {
                match response.chunk().await {
                    Ok(Some(chunk)) => {
                        if bytes.len() + chunk.len() > 64 * 1024 * 1024 {
                            return Err(AppError::provider(
                                "Provider response exceeds the supported size.",
                                false,
                            ));
                        }
                        bytes.extend_from_slice(&chunk);
                    }
                    Ok(None) => break,
                    Err(_) => {
                        interrupted = true;
                        break;
                    }
                }
            }
            if interrupted {
                if attempt < max_retries {
                    tokio::time::sleep(Duration::from_millis(250 * (1 << attempt))).await;
                    continue;
                }
                return Err(AppError::network(
                    "Provider response was interrupted.",
                    true,
                ));
            }
            let text = decode_response_bytes(&bytes);
            return serde_json::from_str(text.trim()).map_err(|_| {
                AppError::provider("Provider returned malformed data.", true)
            });
        }
        Err(AppError::network("Provider request failed.", true))
    }

    pub async fn authenticate(&self) -> Result<(), AppError> {
        validate_account(&self.request("", &[]).await?)
    }

    pub async fn fetch_categories(&self, kind: MediaKind) -> Result<Vec<ParsedCategory>, AppError> {
        let action = match kind {
            MediaKind::Live => "get_live_categories",
            MediaKind::Movie => "get_vod_categories",
            MediaKind::Series => "get_series_categories",
        };
        let value = self.request(action, &[]).await?;
        parse_categories(&value)
    }

    pub async fn fetch_category_items(
        &self,
        kind: MediaKind,
        category_id: &str,
    ) -> Result<Vec<ParsedCatalogItem>, AppError> {
        let action = match kind {
            MediaKind::Live => "get_live_streams",
            MediaKind::Movie => "get_vod_streams",
            MediaKind::Series => "get_series",
        };
        let value = self
            .request(action, &[("category_id", category_id)])
            .await?;
        parse_catalog_items(&value, kind, category_id)
    }

    pub async fn fetch_movie_details(&self, vod_id: &str) -> Result<CachedDetailsPayload, AppError> {
        let value = self
            .request("get_vod_info", &[("vod_id", vod_id)])
            .await?;
        Ok(parse_movie_details(&value))
    }

    pub async fn fetch_series_details(
        &self,
        series_id: &str,
    ) -> Result<CachedDetailsPayload, AppError> {
        let value = self
            .request("get_series_info", &[("series_id", series_id)])
            .await?;
        Ok(parse_series_details(&value))
    }

    pub async fn fetch_short_epg(
        &self,
        channel_id: &str,
        limit: usize,
    ) -> Result<Vec<EpgProgram>, AppError> {
        let limit_str = limit.clamp(1, 50).to_string();
        let value = self
            .request(
                "get_short_epg",
                &[("stream_id", channel_id), ("limit", &limit_str)],
            )
            .await?;
        Ok(parse_short_epg(&value, channel_id))
    }
}

pub fn validate_account(value: &Value) -> Result<(), AppError> {
    let info = &value["user_info"];
    let authenticated = info["auth"].as_i64() == Some(1) || info["auth"].as_str() == Some("1");
    if authenticated
        && info["status"]
            .as_str()
            .is_some_and(|s| s.eq_ignore_ascii_case("active"))
    {
        Ok(())
    } else {
        Err(AppError::unauthenticated(
            "Account is not active or credentials were rejected.",
        ))
    }
}

pub fn parse_categories(value: &Value) -> Result<Vec<ParsedCategory>, AppError> {
    let Some(array) = value.as_array() else {
        return Err(AppError::provider(
            "Provider returned an unexpected categories format.",
            true,
        ));
    };
    let mut out = Vec::with_capacity(array.len());
    for entry in array {
        let Some(obj) = entry.as_object() else {
            continue;
        };
        let Some(id) = obj.get("category_id").and_then(json_string) else {
            continue;
        };
        let name = obj
            .get("category_name")
            .and_then(json_string)
            .unwrap_or_else(|| id.clone());
        out.push(ParsedCategory { id, name });
    }
    Ok(out)
}

pub fn parse_catalog_items(
    value: &Value,
    kind: MediaKind,
    default_category_id: &str,
) -> Result<Vec<ParsedCatalogItem>, AppError> {
    let Some(array) = value.as_array() else {
        return Err(AppError::provider(
            "Provider returned an unexpected catalogue format.",
            true,
        ));
    };
    let mut out = Vec::with_capacity(array.len());
    for entry in array {
        let Some(obj) = entry.as_object() else {
            continue;
        };
        let id = match kind {
            MediaKind::Series => obj
                .get("series_id")
                .or_else(|| obj.get("stream_id"))
                .and_then(json_string),
            MediaKind::Movie | MediaKind::Live => obj
                .get("stream_id")
                .or_else(|| obj.get("id"))
                .and_then(json_string),
        };
        let Some(id) = id else {
            continue;
        };
        let fallback_name = match kind {
            MediaKind::Live => "Channel",
            MediaKind::Movie => "Movie",
            MediaKind::Series => "Series",
        };
        let name = obj
            .get("name")
            .and_then(json_string)
            .unwrap_or_else(|| fallback_name.to_string());
        let category_id = obj
            .get("category_id")
            .and_then(json_string)
            .unwrap_or_else(|| default_category_id.to_string());
        let poster_raw = match kind {
            MediaKind::Series => obj
                .get("cover")
                .or_else(|| obj.get("stream_icon"))
                .and_then(json_string),
            MediaKind::Movie | MediaKind::Live => obj
                .get("stream_icon")
                .or_else(|| obj.get("cover"))
                .and_then(json_string),
        };
        let poster = sanitize_artwork_url(poster_raw.as_deref());
        let backdrop = obj
            .get("backdrop_path")
            .and_then(|v| match v {
                Value::Array(arr) => arr.first().and_then(json_string),
                _ => json_string(v),
            })
            .and_then(|s| sanitize_artwork_url(Some(&s)));
        let year = obj
            .get("year")
            .or_else(|| obj.get("releaseDate"))
            .or_else(|| obj.get("release_date"))
            .and_then(json_string)
            .and_then(|s| extract_year(&s))
            .or_else(|| extract_year(&name));
        let rating = obj
            .get("rating")
            .and_then(json_f64)
            .or_else(|| obj.get("rating_5based").and_then(json_f64).map(|r| r * 2.0));
        let extension = match kind {
            MediaKind::Series => None,
            MediaKind::Live => Some(
                obj.get("container_extension")
                    .and_then(json_string)
                    .filter(|ext| ext.len() <= 10 && ext.bytes().all(|b| b.is_ascii_alphanumeric()))
                    .unwrap_or_else(|| "ts".to_string()),
            ),
            MediaKind::Movie => Some(
                obj.get("container_extension")
                    .and_then(json_string)
                    .filter(|ext| ext.len() <= 10 && ext.bytes().all(|b| b.is_ascii_alphanumeric()))
                    .unwrap_or_else(|| "mp4".to_string()),
            ),
        };
        let added = obj
            .get("added")
            .or_else(|| obj.get("last_modified"))
            .and_then(json_i64);

        out.push(ParsedCatalogItem {
            kind,
            id,
            normalized_name: normalize_search_name(&name),
            name,
            category_id,
            poster,
            backdrop,
            year,
            rating,
            extension,
            added,
        });
    }
    Ok(out)
}

fn parse_backdrop(info: &Value, fallback: Option<&str>) -> Option<String> {
    let from_backdrop = match &info["backdrop_path"] {
        Value::Array(arr) => arr.first().and_then(json_string),
        other => json_string(other),
    };
    sanitize_artwork_url(from_backdrop.as_deref()).or_else(|| sanitize_artwork_url(fallback))
}

fn parse_genre(info: &Value) -> Option<String> {
    match &info["genre"] {
        Value::Array(arr) => {
            let joined: Vec<String> = arr.iter().filter_map(json_string).collect();
            if joined.is_empty() {
                None
            } else {
                Some(joined.join(", "))
            }
        }
        other => json_string(other),
    }
}

pub fn parse_movie_details(root: &Value) -> CachedDetailsPayload {
    let info = &root["info"];
    let movie_data = &root["movie_data"];
    let poster = sanitize_artwork_url(
        json_string(&info["movie_image"])
            .or_else(|| json_string(&movie_data["stream_icon"]))
            .as_deref(),
    );
    let backdrop = parse_backdrop(
        info,
        json_string(&movie_data["stream_icon"])
            .or_else(|| json_string(&info["movie_image"]))
            .as_deref(),
    );
    let plot = json_string(&info["plot"]).or_else(|| json_string(&info["description"]));
    let release_date =
        json_string(&info["releasedate"]).or_else(|| json_string(&info["release_date"]));
    let duration_secs = json_i64(&info["duration_secs"]).unwrap_or(0);
    let duration_minutes = if duration_secs > 0 {
        Some((duration_secs / 60).max(1) as i32)
    } else {
        json_i64(&info["duration"]).filter(|d| *d > 0).map(|d| d as i32)
    };
    let cast = json_string(&info["cast"]).or_else(|| json_string(&info["actors"]));
    CachedDetailsPayload {
        plot,
        genre: parse_genre(info),
        release_date,
        rating: json_f64(&info["rating"]),
        duration_minutes,
        cast,
        backdrop_url: backdrop,
        poster_url: poster,
        seasons: None,
    }
}

pub fn parse_series_details(root: &Value) -> CachedDetailsPayload {
    let info = &root["info"];
    let poster = sanitize_artwork_url(json_string(&info["cover"]).as_deref());
    let backdrop = parse_backdrop(info, json_string(&info["cover"]).as_deref());
    let plot = json_string(&info["plot"]).or_else(|| json_string(&info["description"]));
    let release_date =
        json_string(&info["releaseDate"]).or_else(|| json_string(&info["release_date"]));
    let duration_minutes = json_i64(&info["episode_run_time"])
        .filter(|d| *d > 0)
        .map(|d| d as i32);
    let cast = json_string(&info["cast"]).or_else(|| json_string(&info["actors"]));

    let mut seasons: Vec<SeasonDetails> = Vec::new();
    let episodes_val = &root["episodes"];
    let mut collect_season = |season_num: i32, list_val: &Value| {
        let Some(arr) = list_val.as_array() else {
            return;
        };
        let mut episodes = Vec::with_capacity(arr.len());
        for (idx, item) in arr.iter().enumerate() {
            let Some(id) = json_string(&item["id"]) else {
                continue;
            };
            let ep_info = &item["info"];
            let season = json_i64(&item["season"]).map(|v| v as i32).unwrap_or(season_num);
            let number = json_i64(&item["episode_num"])
                .map(|v| v as i32)
                .unwrap_or((idx + 1) as i32);
            let title = json_string(&item["title"]).unwrap_or_else(|| format!("Episode {number}"));
            let ext = json_string(&item["container_extension"])
                .filter(|e| e.len() <= 10 && e.bytes().all(|b| b.is_ascii_alphanumeric()))
                .unwrap_or_else(|| "mp4".to_string());
            let duration_secs = json_i64(&ep_info["duration_secs"]).filter(|d| *d > 0);
            let duration_minutes = duration_secs.map(|s| (s / 60).max(1) as i32);
            episodes.push(EpisodeDetails {
                id,
                season,
                number,
                title,
                container_extension: Some(ext),
                image_url: sanitize_artwork_url(json_string(&ep_info["movie_image"]).as_deref()),
                plot: json_string(&ep_info["plot"]),
                duration_minutes,
                duration_ms: duration_secs.map(|s| s * 1000),
                rating: json_f64(&ep_info["rating"]),
                progress: None,
                position_ms: None,
            });
        }
        episodes.sort_by_key(|e| (e.number, e.id.clone()));
        if !episodes.is_empty() {
            seasons.push(SeasonDetails {
                number: season_num,
                episodes,
            });
        }
    };

    if let Some(map) = episodes_val.as_object() {
        for (key, list) in map {
            let num = key.parse::<i32>().unwrap_or(0);
            collect_season(num, list);
        }
    } else if let Some(arr) = episodes_val.as_array() {
        for (idx, list) in arr.iter().enumerate() {
            collect_season((idx + 1) as i32, list);
        }
    }
    seasons.sort_by_key(|s| s.number);

    CachedDetailsPayload {
        plot,
        genre: parse_genre(info),
        release_date,
        rating: json_f64(&info["rating"]),
        duration_minutes,
        cast,
        backdrop_url: backdrop,
        poster_url: poster,
        seasons: Some(seasons),
    }
}

pub fn parse_short_epg(value: &Value, channel_id: &str) -> Vec<EpgProgram> {
    let entries = if let Some(arr) = value.as_array() {
        arr
    } else if let Some(arr) = value["epg_listings"].as_array() {
        arr
    } else {
        return Vec::new();
    };
    let mut out = Vec::with_capacity(entries.len());
    for entry in entries {
        let Some(start_ms) = parse_epg_timestamp(entry, "start_timestamp", "start") else {
            continue;
        };
        let Some(end_ms) = parse_epg_timestamp(entry, "stop_timestamp", "end") else {
            continue;
        };
        if start_ms <= 0 || end_ms <= start_ms {
            continue;
        }
        let raw_title = json_string(&entry["title"]).unwrap_or_default();
        let title = decode_epg_text(&raw_title);
        let title = if title.is_empty() {
            "Live programme".to_string()
        } else {
            title
        };
        let description = json_string(&entry["description"])
            .map(|d| decode_epg_text(&d))
            .filter(|d| !d.is_empty());
        let id = json_string(&entry["id"]).unwrap_or_else(|| format!("{channel_id}_{start_ms}"));
        out.push(EpgProgram {
            id,
            channel_id: channel_id.to_string(),
            title,
            description,
            start_ms,
            end_ms,
        });
    }
    out.sort_by_key(|p| (p.start_ms, p.end_ms));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base_url_preserves_provider_path_and_rejects_ambiguous_inputs() {
        assert_eq!(
            normalize_base(" http://example.test:8080/iptv ")
                .unwrap()
                .as_str(),
            "http://example.test:8080/iptv/"
        );
        for value in [
            "file:///tmp/a",
            "https://a:b@host.test/",
            "https://host.test/?secret=1",
            "https://host.test/#x",
            "localhost:8080",
            "",
        ] {
            let error = normalize_base(value).unwrap_err();
            assert_eq!(error.code, "validation");
            assert!(!error.message.contains(value) || value.is_empty());
        }
    }

    #[test]
    fn credentials_are_encoded_and_never_in_debug_or_errors() {
        let p = ProviderSession::new("http://example.test/iptv", "a/b &?", "p/# &").unwrap();
        let debug_str = format!("{p:?} {:?}", p.credentials);
        assert!(!debug_str.contains("p/# &"));
        assert!(!debug_str.contains("a/b &?"));
        let api = p
            .api_url("get_vod_streams", &[("category_id", "12")])
            .unwrap();
        assert_eq!(api.path(), "/iptv/player_api.php");
        assert!(api.query_pairs().any(|(k, v)| k == "username" && v == "a/b &?"));
        let stream = p.stream_url("movie", "15", "mkv").unwrap();
        assert!(stream.as_str().contains("a%2Fb%20&%3F/p%2F%23%20&/15.mkv"));
        assert_ne!(
            p.account_key(),
            ProviderSession::new("http://example.test/iptv", "other", "p")
                .unwrap()
                .account_key()
        );
    }

    #[test]
    fn account_requires_authenticated_active_status() {
        for value in [
            serde_json::json!({"user_info":{"auth":0,"status":"Active"}}),
            serde_json::json!({"user_info":{"auth":1,"status":"Expired"}}),
            serde_json::json!({}),
        ] {
            assert!(validate_account(&value).is_err());
        }
        assert!(validate_account(&serde_json::json!({"user_info":{"auth":"1","status":"Active"}})).is_ok());
        assert!(validate_account(&serde_json::json!({"user_info":{"auth":1,"status":"Active"}})).is_ok());
    }

    #[test]
    fn parses_numeric_string_ids_strips_unsafe_artwork_and_handles_nulls() {
        let payload = serde_json::json!([
            {
                "stream_id": 42,
                "name": "Arrival (2016)",
                "category_id": 7,
                "stream_icon": "https://img.test/arrival.jpg",
                "rating": "7.9",
                "container_extension": null,
                "added": "1700000000"
            },
            {
                "stream_id": "99",
                "name": "Unsafe Art Film",
                "category_id": "7",
                "stream_icon": "javascript:alert(1)",
                "rating": null,
                "container_extension": "mkv"
            },
            {
                "stream_id": null,
                "name": "Missing Id"
            }
        ]);
        let items = parse_catalog_items(&payload, MediaKind::Movie, "7").unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].id, "42");
        assert_eq!(items[0].year.as_deref(), Some("2016"));
        assert_eq!(items[0].rating, Some(7.9));
        assert_eq!(items[0].extension.as_deref(), Some("mp4"));
        assert_eq!(items[0].poster.as_deref(), Some("https://img.test/arrival.jpg"));
        assert_eq!(items[1].id, "99");
        assert_eq!(items[1].poster, None);
        assert_eq!(items[1].extension.as_deref(), Some("mkv"));
    }

    #[test]
    fn parses_series_seasons_and_episodes_in_numeric_order() {
        let payload = serde_json::json!({
            "info": {
                "name": "Example Series",
                "cover": "https://img.test/cover.jpg",
                "backdrop_path": ["https://img.test/bg.jpg"],
                "plot": "A tense story.",
                "genre": ["Drama", "Sci-Fi"],
                "rating": 8.6,
                "episode_run_time": "55"
            },
            "episodes": {
                "2": [
                    { "id": 202, "season": 2, "episode_num": 2, "title": "Second", "container_extension": "mkv", "info": { "duration_secs": 3100 } },
                    { "id": "201", "season": 2, "episode_num": 1, "title": "First", "container_extension": "", "info": {} }
                ],
                "1": [
                    { "id": "101", "season": 1, "episode_num": 1, "title": "Pilot", "info": { "movie_image": "file:///etc/passwd" } }
                ]
            }
        });
        let details = parse_series_details(&payload);
        let seasons = details.seasons.unwrap();
        assert_eq!(seasons.len(), 2);
        assert_eq!(seasons[0].number, 1);
        assert_eq!(seasons[0].episodes[0].id, "101");
        assert_eq!(seasons[0].episodes[0].image_url, None);
        assert_eq!(seasons[1].number, 2);
        assert_eq!(seasons[1].episodes[0].id, "201");
        assert_eq!(seasons[1].episodes[0].container_extension.as_deref(), Some("mp4"));
        assert_eq!(seasons[1].episodes[1].id, "202");
        assert_eq!(seasons[1].episodes[1].duration_minutes, Some(51));
    }

    #[test]
    fn parses_base64_and_plain_epg_and_skips_invalid_timestamps() {
        // "Evening News" in base64 = "RXZlbmluZyBOZXdz"
        // "World headlines." in base64 = "V29ybGQgaGVhZGxpbmVzLg=="
        let payload = serde_json::json!({
            "epg_listings": [
                {
                    "id": "e2",
                    "title": "RXZlbmluZyBOZXdz",
                    "description": "V29ybGQgaGVhZGxpbmVzLg==",
                    "start_timestamp": "1700003600",
                    "stop_timestamp": "1700007200"
                },
                {
                    "id": "e1",
                    "title": "Morning Briefing",
                    "start": "2023-11-14 22:00:00",
                    "end": "2023-11-14 23:00:00"
                },
                {
                    "id": "bad",
                    "title": "Inverted",
                    "start_timestamp": 1700007200,
                    "stop_timestamp": 1700003600
                },
                {
                    "id": "missing",
                    "title": "No times"
                }
            ]
        });
        let programs = parse_short_epg(&payload, "ch1");
        assert_eq!(programs.len(), 2);
        assert_eq!(programs[0].title, "Morning Briefing");
        assert_eq!(programs[1].title, "Evening News");
        assert_eq!(programs[1].description.as_deref(), Some("World headlines."));
        assert_eq!(programs[1].start_ms, 1_700_003_600_000);
        assert_eq!(programs[1].end_ms, 1_700_007_200_000);
    }

    #[test]
    fn retries_transient_503_and_never_retries_401() {
        use std::io::{Read, Write};
        use std::sync::{Arc, atomic::{AtomicUsize, Ordering}};

        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", listener.local_addr().unwrap());
        let calls = Arc::new(AtomicUsize::new(0));
        let calls_clone = calls.clone();
        let server = std::thread::spawn(move || {
            // 1st: 503, 2nd: 200 OK, 3rd: 401 (should NOT retry)
            let responses = [
                ("503 Service Unavailable", r#"{"error":"busy"}"#),
                ("200 OK", r#"[{"category_id":"1","category_name":"Movies"}]"#),
                ("401 Unauthorized", r#"{"error":"nope"}"#),
            ];
            for (status, body) in responses {
                let (mut stream, _) = listener.accept().unwrap();
                calls_clone.fetch_add(1, Ordering::SeqCst);
                let mut buf = [0u8; 4096];
                let _ = stream.read(&mut buf);
                write!(
                    stream,
                    "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                )
                .unwrap();
            }
        });

        let session = ProviderSession::new(&base, "user", "pass").unwrap();
        tauri::async_runtime::block_on(async {
            let cats = session.fetch_categories(MediaKind::Movie).await.unwrap();
            assert_eq!(cats.len(), 1);
            assert_eq!(cats[0].name, "Movies");

            let err = session.fetch_categories(MediaKind::Live).await.unwrap_err();
            assert_eq!(err.code, "unauthenticated");
        });
        server.join().unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 3);
    }
}
