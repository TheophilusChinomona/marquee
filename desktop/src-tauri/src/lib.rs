mod models;
mod player;
use player::service::{PlayerCommand, PlayerService, PlayerSnapshot};
use tauri::Manager;
#[tauri::command]
fn app_bootstrap(player: tauri::State<'_, PlayerService>) -> models::Bootstrap {
    let mut state = models::Bootstrap::disconnected();
    state.development_player = cfg!(debug_assertions) && player.active();
    state
}

#[tauri::command]
async fn player_snapshot(
    player: tauri::State<'_, PlayerService>,
) -> Result<PlayerSnapshot, models::AppError> {
    player.snapshot()
}

#[tauri::command]
async fn player_control(
    command: PlayerCommand,
    player: tauri::State<'_, PlayerService>,
) -> Result<(), models::AppError> {
    player.control(command)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(PlayerService::default())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .setup(|app| {
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
            app_bootstrap,
            player_snapshot,
            player_control
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
