mod catalog;
mod commands;
mod credentials;
mod models;
mod online;
mod online_store;
mod player;
mod provider;
mod recommendations;
mod state;
mod store;

use catalog::CatalogService;
use player::service::PlayerService;
#[cfg(debug_assertions)]
use std::sync::atomic::Ordering;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(PlayerService::default())
        .manage(CatalogService::default())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .setup(|app| {
            let data_dir = app
                .path()
                .app_data_dir()
                .map_err(|e| format!("unable to resolve app data directory: {e}"))?;
            let db_path = data_dir.join("marquee.sqlite");
            app.manage(state::AppState::new(
                db_path,
                Box::new(credentials::OsCredentials),
            ));

            // Guard against offscreen window geometry when monitor setups change.
            if let Some(window) = app.get_webview_window("main")
                && let (Ok(pos), Ok(size), Ok(monitors)) = (
                    window.outer_position(),
                    window.outer_size(),
                    window.available_monitors(),
                )
            {
                let intersects = monitors.iter().any(|m| {
                    let mp = m.position();
                    let ms = m.size();
                    let wx2 = pos.x + size.width as i32;
                    let wy2 = pos.y + size.height as i32;
                    let mx2 = mp.x + ms.width as i32;
                    let my2 = mp.y + ms.height as i32;
                    pos.x < mx2 && wx2 > mp.x && pos.y < my2 && wy2 > mp.y
                });
                if !monitors.is_empty() && !intersects {
                    let _ = window.center();
                }
            }

            #[cfg(debug_assertions)]
            if let Some(source) = std::env::var_os("MARQUEE_PLAYER_SMOKE") {
                let window = app
                    .get_webview_window("main")
                    .ok_or("main window missing")?;
                let surface = player::surface::create(&window).map_err(|e| e.message)?;
                player::surface::set_visible(surface, true);
                let runtime =
                    player::resolve_runtime_path(app.path().resource_dir().ok().as_deref());
                let engine =
                    player::NativePlayer::open(&runtime, surface).map_err(|e| e.message)?;
                engine
                    .command(&["set", "loop-file", "inf"])
                    .map_err(|e| e.message)?;
                engine
                    .load(&source.to_string_lossy(), 0)
                    .map_err(|e| e.message)?;
                let player_state = app.state::<PlayerService>();
                player_state.surface_handle.store(surface, Ordering::SeqCst);
                player_state.development_smoke.store(true, Ordering::SeqCst);
                player_state
                    .controller
                    .lock()
                    .map_err(|_| "player lock")?
                    .set_engine(Box::new(engine));
                player::surface::resize(&window, surface);
                let resize_window = window.clone();
                window.on_window_event(move |event| {
                    if matches!(
                        event,
                        tauri::WindowEvent::Resized(_)
                            | tauri::WindowEvent::ScaleFactorChanged { .. }
                    ) {
                        player::surface::resize(&resize_window, surface);
                    }
                });
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if matches!(event, tauri::WindowEvent::CloseRequested { .. }) {
                window.state::<CatalogService>().cancel_active_refresh();
                let app_state = window.state::<state::AppState>();
                window.state::<PlayerService>().shutdown(&app_state);
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::online_connect,
            commands::online_disconnect,
            commands::online_check,
            commands::online_import_development,
            commands::online_search,
            commands::app_bootstrap,
            commands::provider_connect,
            commands::provider_disconnect,
            commands::profiles_list,
            commands::profile_create,
            commands::profile_update,
            commands::profile_remove,
            commands::profile_activate,
            commands::favorites_list,
            commands::favorite_set,
            commands::history_list,
            commands::settings_update,
            commands::catalog_query,
            commands::catalog_categories,
            commands::catalog_refresh,
            commands::catalog_refresh_cancel,
            commands::media_details,
            commands::epg_query,
            commands::home_sections,
            commands::player_start,
            commands::player_snapshot,
            commands::player_control
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
