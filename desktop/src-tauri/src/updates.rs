use crate::{
    catalog::CatalogService, models::AppError, player::service::PlayerService, state::AppState,
};
use serde::Serialize;
#[cfg(target_os = "linux")]
use tauri::Manager;
use tauri::{AppHandle, State};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateContext {
    version: String,
    enabled: bool,
    installable: bool,
}

#[tauri::command]
pub fn update_context(app: AppHandle) -> UpdateContext {
    #[cfg(target_os = "windows")]
    let installable = true;
    #[cfg(target_os = "linux")]
    let installable = app.env().appimage.is_some();
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    let installable = false;
    UpdateContext {
        version: app.package_info().version.to_string(),
        enabled: !cfg!(debug_assertions),
        installable,
    }
}

// Synchronous commands execute on the main thread, where the native surface must be destroyed.
#[tauri::command]
pub fn prepare_update(
    app: AppHandle,
    state: State<'_, AppState>,
    player: State<'_, PlayerService>,
    catalog: State<'_, CatalogService>,
) -> Result<(), AppError> {
    let context = update_context(app);
    if !context.enabled || !context.installable {
        return Err(AppError::unsupported(
            "Use a supported installed release to update Marquee",
        ));
    }
    // Fail before replacing the app if its database cannot be opened to save playback.
    state.with_store(|_| Ok(()))?;
    catalog.cancel_active_refresh();
    player.shutdown(&state);
    Ok(())
}
