use crate::{
    catalog::CatalogService,
    models::{
        AppError, AppSettings, Bootstrap, Category, EpgQueryResponse, HistoryItem,
        HomeSectionsResponse, MediaDetails, MediaItem, MediaKind, MediaRef, Page, Profile,
    },
    player::{
        service::PlayerService,
        session::{PlayerControlCommand, PlayerSnapshot},
    },
    recommendations,
    state::AppState,
};
use serde::Deserialize;
use tauri::Emitter;

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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogQueryArgs {
    pub kind: Option<MediaKind>,
    pub category_id: Option<String>,
    pub query: Option<String>,
    #[serde(default)]
    pub offset: usize,
    #[serde(default = "default_limit")]
    pub limit: usize,
    pub sort: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CategoriesArgs {
    pub kind: MediaKind,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaRefArgs {
    #[serde(rename = "ref")]
    pub media_ref: MediaRef,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EpgQueryArgs {
    pub channel_ids: Vec<String>,
    pub from_ms: i64,
    pub to_ms: i64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerStartArgs {
    #[serde(rename = "ref")]
    pub media_ref: MediaRef,
    pub episode_id: Option<String>,
    #[serde(default = "default_true")]
    pub resume: bool,
}

fn default_true() -> bool {
    true
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
    app: tauri::AppHandle,
    args: ConnectArgs,
    state: tauri::State<'_, AppState>,
    catalog: tauri::State<'_, CatalogService>,
    player: tauri::State<'_, PlayerService>,
) -> Result<Bootstrap, AppError> {
    player.stop_and_save(&app, &state);
    catalog.cancel_active_refresh();
    catalog.clear_epg_cache();
    state
        .connect(
            &args.base_url,
            &args.username,
            &args.password,
            args.remember,
        )
        .await
}

#[tauri::command]
pub async fn provider_disconnect(
    app: tauri::AppHandle,
    state: tauri::State<'_, AppState>,
    catalog: tauri::State<'_, CatalogService>,
    player: tauri::State<'_, PlayerService>,
) -> Result<(), AppError> {
    player.stop_and_save(&app, &state);
    catalog.cancel_active_refresh();
    catalog.clear_epg_cache();
    state.disconnect().await
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
    app: tauri::AppHandle,
    args: ProfileIdArgs,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<String, AppError> {
    player.stop_and_save(&app, &state);
    state.profile_remove(&args.id)
}

#[tauri::command]
pub fn profile_activate(
    app: tauri::AppHandle,
    args: ProfileIdArgs,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<(), AppError> {
    player.stop_and_save(&app, &state);
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
pub fn catalog_query(
    args: CatalogQueryArgs,
    state: tauri::State<'_, AppState>,
) -> Result<Page<MediaItem>, AppError> {
    let provider_id = state
        .with_store(|s| s.active_provider_id())?
        .ok_or_else(|| AppError::unauthenticated("Connect a provider to browse your library."))?;
    state.with_store(|s| {
        s.query_catalog(
            &provider_id,
            args.kind,
            args.category_id.as_deref(),
            args.query.as_deref(),
            args.offset,
            args.limit,
            args.sort.as_deref(),
        )
    })
}

#[tauri::command]
pub fn catalog_categories(
    args: CategoriesArgs,
    state: tauri::State<'_, AppState>,
) -> Result<Vec<Category>, AppError> {
    let provider_id = state
        .with_store(|s| s.active_provider_id())?
        .ok_or_else(|| AppError::unauthenticated("Connect a provider to browse your library."))?;
    state.with_store(|s| s.list_categories(&provider_id, args.kind))
}

#[tauri::command]
pub async fn catalog_refresh(
    app: tauri::AppHandle,
    state: tauri::State<'_, AppState>,
    catalog: tauri::State<'_, CatalogService>,
) -> Result<String, AppError> {
    catalog
        .refresh(&state, |event| {
            let _ = app.emit("catalog-progress", event);
        })
        .await
}

#[tauri::command]
pub fn catalog_refresh_cancel(catalog: tauri::State<'_, CatalogService>) {
    catalog.cancel_active_refresh();
}

#[tauri::command]
pub async fn media_details(
    args: MediaRefArgs,
    state: tauri::State<'_, AppState>,
    catalog: tauri::State<'_, CatalogService>,
) -> Result<MediaDetails, AppError> {
    if crate::online::is_online(&args.media_ref.id) {
        return state.online_details(&args.media_ref).await;
    }
    catalog.media_details(&state, &args.media_ref).await
}

#[tauri::command]
pub async fn epg_query(
    args: EpgQueryArgs,
    state: tauri::State<'_, AppState>,
    catalog: tauri::State<'_, CatalogService>,
) -> Result<EpgQueryResponse, AppError> {
    catalog
        .epg_query(&state, &args.channel_ids, args.from_ms, args.to_ms)
        .await
}

#[tauri::command]
pub fn home_sections(state: tauri::State<'_, AppState>) -> Result<HomeSectionsResponse, AppError> {
    let refreshing = state.refreshing.load(std::sync::atomic::Ordering::Relaxed);
    let mut home = state.with_store(|s| match (s.active_provider_id()?, s.active_profile()?) {
        (Some(provider), Some(profile)) => {
            recommendations::build_home_sections(s, &provider, &profile, refreshing)
        }
        _ => Ok(HomeSectionsResponse {
            hero: vec![],
            sections: vec![],
            cache_status: Default::default(),
        }),
    })?;
    let history: Vec<_> = state
        .history_list()?
        .into_iter()
        .filter_map(|h| h.item)
        .collect();
    let favorites = state.favorites_list(0, 200)?.items;
    home.sections
        .retain(|s| s.id != "continue-watching" && s.id != "my-list");
    // Replace both source-specific activity rails with the combined profile view.
    home.sections
        .retain(|s| s.kind != "continue" && s.kind != "my-list");
    if !history.is_empty() {
        home.sections.insert(
            0,
            crate::models::HomeSection {
                id: "continue-watching".into(),
                kind: "continue".into(),
                eyebrow: None,
                title: "Continue Watching".into(),
                items: history,
            },
        );
    }
    if !favorites.is_empty() {
        home.sections.push(crate::models::HomeSection {
            id: "my-list".into(),
            kind: "myList".into(),
            eyebrow: None,
            title: "My List".into(),
            items: favorites,
        });
    }
    Ok(home)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OnlineConnectArgs {
    pub base_url: String,
    pub client_id: String,
    pub client_secret: String,
    pub remember: bool,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OnlineImportArgs {
    pub base_url: String,
    pub remember: bool,
}
#[derive(Deserialize)]
pub struct OnlineSearchArgs {
    pub query: String,
}

#[tauri::command]
pub async fn online_connect(
    app: tauri::AppHandle,
    args: OnlineConnectArgs,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<Bootstrap, AppError> {
    state
        .connect_online(
            &args.base_url,
            &args.client_id,
            &args.client_secret,
            args.remember,
        )
        .await?;
    player.stop_and_save(&app, &state);
    state.bootstrap()
}
#[tauri::command]
pub async fn online_disconnect(
    app: tauri::AppHandle,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<(), AppError> {
    let result = state.disconnect_online().await;
    if player
        .snapshot()?
        .media
        .is_some_and(|m| crate::online::is_online(&m.media_ref.id))
    {
        player.stop_and_save(&app, &state);
    }
    result
}
#[tauri::command]
pub async fn online_check(
    state: tauri::State<'_, AppState>,
) -> Result<crate::online::OnlineStatus, AppError> {
    let (session, generation) = state.online.session()?;
    session.health().await?;
    if !state.online.is_current(&session.scope(), generation) {
        return Err(AppError::unauthenticated(
            "Online connection changed. Please retry.",
        ));
    }
    state.online_status()
}
#[tauri::command]
pub async fn online_import_development(
    app: tauri::AppHandle,
    args: OnlineImportArgs,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
) -> Result<Bootstrap, AppError> {
    #[cfg(debug_assertions)]
    {
        let credentials = crate::online::development_credentials()?;
        state
            .connect_online(
                &args.base_url,
                &credentials.username,
                &credentials.password,
                args.remember,
            )
            .await?;
        player.stop_and_save(&app, &state);
        state.bootstrap()
    }
    #[cfg(not(debug_assertions))]
    {
        let _ = (app, args, state, player);
        Err(AppError::unsupported(
            "Development credential import is unavailable in release builds.",
        ))
    }
}
#[tauri::command]
pub async fn online_search(
    args: OnlineSearchArgs,
    state: tauri::State<'_, AppState>,
) -> Result<Vec<MediaItem>, AppError> {
    state.search_online(&args.query).await
}

#[tauri::command]
pub async fn player_start(
    app: tauri::AppHandle,
    args: PlayerStartArgs,
    state: tauri::State<'_, AppState>,
    catalog: tauri::State<'_, CatalogService>,
    player: tauri::State<'_, PlayerService>,
) -> Result<PlayerSnapshot, AppError> {
    player
        .start(
            &app,
            &state,
            &catalog,
            &args.media_ref,
            args.episode_id.as_deref(),
            args.resume,
        )
        .await
}

#[tauri::command]
pub async fn player_snapshot(
    player: tauri::State<'_, PlayerService>,
) -> Result<PlayerSnapshot, AppError> {
    player.snapshot()
}

#[tauri::command]
pub async fn player_control(
    app: tauri::AppHandle,
    command: PlayerControlCommand,
    state: tauri::State<'_, AppState>,
    player: tauri::State<'_, PlayerService>,
    catalog: tauri::State<'_, CatalogService>,
) -> Result<PlayerSnapshot, AppError> {
    let snapshot = player.snapshot()?;
    if matches!(command, PlayerControlCommand::Resume)
        && snapshot.status == "ended"
        && let Some(media) = snapshot.media
        && crate::online::is_online(&media.media_ref.id)
    {
        return player
            .start(
                &app,
                &state,
                &catalog,
                &media.media_ref,
                snapshot.episode_id.as_deref(),
                false,
            )
            .await;
    }
    player.control(&app, &state, command)
}
