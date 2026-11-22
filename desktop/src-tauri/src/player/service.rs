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
    pub controller: Mutex<SessionController>,
    pub surface_handle: AtomicU64,
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

    fn ensure_surface_and_engine(&self, app: &AppHandle) -> Result<(), AppError> {
        let needs_engine = {
            let ctrl = self
                .controller
                .lock()
                .map_err(|_| AppError::player("Player state unavailable."))?;
            !ctrl.has_engine()
        };
        if !needs_engine {
            let handle = self.surface_handle.load(Ordering::Relaxed);
            if handle != 0 {
                if let Some(window) = app.get_webview_window("main") {
                    let w = window.clone();
                    let _ = window.run_on_main_thread(move || {
                        surface::resize(&w, handle);
                        surface::set_visible(handle, true);
                    });
                }
            }
            return Ok(());
        }

        let window = app
            .get_webview_window("main")
            .ok_or_else(|| AppError::player("Main window is unavailable."))?;

        let (tx, rx) = std::sync::mpsc::channel();
        let w_create = window.clone();
        window
            .run_on_main_thread(move || {
                let res = surface::create(&w_create);
                if let Ok(h) = res {
                    surface::resize(&w_create, h);
                    surface::set_visible(h, true);
                }
                let _ = tx.send(res);
            })
            .map_err(|_| AppError::player("Unable to access window UI thread."))?;

        let handle = rx
            .recv()
            .map_err(|_| AppError::player("Unable to initialize video surface."))??;
        self.surface_handle.store(handle, Ordering::SeqCst);

        let resize_window = window.clone();
        window.on_window_event(move |event| {
            if matches!(
                event,
                tauri::WindowEvent::Resized(_) | tauri::WindowEvent::ScaleFactorChanged { .. }
            ) {
                surface::resize(&resize_window, handle);
            }
        });

        let resource_dir = app.path().resource_dir().ok();
        let runtime_path = resolve_runtime_path(resource_dir.as_deref());
        let engine = NativePlayer::open(&runtime_path, handle)?;
        let mut ctrl = self
            .controller
            .lock()
            .map_err(|_| AppError::player("Player state unavailable."))?;
        ctrl.set_engine(Box::new(engine));
        Ok(())
    }

    fn hide_surface(&self, app: &AppHandle) {
        let handle = self.surface_handle.load(Ordering::Relaxed);
        if handle != 0 {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.run_on_main_thread(move || {
                    surface::set_visible(handle, false);
                });
            }
        }
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

                let snapshot_opt = {
                    let Ok(mut ctrl) = player.controller.lock() else {
                        break;
                    };
                    if !ctrl.is_active() {
                        player.poller_running.store(false, Ordering::SeqCst);
                        break;
                    }
                    app_state
                        .with_store(|store| Ok(ctrl.tick(store, now_ms())))
                        .ok()
                        .flatten()
                };

                if let Some(snap) = snapshot_opt {
                    if snap.status == "stopped" {
                        player.hide_surface(&app_handle);
                    }
                    let _ = app_handle.emit(
                        "player-state",
                        PlayerStateEvent {
                            session_id: snap.session_id.clone(),
                            snapshot: snap,
                        },
                    );
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
        let (provider_id, profile_id) = state.active_scope()?;
        let (resolved, episodes) = catalog.resolve_stream(state, media_ref, episode_id).await?;
        let (session, _) = state.require_session()?;

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
                let entries =
                    store.item_history_entries(&provider_id, &profile_id, media_ref.kind, &media_ref.id)?;
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

        let snap = {
            let mut ctrl = self
                .controller
                .lock()
                .map_err(|_| AppError::player("Player state unavailable."))?;
            state.with_store(|store| {
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
                        subtitles_default: settings.subtitles_default,
                    },
                    store,
                    now_ms(),
                )
            })?
        };

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

    pub fn control(
        &self,
        app: &AppHandle,
        state: &AppState,
        command: PlayerControlCommand,
    ) -> Result<PlayerSnapshot, AppError> {
        let is_stop = matches!(command, PlayerControlCommand::Stop);
        let snap = {
            let mut ctrl = self
                .controller
                .lock()
                .map_err(|_| AppError::player("Player state unavailable."))?;
            state.with_store(|store| ctrl.control(command, store, now_ms()))?
        };
        if is_stop {
            self.hide_surface(app);
        }
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
        if let Ok(mut ctrl) = self.controller.lock() {
            let _ = state.with_store(|store| {
                ctrl.stop_and_save(store, now_ms());
                Ok(())
            });
        }
        self.hide_surface(app);
    }

    pub fn shutdown(&self, state: &AppState) {
        if let Ok(mut ctrl) = self.controller.lock() {
            let _ = state.with_store(|store| {
                ctrl.shutdown(store);
                Ok(())
            });
        }
    }
}
