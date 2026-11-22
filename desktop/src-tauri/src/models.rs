#[cfg(test)]
mod tests {
    #[test]
    fn first_boot_has_no_provider_or_demo_data() {
        let value = serde_json::to_value(super::Bootstrap::disconnected()).unwrap();
        assert_eq!(value["connected"], false);
        assert_eq!(value["credentialStatus"], "none");
        assert_eq!(value["activeProfileId"], serde_json::Value::Null);
        assert_eq!(value["profiles"].as_array().unwrap().len(), 0);
    }
}
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppError {
    pub code: &'static str,
    pub message: String,
    pub retryable: bool,
}

impl AppError {
    pub fn player(message: &str) -> Self {
        Self {
            code: "player",
            message: message.into(),
            retryable: true,
        }
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub avatar: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Bootstrap {
    pub development_player: bool,
    pub connected: bool,
    pub profiles: Vec<Profile>,
    pub active_profile_id: Option<String>,
    pub credential_status: &'static str,
}

impl Bootstrap {
    pub fn disconnected() -> Self {
        Self {
            development_player: false,
            connected: false,
            profiles: vec![],
            active_profile_id: None,
            credential_status: "none",
        }
    }
}
