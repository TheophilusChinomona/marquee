use super::{
    NativePlayer, resolve_runtime_path,
    session::{
        PlayerControlCommand, PlayerSnapshot, PlayerStateEvent, QueueEpisode, QueueItem,
        SessionController, StartSessionRequest,
    },
    surface,
};
use crate::{
    catalog::CatalogService,
    models::{AppError, MediaKind, MediaRef},
    state::AppState,
    store::now_ms,
};
use std::sync::{
    Mutex,
    atomic::{AtomicBool, AtomicU64, Ordering},
};
use tauri::{AppHandle, Emitter, Manager};

#[derive(Default)]
pub struct PlayerService {
    start_generation: AtomicU64,
    pub controller: Mutex<SessionController>,
    init_lock: Mutex<()>,
    pub surface_handle: AtomicU64,
    resize_registered: AtomicBool,
    pub development_smoke: AtomicBool,
    pub poller_running: AtomicBool,
}

impl PlayerService {
    pub fn active(&self) -> bool {
        self.development_smoke.load(Ordering::Relaxed)
    }

    pub fn snapshot(&self) -> Result<PlayerSnapshot, AppError> {
        let ctrl = self
            .controller
            .lock()
            .map_err(|_| AppError::player("Player state unavailable."))?;
        Ok(ctrl.snapshot())
    }

    fn sync_surface_visibility(&self, app: &AppHandle, show: bool) {
        let handle = self.surface_handle.load(Ordering::Relaxed);
        if handle != 0
            && let Some(window) = app.get_webview_window("main")
        {
            let w = window.clone();
            let _ = window.run_on_main_thread(move || {
                if show {
                    surface::resize(&w, handle);
                }
                surface::set_visible(handle, show);
            });
        }
    }

    fn ensure_surface_and_engine(&self, app: &AppHandle) -> Result<(), AppError> {
        let _init_guard = self
            .init_lock
            .lock()
            .map_err(|_| AppError::player("Player initialization unavailable."))?;

        let needs_engine = {
            let ctrl = self
                .controller
                .lock()
                .map_err(|_| AppError::player("Player state unavailable."))?;
            !ctrl.has_engine()
        };
        if !needs_engine {
            return Ok(());
        }

        let window = app
            .get_webview_window("main")
            .ok_or_else(|| AppError::player("Main window is unavailable."))?;

        let existing_handle = self.surface_handle.load(Ordering::Relaxed);
        let (handle, created_here) = if existing_handle != 0 {
            (existing_handle, false)
        } else {
            let (tx, rx) = std::sync::mpsc::channel();
            let w_create = window.clone();
            window
                .run_on_main_thread(move || {
                    let res = surface::create(&w_create);
                    if let Ok(h) = res {
                        surface::resize(&w_create, h);
                        surface::set_visible(h, false);
                    }
                    let _ = tx.send(res);
                })
                .map_err(|_| AppError::player("Unable to access window UI thread."))?;
            let h = rx
                .recv()
                .map_err(|_| AppError::player("Unable to initialize video surface."))??;
            (h, true)
        };

        let resource_dir = app.path().resource_dir().ok();
        let runtime_path = resolve_runtime_path(resource_dir.as_deref());
        let engine = match NativePlayer::open(&runtime_path, handle) {
            Ok(eng) => eng,
            Err(err) => {
                if created_here {
                    let _ = window.run_on_main_thread(move || {
                        surface::destroy(handle);
                    });
                }
                return Err(err);
            }
        };

        self.surface_handle.store(handle, Ordering::SeqCst);

        if !self.resize_registered.swap(true, Ordering::SeqCst) {
            let resize_window = window.clone();
            let app_resize = app.clone();
            window.on_window_event(move |event| {
                if matches!(
                    event,
                    tauri::WindowEvent::Resized(_) | tauri::WindowEvent::ScaleFactorChanged { .. }
                ) {
                    let h = app_resize
                        .state::<PlayerService>()
                        .surface_handle
                        .load(Ordering::Relaxed);
                    if h != 0 {
                        surface::resize(&resize_window, h);
                    }
                }
            });
        }

        let mut ctrl = self
            .controller
            .lock()
            .map_err(|_| AppError::player("Player state unavailable."))?;
        ctrl.set_engine(Box::new(engine));
        Ok(())
    }

    fn ensure_poller(&self, app: &AppHandle) {
        if self.poller_running.swap(true, Ordering::SeqCst) {
            return;
        }
        let app_handle = app.clone();
        tauri::async_runtime::spawn(async move {
            let mut interval = tokio::time::interval(std::time::Duration::from_millis(250));
            loop {
                interval.tick().await;
                let player = app_handle.state::<PlayerService>();
                let app_state = app_handle.state::<AppState>();

                let poll_res = {
                    let Ok(mut ctrl) = player.controller.lock() else {
                        break;
                    };
                    if !ctrl.is_active() {
                        player.poller_running.store(false, Ordering::SeqCst);
                        break;
                    }
                    let snap = app_state
                        .with_store(|store| Ok(ctrl.tick(store, now_ms())))
                        .ok()
                        .flatten();
                    snap.map(|s| (s, ctrl.should_show_surface()))
                };

                if let Some((snap, show_surface)) = poll_res {
                    player.sync_surface_visibility(&app_handle, show_surface);
                    let _ = app_handle.emit(
                        "player-state",
                        PlayerStateEvent {
                            session_id: snap.session_id.clone(),
                            snapshot: snap,
                        },
                    );
                }
                let pending = player
                    .controller
                    .lock()
                    .ok()
                    .and_then(|mut ctrl| ctrl.take_pending_episode());
                if let Some(pending) = pending {
                    let transition_app = app_handle.clone();
                    tauri::async_runtime::spawn(async move {
                        let state = transition_app.state::<AppState>();
                        let player = transition_app.state::<PlayerService>();
                        let session = state.online.session();
                        let result = match &session {
                            Ok((session, _)) if session.scope() == pending.provider_id => {
                                session.resolve(&pending.episode_id).await
                            }
                            _ => Err(AppError::unauthenticated(
                                "Reconnect the online library to continue playback.",
                            )),
                        };
                        let _gate = state.change_gate.lock().await;
                        let current = session.as_ref().is_ok_and(|(session, generation)| {
                            state.online.is_current(&session.scope(), *generation)
                        }) && state
                            .with_store(|s| {
                                Ok(s.active_profile()?.as_deref()
                                    == Some(pending.profile_id.as_str()))
                            })
                            .unwrap_or(false)
                            && state.online_scope_for(&pending.media_ref).is_ok();
                        if let Ok(mut ctrl) = player.controller.lock() {
                            if current {
                                match result {
                                    Ok(resolved) => {
                                        if let Err(error) = ctrl.finish_pending_episode(
                                            &pending,
                                            &resolved.url,
                                            resolved.referer.as_deref(),
                                            now_ms(),
                                        ) {
                                            ctrl.fail_pending_episode(&pending, error);
                                        }
                                    }
                                    Err(error) => ctrl.fail_pending_episode(&pending, error),
                                }
                            } else {
                                ctrl.fail_pending_episode(&pending, AppError::unauthenticated("Account or profile changed before playback could continue."));
                            }
                            let snap = ctrl.snapshot();
                            let show = ctrl.should_show_surface();
                            drop(ctrl);
                            player.sync_surface_visibility(&transition_app, show);
                            let _ = transition_app.emit(
                                "player-state",
                                PlayerStateEvent {
                                    session_id: snap.session_id.clone(),
                                    snapshot: snap,
                                },
                            );
                        }
                    });
                }
            }
        });
    }

    pub async fn start(
        &self,
        app: &AppHandle,
        state: &AppState,
        catalog: &CatalogService,
        media_ref: &MediaRef,
        episode_id: Option<&str>,
        resume: bool,
    ) -> Result<PlayerSnapshot, AppError> {
        if crate::online::is_online(&media_ref.id) {
            return self
                .start_online(app, state, media_ref, episode_id, resume)
                .await;
        }
        let request = self.start_generation.fetch_add(1, Ordering::SeqCst) + 1;
        let (session, provider_id) = state.require_session()?;
        let (active_prov, profile_id) = state.active_scope()?;
        if active_prov != provider_id {
            return Err(AppError::unauthenticated(
                "Active provider changed. Please retry.",
            ));
        }

        let (resolved, episodes) = catalog
            .resolve_stream(state, &session, &provider_id, media_ref, episode_id)
            .await?;

        // Re-verify active provider and profile after async network resolution.
        let (post_prov, post_profile) = state.active_scope()?;
        if post_prov != provider_id
            || self.start_generation.load(Ordering::SeqCst) != request
            || post_profile != profile_id
            || state.active_session_for_provider(&provider_id)?.is_none()
        {
            return Err(AppError::unauthenticated(
                "Account or profile changed before playback could start.",
            ));
        }

        let queue: Vec<QueueEpisode> = episodes
            .into_iter()
            .filter_map(|ep| {
                let ext = ep.container_extension.as_deref().unwrap_or("mp4");
                let url = session.stream_url("series", &ep.id, ext).ok()?;
                Some(QueueEpisode {
                    item: QueueItem {
                        episode_id: ep.id,
                        season: ep.season,
                        number: ep.number,
                        title: ep.title,
                    },
                    url: url.to_string(),
                })
            })
            .collect();

        let (start_ms, settings) = state.with_store(|store| {
            let settings = store.settings()?;
            let mut pos_ms = 0u64;
            if resume && media_ref.kind != MediaKind::Live {
                let entries = store.item_history_entries(
                    &provider_id,
                    &profile_id,
                    media_ref.kind,
                    &media_ref.id,
                )?;
                let target_ep = resolved.episode_id.as_deref().unwrap_or("");
                if let Some(entry) = entries
                    .iter()
                    .find(|e| !e.completed && e.episode_id == target_ep)
                {
                    pos_ms = entry.position_ms.max(0) as u64;
                }
            }
            Ok((pos_ms, settings))
        })?;

        self.ensure_surface_and_engine(app)?;

        let (snap, show_surface) = {
            let mut ctrl = self
                .controller
                .lock()
                .map_err(|_| AppError::player("Player state unavailable."))?;
            ctrl.set_referer(None)?;
            let snap = state.with_store(|store| {
                ctrl.start(
                    StartSessionRequest {
                        provider_id: provider_id.clone(),
                        profile_id: profile_id.clone(),
                        media: resolved.media,
                        episode_id: resolved.episode_id,
                        episode_title: resolved.episode_title,
                        url: resolved.url,
                        start_ms,
                        queue,
                        autoplay_next: settings.autoplay_next,
                        preferred_audio: settings.preferred_audio,
                        subtitles_default: settings.subtitles_default,
                    },
                    store,
                    now_ms(),
                )
            })?;
            (snap, ctrl.should_show_surface())
        };

        self.sync_surface_visibility(app, show_surface);
        self.ensure_poller(app);
        let _ = app.emit(
            "player-state",
            PlayerStateEvent {
                session_id: snap.session_id.clone(),
                snapshot: snap.clone(),
            },
        );
        Ok(snap)
    }

    async fn start_online(
        &self,
        app: &AppHandle,
        state: &AppState,
        media_ref: &MediaRef,
        episode_id: Option<&str>,
        resume: bool,
    ) -> Result<PlayerSnapshot, AppError> {
        let request = self.start_generation.fetch_add(1, Ordering::SeqCst) + 1;
        let scope = state.online_scope_for(media_ref)?;
        let profile = state
            .with_store(|s| s.active_profile())?
            .ok_or_else(|| AppError::validation("Select a profile."))?;
        let (session, generation) = state.online.session()?;
        if session.scope() != scope {
            return Err(AppError::unauthenticated(
                "Online library changed. Please retry.",
            ));
        }
        let details = state.online_details(media_ref).await?;
        let episodes: Vec<_> = details
            .seasons
            .iter()
            .flatten()
            .flat_map(|s| &s.episodes)
            .collect();
        let target = if media_ref.kind == MediaKind::Series {
            episode_id
                .or(if resume {
                    details.resume_episode_id.as_deref()
                } else {
                    None
                })
                .or_else(|| episodes.first().map(|ep| ep.id.as_str()))
        } else {
            None
        };
        let episode = if media_ref.kind == MediaKind::Series {
            Some(
                episodes
                    .iter()
                    .find(|ep| Some(ep.id.as_str()) == target)
                    .ok_or_else(|| {
                        AppError::validation("This series has no matching playable episode.")
                    })?,
            )
        } else {
            None
        };
        let resolved = session.resolve(target.unwrap_or(&media_ref.id)).await?;
        let _gate = state.change_gate.lock().await;
        if self.start_generation.load(Ordering::SeqCst) != request
            || !state.online.is_current(&scope, generation)
            || state.with_store(|s| s.active_profile())?.as_deref() != Some(profile.as_str())
        {
            return Err(AppError::unauthenticated(
                "Account, profile, or playback request changed. Please retry.",
            ));
        }
        let (settings, start_ms) = state.with_store(|s| {
            let position = if resume {
                s.item_history_entries(&scope, &profile, media_ref.kind, &media_ref.id)?
                    .iter()
                    .find(|h| !h.completed && h.episode_id == target.unwrap_or(""))
                    .map(|h| h.position_ms.max(0) as u64)
                    .unwrap_or(0)
            } else {
                0
            };
            Ok((s.settings()?, position))
        })?;
        let queue = episodes
            .iter()
            .map(|ep| QueueEpisode {
                item: QueueItem {
                    episode_id: ep.id.clone(),
                    season: ep.season,
                    number: ep.number,
                    title: ep.title.clone(),
                },
                url: String::new(),
            })
            .collect();
        self.ensure_surface_and_engine(app)?;
        let (snap, show) = self.commit_online_start(
            state,
            request,
            generation,
            StartSessionRequest {
                provider_id: scope,
                profile_id: profile,
                media: crate::online::details_item(&details),
                episode_id: target.map(String::from),
                episode_title: episode
                    .map(|ep| format!("S{} E{} · {}", ep.season, ep.number, ep.title)),
                url: resolved.url,
                start_ms,
                queue,
                autoplay_next: settings.autoplay_next,
                preferred_audio: settings.preferred_audio,
                subtitles_default: settings.subtitles_default,
            },
            resolved.referer.as_deref(),
        )?;
        self.sync_surface_visibility(app, show);
        self.ensure_poller(app);
        let _ = app.emit(
            "player-state",
            PlayerStateEvent {
                session_id: snap.session_id.clone(),
                snapshot: snap.clone(),
            },
        );
        Ok(snap)
    }

    // Keep this final validation under the controller lock: Stop/profile changes
    // invalidate the request before they acquire that same lock.
    fn commit_online_start(
        &self,
        state: &AppState,
        request: u64,
        generation: u64,
        playback: StartSessionRequest,
        referer: Option<&str>,
    ) -> Result<(PlayerSnapshot, bool), AppError> {
        let mut ctrl = self
            .controller
            .lock()
            .map_err(|_| AppError::player("Player state unavailable."))?;
        let snap = state.with_store(|store| {
            if self.start_generation.load(Ordering::SeqCst) != request
                || !state.online.is_current(&playback.provider_id, generation)
                || store.active_profile()?.as_deref() != Some(playback.profile_id.as_str())
            {
                return Err(AppError::unauthenticated(
                    "Account, profile, or playback request changed. Please retry.",
                ));
            }
            ctrl.set_referer(referer)?;
            ctrl.start(playback, store, now_ms())
        })?;
        Ok((snap, ctrl.should_show_surface()))
    }

    pub fn control(
        &self,
        app: &AppHandle,
        state: &AppState,
        command: PlayerControlCommand,
    ) -> Result<PlayerSnapshot, AppError> {
        if matches!(command, PlayerControlCommand::Stop) {
            self.start_generation.fetch_add(1, Ordering::SeqCst);
        }
        let (snap, show_surface) = {
            let mut ctrl = self
                .controller
                .lock()
                .map_err(|_| AppError::player("Player state unavailable."))?;
            let snap = state.with_store(|store| ctrl.control(command, store, now_ms()))?;
            (snap, ctrl.should_show_surface())
        };
        self.sync_surface_visibility(app, show_surface);
        let _ = app.emit(
            "player-state",
            PlayerStateEvent {
                session_id: snap.session_id.clone(),
                snapshot: snap.clone(),
            },
        );
        Ok(snap)
    }

    pub fn stop_and_save(&self, app: &AppHandle, state: &AppState) {
        self.start_generation.fetch_add(1, Ordering::SeqCst);
        if let Ok(mut ctrl) = self.controller.lock() {
            let _ = state.with_store(|store| {
                ctrl.stop_and_save(store, now_ms());
                Ok(())
            });
        }
        self.sync_surface_visibility(app, false);
    }

    pub fn shutdown(&self, state: &AppState) {
        self.start_generation.fetch_add(1, Ordering::SeqCst);
        if let Ok(mut ctrl) = self.controller.lock() {
            let _ = state.with_store(|store| {
                ctrl.shutdown(store);
                Ok(())
            });
        }
        let handle = self.surface_handle.swap(0, Ordering::SeqCst);
        if handle != 0 {
            surface::destroy(handle);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        credentials::{CredentialError, CredentialStore},
        online::{OnlineService, ResolverSession},
        provider::Credentials,
    };
    struct SessionSecrets;
    impl CredentialStore for SessionSecrets {
        fn load(&self) -> Result<Option<Credentials>, CredentialError> {
            Ok(None)
        }
        fn save(&self, _: &Credentials) -> Result<(), CredentialError> {
            Err(CredentialError)
        }
        fn delete(&self) -> Result<(), CredentialError> {
            Ok(())
        }
    }

    #[test]
    fn online_start_commit_rejects_stop_and_profile_changes_before_touching_engine() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buffer = [0; 8192];
            let _ = stream.read(&mut buffer).unwrap();
            let body = r#"{"status":"ok","service":"marquee-resolver"}"#;
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            )
            .unwrap();
        });
        let path =
            std::env::temp_dir().join(format!("marquee-commit-{}.sqlite", uuid::Uuid::new_v4()));
        let mut state = AppState::new(path.clone(), Box::new(SessionSecrets));
        state.online = OnlineService::with_secrets(Box::new(SessionSecrets));
        tauri::async_runtime::block_on(state.connect_online(&base, "id", "secret", false)).unwrap();
        let session = ResolverSession::new(&base, "id", "secret").unwrap();
        let details = session
            .map_details(
                &serde_json::json!({"id":"/m12345","kind":"movie","name":"Movie","seasons":[]}),
            )
            .unwrap();
        let profile = state.profiles_list().unwrap()[0].id.clone();
        let (_, generation) = state.online.session().unwrap();
        let request = || StartSessionRequest {
            provider_id: session.scope(),
            profile_id: profile.clone(),
            media: crate::online::details_item(&details),
            episode_id: None,
            episode_title: None,
            url: "https://cdn.test/video.mp4".into(),
            start_ms: 0,
            queue: vec![],
            autoplay_next: false,
            preferred_audio: "English".into(),
            subtitles_default: false,
        };
        let player = PlayerService::default();
        let first = player.start_generation.fetch_add(1, Ordering::SeqCst) + 1;
        player.shutdown(&state);
        let error = player
            .commit_online_start(&state, first, generation, request(), None)
            .unwrap_err();
        assert_eq!(error.code, "unauthenticated");
        let next = player.start_generation.fetch_add(1, Ordering::SeqCst) + 1;
        let other = state.profile_create("Other", "blue").unwrap();
        state.profile_activate(&other.id).unwrap();
        let error = player
            .commit_online_start(&state, next, generation, request(), None)
            .unwrap_err();
        assert_eq!(error.code, "unauthenticated");
        assert_eq!(player.snapshot().unwrap().status, "stopped");
        drop(state);
        std::fs::remove_file(path).unwrap();
        server.join().unwrap();
    }
}
