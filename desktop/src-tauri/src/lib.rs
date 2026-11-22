mod models;
#[tauri::command]
fn app_bootstrap() -> models::Bootstrap {
    models::Bootstrap::disconnected()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .invoke_handler(tauri::generate_handler![app_bootstrap])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
