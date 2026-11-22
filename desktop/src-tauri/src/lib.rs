mod commands;
mod credentials;
mod models;
mod player;
mod provider;
mod state;
mod store;

use player::service::PlayerService;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(PlayerService::default())
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

            #[cfg(debug_assertions)]
            if let Some(source) = std::env::var_os("MARQUEE_PLAYER_SMOKE") {
                let window = app
                    .get_webview_window("main")
                    .ok_or("main window missing")?;
                let surface = player::surface::create(&window).map_err(|e| e.message)?;
                let runtime = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                    .join("resources/mpv/libmpv-2.dll");
                let engine =
                    player::NativePlayer::open(&runtime, surface).map_err(|e| e.message)?;
                engine
                    .command(&["set", "loop-file", "inf"])
                    .map_err(|e| e.message)?;
                engine
                    .load(&source.to_string_lossy(), 0)
                    .map_err(|e| e.message)?;
                *app.state::<PlayerService>()
                    .0
                    .lock()
                    .map_err(|_| "player lock")? = Some(engine);
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
                if let Ok(mut player) = window.state::<PlayerService>().0.lock() {
                    player.take();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
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
            commands::player_snapshot,
            commands::player_control
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
