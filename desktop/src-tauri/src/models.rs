use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppError {
    pub code: String,
    pub message: String,
    pub retryable: bool,
}

impl AppError {
    pub fn new(code: &str, message: impl Into<String>, retryable: bool) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            retryable,
        }
    }

    pub fn validation(message: impl Into<String>) -> Self {
        Self::new("validation", message, false)
    }

    pub fn unauthenticated(message: impl Into<String>) -> Self {
        Self::new("unauthenticated", message, false)
    }

    pub fn network(message: impl Into<String>, retryable: bool) -> Self {
        Self::new("network", message, retryable)
    }

    pub fn provider(message: impl Into<String>, retryable: bool) -> Self {
        Self::new("provider", message, retryable)
    }

    pub fn storage(message: impl Into<String>, retryable: bool) -> Self {
        Self::new("storage", message, retryable)
    }

    pub fn player(message: impl Into<String>) -> Self {
        Self::new("player", message, true)
    }

    #[allow(dead_code)]
    pub fn unsupported(message: impl Into<String>) -> Self {
        Self::new("unsupported", message, false)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MediaKind {
    Live,
    Movie,
    Series,
}

impl MediaKind {
    pub fn as_str(self) -> &'static str {
        match self {
            MediaKind::Live => "live",
            MediaKind::Movie => "movie",
            MediaKind::Series => "series",
        }
    }

    pub fn parse(value: &str) -> Result<Self, AppError> {
        match value {
            "live" => Ok(MediaKind::Live),
            "movie" => Ok(MediaKind::Movie),
            "series" => Ok(MediaKind::Series),
            _ => Err(AppError::validation("Unsupported media kind.")),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaRef {
    pub kind: MediaKind,
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaItem {
    #[serde(rename = "ref")]
    pub media_ref: MediaRef,
    pub name: String,
    pub category_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub poster_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backdrop_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub year: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rating: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container_extension: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub added: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub progress: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub episode_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rank: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Category {
    pub id: String,
    pub name: String,
    pub kind: MediaKind,
    pub count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Page<T> {
    pub items: Vec<T>,
    pub total: usize,
    pub offset: usize,
    pub limit: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub avatar: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppSettings {
    pub autoplay_next: bool,
    pub subtitles_default: bool,
    pub preferred_audio: String,
    pub compact_library: bool,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            autoplay_next: true,
            subtitles_default: false,
            preferred_audio: "English".into(),
            compact_library: false,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CacheStatus {
    pub has_catalogue: bool,
    pub stale: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub refreshed_at_ms: Option<i64>,
    pub live_count: i64,
    pub movie_count: i64,
    pub series_count: i64,
    pub refreshing: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Bootstrap {
    pub online: Option<crate::online::OnlineStatus>,
    pub active_provider_id: Option<String>,
    pub provider_url: Option<String>,
    pub credential_warning: Option<String>,
    pub development_player: bool,
    pub connected: bool,
    pub profiles: Vec<Profile>,
    pub active_profile_id: Option<String>,
    pub credential_status: crate::credentials::CredentialStatus,
    pub cache_status: CacheStatus,
    pub settings: AppSettings,
}

impl Bootstrap {
    pub fn disconnected() -> Self {
        Self {
            online: None,
            active_provider_id: None,
            provider_url: None,
            credential_warning: None,
            development_player: false,
            connected: false,
            profiles: vec![],
            active_profile_id: None,
            credential_status: crate::credentials::CredentialStatus::None,
            cache_status: CacheStatus::default(),
            settings: AppSettings::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EpisodeDetails {
    pub id: String,
    pub season: i32,
    pub number: i32,
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container_extension: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub plot: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_minutes: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rating: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub progress: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position_ms: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeasonDetails {
    pub number: i32,
    pub episodes: Vec<EpisodeDetails>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaDetails {
    #[serde(rename = "ref")]
    pub media_ref: MediaRef,
    pub name: String,
    pub category_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub poster_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backdrop_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub plot: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub genre: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub year: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub release_date: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rating: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_minutes: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cast: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seasons: Option<Vec<SeasonDetails>>,
    pub related: Vec<MediaItem>,
    pub in_my_list: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resume_position_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resume_duration_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resume_episode_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryItem {
    #[serde(rename = "ref")]
    pub media_ref: MediaRef,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub episode_id: Option<String>,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub updated_at_ms: i64,
    pub completed: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub item: Option<MediaItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HomeSection {
    pub id: String,
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub eyebrow: Option<String>,
    pub title: String,
    pub items: Vec<MediaItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HomeSectionsResponse {
    pub hero: Vec<MediaItem>,
    pub sections: Vec<HomeSection>,
    pub cache_status: CacheStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogProgressEvent {
    pub refresh_id: String,
    pub status: String,
    pub stage: String,
    pub completed_categories: usize,
    pub total_categories: usize,
    pub live_count: i64,
    pub movie_count: i64,
    pub series_count: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<AppError>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EpgProgram {
    pub id: String,
    pub channel_id: String,
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub start_ms: i64,
    pub end_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EpgQueryResponse {
    pub channels: std::collections::HashMap<String, Vec<EpgProgram>>,
}

#[cfg(test)]
mod tests {
    #[test]
    fn first_boot_has_no_provider_or_demo_data() {
        let value = serde_json::to_value(super::Bootstrap::disconnected()).unwrap();
        assert_eq!(value["connected"], false);
        assert_eq!(value["credentialStatus"], "none");
        assert_eq!(value["activeProfileId"], serde_json::Value::Null);
        assert_eq!(value["profiles"].as_array().unwrap().len(), 0);
        assert_eq!(value["cacheStatus"]["hasCatalogue"], false);
        assert_eq!(value["settings"]["autoplayNext"], true);
    }
}
