use crate::{
    models::{
        AppError, AppSettings, Bootstrap, HistoryItem, MediaItem, MediaRef, Page, Profile,
    },
    player::service::{PlayerCommand, PlayerService, PlayerSnapshot},
    state::AppState,
};
use serde::Deserialize;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectArgs {
    pub base_url: String,
    pub username: String,
    pub password: String,
    pub remember: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileCreateArgs {
    pub name: String,
    pub avatar: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileUpdateArgs {
    pub id: String,
    pub name: String,
    pub avatar: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileIdArgs {
    pub id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FavoriteSetArgs {
    #[serde(rename = "ref")]
    pub media_ref: MediaRef,
    pub saved: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PageArgs {
    #[serde(default)]
    pub offset: usize,
    #[serde(default = "default_limit")]
    pub limit: usize,
}

fn default_limit() -> usize {
    60
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsUpdateArgs {
    pub autoplay_next: Option<bool>,
    pub subtitles_default: Option<bool>,
    pub preferred_audio: Option<String>,
    pub compact_library: Option<bool>,
}

#[tauri::command]
pub fn app_bootstrap(
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<Bootstrap, AppError> {
    let mut boot = state.bootstrap()?;
    boot.development_player = cfg!(debug_assertions) && player.active();
    Ok(boot)
}

#[tauri::command]
pub async fn provider_connect(
    args: ConnectArgs,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<Bootstrap, AppError> {
    let _ = player.control(PlayerCommand::Stop);
    state
        .connect(&args.base_url, &args.username, &args.password, args.remember)
        .await
}

#[tauri::command]
pub async fn provider_disconnect(
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<(), AppError> {
    let _ = player.control(PlayerCommand::Stop);
    state.disconnect()
}

#[tauri::command]
pub fn profiles_list(state: tauri::State<'_, AppState>) -> Result<Vec<Profile>, AppError> {
    state.profiles_list()
}

#[tauri::command]
pub fn profile_create(
    args: ProfileCreateArgs,
    state: tauri::State<'_, AppState>,
) -> Result<Profile, AppError> {
    state.profile_create(&args.name, &args.avatar)
}

#[tauri::command]
pub fn profile_update(
    args: ProfileUpdateArgs,
    state: tauri::State<'_, AppState>,
) -> Result<Profile, AppError> {
    state.profile_update(&args.id, &args.name, &args.avatar)
}

#[tauri::command]
pub fn profile_remove(
    args: ProfileIdArgs,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<String, AppError> {
    let _ = player.control(PlayerCommand::Stop);
    state.profile_remove(&args.id)
}

#[tauri::command]
pub fn profile_activate(
    args: ProfileIdArgs,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<(), AppError> {
    let _ = player.control(PlayerCommand::Stop);
    state.profile_activate(&args.id)
}

#[tauri::command]
pub fn favorites_list(
    args: PageArgs,
    state: tauri::State<'_, AppState>,
) -> Result<Page<MediaItem>, AppError> {
    state.favorites_list(args.offset, args.limit)
}

#[tauri::command]
pub fn favorite_set(
    args: FavoriteSetArgs,
    state: tauri::State<'_, AppState>,
) -> Result<(), AppError> {
    state.favorite_set(&args.media_ref, args.saved)
}

#[tauri::command]
pub fn history_list(state: tauri::State<'_, AppState>) -> Result<Vec<HistoryItem>, AppError> {
    state.history_list()
}

#[tauri::command]
pub fn settings_update(
    args: SettingsUpdateArgs,
    state: tauri::State<'_, AppState>,
) -> Result<AppSettings, AppError> {
    state.settings_update(
        args.autoplay_next,
        args.subtitles_default,
        args.preferred_audio.as_deref(),
        args.compact_library,
    )
}

#[tauri::command]
pub async fn player_snapshot(
    player: tauri::State<'_, PlayerService>,
) -> Result<PlayerSnapshot, AppError> {
    player.snapshot()
}

#[tauri::command]
pub async fn player_control(
    command: PlayerCommand,
    player: tauri::State<'_, PlayerService>,
) -> Result<(), AppError> {
    player.control(command)
}
