use crate::{
    models::{
        AppError, CatalogProgressEvent, EpgProgram, EpgQueryResponse, MediaDetails, MediaKind,
        MediaRef,
    },
    provider::{CachedDetailsPayload, ProviderSession, ResolvedStream},
    state::AppState,
    store::now_ms,
};
use std::{
    collections::{HashMap, HashSet},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};
use tokio::sync::{Notify, Semaphore};

const MAX_IN_FLIGHT_CATEGORIES: usize = 4;
const EPG_MAX_CHANNELS: usize = 50;
const EPG_MAX_WINDOW_MS: i64 = 24 * 60 * 60 * 1000;
const EPG_CACHE_TTL_MS: i64 = 10 * 60 * 1000;

struct ActiveRefresh {
    refresh_id: String,
    cancelled: Arc<AtomicBool>,
}

struct EpgCacheEntry {
    fetched_at_ms: i64,
    programs: Vec<EpgProgram>,
}

#[derive(Default)]
pub struct CatalogService {
    active_refresh: Mutex<Option<ActiveRefresh>>,
    epg_cache: Mutex<HashMap<(String, String), EpgCacheEntry>>,
    epg_inflight: Mutex<HashMap<(String, String), Arc<Notify>>>,
}

impl CatalogService {
    pub fn cancel_active_refresh(&self) {
        if let Ok(guard) = self.active_refresh.lock() {
            if let Some(active) = guard.as_ref() {
                active.cancelled.store(true, Ordering::SeqCst);
            }
        }
    }

    pub fn clear_epg_cache(&self) {
        if let Ok(mut cache) = self.epg_cache.lock() {
            cache.clear();
        }
    }

    pub async fn refresh<F>(
        &self,
        state: &AppState,
        mut on_progress: F,
    ) -> Result<String, AppError>
    where
        F: FnMut(CatalogProgressEvent),
    {
        let (session, provider_id) = state.require_session()?;
        let refresh_id = uuid::Uuid::new_v4().to_string();
        let generation = uuid::Uuid::new_v4().to_string();
        let cancelled = Arc::new(AtomicBool::new(false));

        {
            let mut guard = self
                .active_refresh
                .lock()
                .map_err(|_| AppError::storage("Refresh state unavailable.", true))?;
            if let Some(prev) = guard.take() {
                prev.cancelled.store(true, Ordering::SeqCst);
            }
            *guard = Some(ActiveRefresh {
                refresh_id: refresh_id.clone(),
                cancelled: cancelled.clone(),
            });
        }

        state.refreshing.store(true, Ordering::SeqCst);
        let begin_res = state.with_store(|s| s.begin_refresh_run(&refresh_id, &provider_id));
        if let Err(err) = begin_res {
            self.finish_refresh_slot(&refresh_id, state);
            return Err(err);
        }

        let result = self
            .run_refresh(
                state,
                &session,
                &provider_id,
                &refresh_id,
                &generation,
                &cancelled,
                &mut on_progress,
            )
            .await;

        self.finish_refresh_slot(&refresh_id, state);

        match result {
            Ok((live_count, movie_count, series_count, total_cats)) => {
                on_progress(CatalogProgressEvent {
                    refresh_id: refresh_id.clone(),
                    status: "completed".into(),
                    stage: "Library updated".into(),
                    completed_categories: total_cats,
                    total_categories: total_cats,
                    live_count,
                    movie_count,
                    series_count,
                    error: None,
                });
                Ok(refresh_id)
            }
            Err(err) => {
                let was_cancelled = cancelled.load(Ordering::SeqCst);
                let status = if was_cancelled { "cancelled" } else { "failed" };
                let _ = state.with_store(|s| {
                    s.discard_generation(&provider_id, &generation, &refresh_id, status)
                });
                on_progress(CatalogProgressEvent {
                    refresh_id: refresh_id.clone(),
                    status: status.into(),
                    stage: if was_cancelled {
                        "Refresh cancelled".into()
                    } else {
                        "Refresh failed".into()
                    },
                    completed_categories: 0,
                    total_categories: 0,
                    live_count: 0,
                    movie_count: 0,
                    series_count: 0,
                    error: if was_cancelled {
                        None
                    } else {
                        Some(err.clone())
                    },
                });
                Err(err)
            }
        }
    }

    fn finish_refresh_slot(&self, refresh_id: &str, state: &AppState) {
        if let Ok(mut guard) = self.active_refresh.lock() {
            if guard
                .as_ref()
                .is_some_and(|active| active.refresh_id == refresh_id)
            {
                *guard = None;
                state.refreshing.store(false, Ordering::SeqCst);
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    async fn run_refresh<F>(
        &self,
        state: &AppState,
        session: &ProviderSession,
        provider_id: &str,
        refresh_id: &str,
        generation: &str,
        cancelled: &Arc<AtomicBool>,
        on_progress: &mut F,
    ) -> Result<(i64, i64, i64, usize), AppError>
    where
        F: FnMut(CatalogProgressEvent),
    {
        let check_continue = || -> Result<(), AppError> {
            if cancelled.load(Ordering::SeqCst) {
                return Err(AppError::provider("Refresh was cancelled.", true));
            }
            let active = state.with_store(|s| s.active_provider_id())?;
            if active.as_deref() != Some(provider_id) {
                cancelled.store(true, Ordering::SeqCst);
                return Err(AppError::provider("Active provider changed.", false));
            }
            Ok(())
        };

        on_progress(CatalogProgressEvent {
            refresh_id: refresh_id.to_string(),
            status: "running".into(),
            stage: "categories".into(),
            completed_categories: 0,
            total_categories: 0,
            live_count: 0,
            movie_count: 0,
            series_count: 0,
            error: None,
        });

        let (live_cats, movie_cats, series_cats) = tokio::try_join!(
            session.fetch_categories(MediaKind::Live),
            session.fetch_categories(MediaKind::Movie),
            session.fetch_categories(MediaKind::Series),
        )?;
        check_continue()?;

        state.with_store(|s| {
            s.stage_categories(provider_id, generation, MediaKind::Live, &live_cats)?;
            s.stage_categories(provider_id, generation, MediaKind::Movie, &movie_cats)?;
            s.stage_categories(provider_id, generation, MediaKind::Series, &series_cats)
        })?;

        let mut tasks: Vec<(MediaKind, String)> = Vec::with_capacity(
            live_cats.len() + movie_cats.len() + series_cats.len(),
        );
        for c in &live_cats {
            tasks.push((MediaKind::Live, c.id.clone()));
        }
        for c in &movie_cats {
            tasks.push((MediaKind::Movie, c.id.clone()));
        }
        for c in &series_cats {
            tasks.push((MediaKind::Series, c.id.clone()));
        }

        let total_categories = tasks.len();
        let mut completed_categories = 0usize;
        let mut live_count = 0i64;
        let mut movie_count = 0i64;
        let mut series_count = 0i64;

        let semaphore = Arc::new(Semaphore::new(MAX_IN_FLIGHT_CATEGORIES));
        let mut join_set = tokio::task::JoinSet::new();

        for (kind, cat_id) in tasks {
            check_continue()?;
            let permit = semaphore
                .clone()
                .acquire_owned()
                .await
                .map_err(|_| AppError::provider("Refresh interrupted.", true))?;
            let session_clone = session.clone();
            let cancel_clone = cancelled.clone();
            join_set.spawn(async move {
                let _permit = permit;
                if cancel_clone.load(Ordering::SeqCst) {
                    return Err(AppError::provider("Refresh was cancelled.", true));
                }
                let items = session_clone.fetch_category_items(kind, &cat_id).await?;
                Ok((kind, items))
            });
        }

        while let Some(joined) = join_set.join_next().await {
            if let Err(err) = check_continue() {
                join_set.abort_all();
                return Err(err);
            }
            let task_result = match joined {
                Ok(res) => res,
                Err(_) => {
                    join_set.abort_all();
                    return Err(AppError::provider("Refresh task failed.", true));
                }
            };
            let (kind, items) = match task_result {
                Ok(pair) => pair,
                Err(err) => {
                    join_set.abort_all();
                    return Err(err);
                }
            };

            let count = items.len() as i64;
            match kind {
                MediaKind::Live => live_count += count,
                MediaKind::Movie => movie_count += count,
                MediaKind::Series => series_count += count,
            }
            if !items.is_empty() {
                state.with_store(|s| s.stage_items(provider_id, generation, &items))?;
            }
            completed_categories += 1;

            let stage = match kind {
                MediaKind::Live => "live channels",
                MediaKind::Movie => "movies",
                MediaKind::Series => "series",
            };
            on_progress(CatalogProgressEvent {
                refresh_id: refresh_id.to_string(),
                status: "running".into(),
                stage: stage.into(),
                completed_categories,
                total_categories,
                live_count,
                movie_count,
                series_count,
                error: None,
            });
        }

        check_continue()?;
        state.with_store(|s| s.publish_generation(provider_id, generation, refresh_id))?;
        Ok((live_count, movie_count, series_count, total_categories))
    }

    pub async fn media_details(
        &self,
        state: &AppState,
        media_ref: &MediaRef,
    ) -> Result<MediaDetails, AppError> {
        let (provider_id, profile_id) = state.active_scope()?;
        let (item, cached_json) = state
            .with_store(|s| s.get_item(&provider_id, media_ref.kind, &media_ref.id))?
            .ok_or_else(|| AppError::validation("Title not found in the local catalogue."))?;

        let mut payload: Option<CachedDetailsPayload> = cached_json
            .as_deref()
            .and_then(|raw| serde_json::from_str(raw).ok());

        if media_ref.kind != MediaKind::Live {
            if let Some(session) = state.active_session()? {
                let fetched = match media_ref.kind {
                    MediaKind::Movie => session.fetch_movie_details(&media_ref.id).await.ok(),
                    MediaKind::Series => session.fetch_series_details(&media_ref.id).await.ok(),
                    MediaKind::Live => None,
                };
                if let Some(fresh) = fetched {
                    if let Ok(json) = serde_json::to_string(&fresh) {
                        let _ = state.with_store(|s| {
                            s.save_item_details(&provider_id, media_ref.kind, &media_ref.id, &json)
                        });
                    }
                    payload = Some(fresh);
                }
            }
        }

        let (related, in_my_list, history_entries) = state.with_store(|s| {
            let related = s.related_items(
                &provider_id,
                media_ref.kind,
                &item.category_id,
                &item.media_ref.id,
                14,
            )?;
            let in_my_list = s.is_favorite(
                &provider_id,
                &profile_id,
                media_ref.kind.as_str(),
                &media_ref.id,
            )?;
            let history = s.item_history_entries(
                &provider_id,
                &profile_id,
                media_ref.kind,
                &media_ref.id,
            )?;
            Ok((related, in_my_list, history))
        })?;

        let latest_incomplete = history_entries.iter().find(|h| !h.completed);
        let resume_position_ms = latest_incomplete.map(|h| h.position_ms);
        let resume_duration_ms = latest_incomplete.map(|h| h.duration_ms);
        let resume_episode_id = latest_incomplete
            .filter(|h| !h.episode_id.is_empty())
            .map(|h| h.episode_id.clone());

        let episode_history: HashMap<&str, (i64, i64, bool)> = history_entries
            .iter()
            .filter(|h| !h.episode_id.is_empty())
            .map(|h| (h.episode_id.as_str(), (h.position_ms, h.duration_ms, h.completed)))
            .collect();

        let seasons = payload.as_ref().and_then(|p| p.seasons.clone()).map(|mut list| {
            for season in &mut list {
                for ep in &mut season.episodes {
                    if let Some(&(pos, dur, completed)) = episode_history.get(ep.id.as_str()) {
                        ep.position_ms = Some(pos);
                        if dur > 0 {
                            ep.progress = Some(if completed {
                                1.0
                            } else {
                                (pos as f64 / dur as f64).clamp(0.0, 1.0)
                            });
                        }
                    }
                }
            }
            list
        });

        Ok(MediaDetails {
            media_ref: item.media_ref,
            name: item.name,
            category_id: item.category_id,
            category_name: item.category_name,
            poster_url: payload
                .as_ref()
                .and_then(|p| p.poster_url.clone())
                .or(item.poster_url),
            backdrop_url: payload
                .as_ref()
                .and_then(|p| p.backdrop_url.clone())
                .or(item.backdrop_url),
            plot: payload.as_ref().and_then(|p| p.plot.clone()),
            genre: payload.as_ref().and_then(|p| p.genre.clone()),
            year: item.year,
            release_date: payload.as_ref().and_then(|p| p.release_date.clone()),
            rating: payload.as_ref().and_then(|p| p.rating).or(item.rating),
            duration_minutes: payload.as_ref().and_then(|p| p.duration_minutes),
            cast: payload.as_ref().and_then(|p| p.cast.clone()),
            seasons,
            related,
            in_my_list,
            resume_position_ms,
            resume_duration_ms,
            resume_episode_id,
        })
    }

    pub async fn epg_query(
        &self,
        state: &AppState,
        channel_ids: &[String],
        from_ms: i64,
        to_ms: i64,
    ) -> Result<EpgQueryResponse, AppError> {
        if channel_ids.len() > EPG_MAX_CHANNELS {
            return Err(AppError::validation(
                "EPG queries accept at most 50 channels at once.",
            ));
        }
        if to_ms <= from_ms || (to_ms - from_ms) > EPG_MAX_WINDOW_MS {
            return Err(AppError::validation(
                "EPG window must be positive and at most 24 hours.",
            ));
        }
        let provider_id = state
            .with_store(|s| s.active_provider_id())?
            .ok_or_else(|| AppError::unauthenticated("No active provider configured."))?;
        let session = state.active_session()?;

        let mut unique_ids = Vec::new();
        let mut seen = HashSet::new();
        for id in channel_ids {
            let trimmed = id.trim();
            if !trimmed.is_empty() && seen.insert(trimmed.to_string()) {
                unique_ids.push(trimmed.to_string());
            }
        }

        let mut result_map: HashMap<String, Vec<EpgProgram>> = HashMap::new();
        let mut to_fetch = Vec::new();
        let now = now_ms();

        {
            let cache = self
                .epg_cache
                .lock()
                .map_err(|_| AppError::storage("EPG cache unavailable.", true))?;
            for id in &unique_ids {
                if let Some(entry) = cache.get(&(provider_id.clone(), id.clone())) {
                    if now.saturating_sub(entry.fetched_at_ms) <= EPG_CACHE_TTL_MS {
                        result_map.insert(id.clone(), filter_epg_window(&entry.programs, from_ms, to_ms));
                        continue;
                    }
                }
                to_fetch.push(id.clone());
            }
        }

        if let Some(session) = session {
            let semaphore = Arc::new(Semaphore::new(MAX_IN_FLIGHT_CATEGORIES));
            let mut futures = Vec::new();
            for ch_id in to_fetch {
                let key = (provider_id.clone(), ch_id.clone());
                let wait_notify = {
                    let mut inflight = self
                        .epg_inflight
                        .lock()
                        .map_err(|_| AppError::storage("EPG state unavailable.", true))?;
                    if let Some(existing) = inflight.get(&key) {
                        Some((existing.clone(), false))
                    } else {
                        let notify = Arc::new(Notify::new());
                        inflight.insert(key.clone(), notify.clone());
                        Some((notify, true))
                    }
                };
                if let Some((notify, is_leader)) = wait_notify {
                    let session_c = session.clone();
                    let sem_c = semaphore.clone();
                    futures.push((key, notify, is_leader, session_c, sem_c));
                }
            }

            for (key, notify, is_leader, session_c, sem_c) in futures {
                if is_leader {
                    let fetched = match sem_c.acquire_owned().await {
                        Ok(_permit) => session_c.fetch_short_epg(&key.1, 24).await.unwrap_or_default(),
                        Err(_) => Vec::new(),
                    };
                    if let Ok(mut cache) = self.epg_cache.lock() {
                        cache.insert(
                            key.clone(),
                            EpgCacheEntry {
                                fetched_at_ms: now_ms(),
                                programs: fetched.clone(),
                            },
                        );
                    }
                    if let Ok(mut inflight) = self.epg_inflight.lock() {
                        inflight.remove(&key);
                    }
                    notify.notify_waiters();
                    result_map.insert(key.1, filter_epg_window(&fetched, from_ms, to_ms));
                } else {
                    notify.notified().await;
                    let programs = self
                        .epg_cache
                        .lock()
                        .ok()
                        .and_then(|c| c.get(&key).map(|e| e.programs.clone()))
                        .unwrap_or_default();
                    result_map.insert(key.1, filter_epg_window(&programs, from_ms, to_ms));
                }
            }
        } else {
            for ch_id in to_fetch {
                result_map.insert(ch_id, Vec::new());
            }
        }

        Ok(EpgQueryResponse {
            channels: result_map,
        })
    }

    /// Resolves a playable stream URL strictly inside Rust; credentials never cross IPC.
    pub async fn resolve_stream(
        &self,
        state: &AppState,
        media_ref: &MediaRef,
        episode_id: Option<&str>,
    ) -> Result<(ResolvedStream, Vec<crate::models::EpisodeDetails>), AppError> {
        let (session, provider_id) = state.require_session()?;
        let (item, cached_json) = state
            .with_store(|s| s.get_item(&provider_id, media_ref.kind, &media_ref.id))?
            .ok_or_else(|| AppError::validation("Selected title was not found in your library."))?;

        match media_ref.kind {
            MediaKind::Live => {
                let ext = item.container_extension.as_deref().unwrap_or("ts");
                let url = session.stream_url("live", &item.media_ref.id, ext)?;
                Ok((
                    ResolvedStream {
                        url: url.to_string(),
                        media: item,
                        episode_id: None,
                        episode_title: None,
                    },
                    Vec::new(),
                ))
            }
            MediaKind::Movie => {
                let ext = item.container_extension.as_deref().unwrap_or("mp4");
                let url = session.stream_url("movie", &item.media_ref.id, ext)?;
                Ok((
                    ResolvedStream {
                        url: url.to_string(),
                        media: item,
                        episode_id: None,
                        episode_title: None,
                    },
                    Vec::new(),
                ))
            }
            MediaKind::Series => {
                let mut payload: Option<CachedDetailsPayload> = cached_json
                    .as_deref()
                    .and_then(|raw| serde_json::from_str(raw).ok());
                let needs_fetch = payload
                    .as_ref()
                    .and_then(|p| p.seasons.as_ref())
                    .map(|s| s.is_empty())
                    .unwrap_or(true);
                if needs_fetch {
                    let fresh = session.fetch_series_details(&media_ref.id).await?;
                    if let Ok(json) = serde_json::to_string(&fresh) {
                        let _ = state.with_store(|s| {
                            s.save_item_details(&provider_id, MediaKind::Series, &media_ref.id, &json)
                        });
                    }
                    payload = Some(fresh);
                }
                let episodes: Vec<crate::models::EpisodeDetails> = payload
                    .and_then(|p| p.seasons)
                    .unwrap_or_default()
                    .into_iter()
                    .flat_map(|s| s.episodes)
                    .collect();

                let chosen = if let Some(ep_id) = episode_id.filter(|s| !s.trim().is_empty()) {
                    episodes
                        .iter()
                        .find(|e| e.id == ep_id)
                        .cloned()
                        .ok_or_else(|| {
                            AppError::validation("Selected episode was not found in this series.")
                        })?
                } else {
                    episodes.first().cloned().ok_or_else(|| {
                        AppError::provider("No playable episodes were returned for this series.", false)
                    })?
                };

                let ext = chosen.container_extension.as_deref().unwrap_or("mp4");
                let url = session.stream_url("series", &chosen.id, ext)?;
                let ep_label = format!("S{} E{} · {}", chosen.season, chosen.number, chosen.title);
                Ok((
                    ResolvedStream {
                        url: url.to_string(),
                        media: item,
                        episode_id: Some(chosen.id),
                        episode_title: Some(ep_label),
                    },
                    episodes,
                ))
            }
        }
    }
}

pub fn filter_epg_window(programs: &[EpgProgram], from_ms: i64, to_ms: i64) -> Vec<EpgProgram> {
    programs
        .iter()
        .filter(|p| p.end_ms > from_ms && p.start_ms < to_ms)
        .cloned()
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::credentials::{CredentialError, CredentialStore};
    use crate::provider::Credentials;
    use std::io::{Read, Write};
    use std::sync::{Arc, Mutex, atomic::AtomicUsize};

    #[derive(Clone, Default)]
    struct MemSecrets(Arc<Mutex<Option<Credentials>>>);
    impl CredentialStore for MemSecrets {
        fn load(&self) -> Result<Option<Credentials>, CredentialError> {
            Ok(self.0.lock().unwrap().clone())
        }
        fn save(&self, c: &Credentials) -> Result<(), CredentialError> {
            *self.0.lock().unwrap() = Some(c.clone());
            Ok(())
        }
        fn delete(&self) -> Result<(), CredentialError> {
            *self.0.lock().unwrap() = None;
            Ok(())
        }
    }

    fn spawn_fixture_server(
        handler: impl Fn(&str) -> (&'static str, String) + Send + Sync + 'static,
    ) -> (String, Arc<AtomicBool>, std::thread::JoinHandle<()>) {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let base = format!("http://{}/", listener.local_addr().unwrap());
        let stop = Arc::new(AtomicBool::new(false));
        let stop_clone = stop.clone();
        let handler = Arc::new(handler);
        let handle = std::thread::spawn(move || {
            while !stop_clone.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let _ = stream.set_nonblocking(false);
                        let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(5)));
                        let mut buf = [0u8; 8192];
                        if let Ok(n) = stream.read(&mut buf) {
                            let req = String::from_utf8_lossy(&buf[..n]);
                            let first_line = req.lines().next().unwrap_or("");
                            let path = first_line.split_whitespace().nth(1).unwrap_or("/");
                            let (status, body) = handler(path);
                            let _ = write!(
                                stream,
                                "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                                body.len(),
                                body
                            );
                        }
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(std::time::Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });
        (base, stop, handle)
    }

    #[test]
    fn atomic_refresh_preserves_catalogue_a_when_later_category_fails_for_b() {
        let phase = Arc::new(AtomicUsize::new(0));
        let phase_c = phase.clone();
        let (base, stop, server) = spawn_fixture_server(move |path| {
            if !path.contains("action=") {
                return ("200 OK", r#"{"user_info":{"auth":1,"status":"Active"}}"#.into());
            }
            let current = phase_c.load(Ordering::SeqCst);
            if path.contains("action=get_live_categories") {
                return ("200 OK", r#"[]"#.into());
            }
            if path.contains("action=get_series_categories") {
                return ("200 OK", r#"[]"#.into());
            }
            if path.contains("action=get_vod_categories") {
                if current == 0 {
                    return ("200 OK", r#"[{"category_id":"1","category_name":"Sci-Fi"}]"#.into());
                } else {
                    return (
                        "200 OK",
                        r#"[{"category_id":"1","category_name":"Sci-Fi"},{"category_id":"2","category_name":"Broken"}]"#.into(),
                    );
                }
            }
            if path.contains("action=get_vod_streams") && path.contains("category_id=1") {
                if current == 0 {
                    return (
                        "200 OK",
                        r#"[{"stream_id":10,"name":"Arrival (2016)","category_id":"1","rating":"8.0"}]"#.into(),
                    );
                } else {
                    return (
                        "200 OK",
                        r#"[{"stream_id":20,"name":"Replacement Film","category_id":"1","rating":"7.0"}]"#.into(),
                    );
                }
            }
            if path.contains("action=get_vod_streams") && path.contains("category_id=2") {
                return ("200 OK", r#"not-valid-json{"#.into());
            }
            ("200 OK", "[]".into())
        });

        let db_path = std::env::temp_dir().join(format!(
            "marquee-cat-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let state = AppState::new(db_path.clone(), Box::new(MemSecrets::default()));
        let catalog = CatalogService::default();

        tauri::async_runtime::block_on(async {
            state.connect(&base, "user", "pass", false).await.unwrap();
            let mut events = Vec::new();
            catalog.refresh(&state, |ev| events.push(ev)).await.unwrap();
            assert!(events.last().unwrap().status == "completed");

            let provider_id = state.with_store(|s| s.active_provider_id()).unwrap().unwrap();
            let page_a = state
                .with_store(|s| s.query_catalog(&provider_id, Some(MediaKind::Movie), None, None, 0, 20, None))
                .unwrap();
            assert_eq!(page_a.total, 1);
            assert_eq!(page_a.items[0].name, "Arrival (2016)");

            // Phase 1: category 1 returns Replacement Film, category 2 returns malformed JSON
            phase.store(1, Ordering::SeqCst);
            let err = catalog.refresh(&state, |_| {}).await.unwrap_err();
            assert_eq!(err.code, "provider");

            // Catalogue A must remain completely intact!
            let page_after = state
                .with_store(|s| s.query_catalog(&provider_id, Some(MediaKind::Movie), None, None, 0, 20, None))
                .unwrap();
            assert_eq!(page_after.total, 1);
            assert_eq!(page_after.items[0].name, "Arrival (2016)");
        });

        stop.store(true, Ordering::Relaxed);
        server.join().unwrap();
        drop(state);
        let _ = std::fs::remove_file(db_path);
    }

    #[test]
    fn valid_empty_catalogue_succeeds_and_epg_enforces_bounds_and_caches() {
        let epg_calls = Arc::new(AtomicUsize::new(0));
        let epg_calls_c = epg_calls.clone();
        let (base, stop, server) = spawn_fixture_server(move |path| {
            if !path.contains("action=") {
                return ("200 OK", r#"{"user_info":{"auth":1,"status":"Active"}}"#.into());
            }
            if path.contains("action=get_short_epg") {
                epg_calls_c.fetch_add(1, Ordering::SeqCst);
                return (
                    "200 OK",
                    r#"{"epg_listings":[{"id":"p1","title":"News","start_timestamp":1700000000,"stop_timestamp":1700003600}]}"#.into(),
                );
            }
            ("200 OK", "[]".into())
        });

        let db_path = std::env::temp_dir().join(format!(
            "marquee-empty-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let state = AppState::new(db_path.clone(), Box::new(MemSecrets::default()));
        let catalog = CatalogService::default();

        tauri::async_runtime::block_on(async {
            state.connect(&base, "user", "pass", false).await.unwrap();
            catalog.refresh(&state, |_| {}).await.unwrap();
            let boot = state.bootstrap().unwrap();
            assert!(boot.cache_status.has_catalogue);
            assert_eq!(boot.cache_status.movie_count, 0);

            // More than 50 channels rejected
            let too_many: Vec<String> = (0..51).map(|i| i.to_string()).collect();
            assert_eq!(
                catalog.epg_query(&state, &too_many, 1_700_000_000_000, 1_700_010_000_000).await.unwrap_err().code,
                "validation"
            );
            // Window > 24h rejected
            assert_eq!(
                catalog.epg_query(&state, &["1".into()], 1_700_000_000_000, 1_700_000_000_000 + EPG_MAX_WINDOW_MS + 1).await.unwrap_err().code,
                "validation"
            );
            // Valid query hits provider once, second query uses cache
            let res1 = catalog.epg_query(&state, &["1".into()], 1_699_999_000_000, 1_700_010_000_000).await.unwrap();
            assert_eq!(res1.channels["1"].len(), 1);
            let res2 = catalog.epg_query(&state, &["1".into()], 1_699_999_000_000, 1_700_010_000_000).await.unwrap();
            assert_eq!(res2.channels["1"].len(), 1);
            assert_eq!(epg_calls.load(Ordering::SeqCst), 1);
        });

        stop.store(true, Ordering::Relaxed);
        server.join().unwrap();
        drop(state);
        let _ = std::fs::remove_file(db_path);
    }
}
