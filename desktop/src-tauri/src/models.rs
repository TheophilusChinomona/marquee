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
    pub connected: bool,
    pub profiles: Vec<Profile>,
    pub active_profile_id: Option<String>,
    pub credential_status: &'static str,
}

impl Bootstrap {
    pub fn disconnected() -> Self {
        Self {
            connected: false,
            profiles: vec![],
            active_profile_id: None,
            credential_status: "none",
        }
    }
}
