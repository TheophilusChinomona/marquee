#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn base_url_preserves_provider_path_and_rejects_ambiguous_inputs() {
        assert_eq!(normalize_base(" http://example.test:8080/iptv ").unwrap().as_str(), "http://example.test:8080/iptv/");
        for value in ["file:///tmp/a", "https://a:b@host.test/", "https://host.test/?secret=1", "https://host.test/#x", "localhost:8080", ""] {
            let error = normalize_base(value).unwrap_err();
            assert_eq!(error.code, "validation");
            assert!(!error.message.contains(value) || value.is_empty());
        }
    }
    #[test]
    fn credentials_are_encoded_and_never_in_debug_or_errors() {
        let p = ProviderSession::new("http://example.test/iptv", "a/b &?", "p/# &").unwrap();
        let api = p.api_url("get_vod_streams", &[("category_id", "12")]).unwrap();
        assert_eq!(api.path(), "/iptv/player_api.php");
        assert!(api.query_pairs().any(|(k,v)| k == "username" && v == "a/b &?"));
        let stream = p.stream_url("movie", "15", "mkv").unwrap();
        assert!(stream.as_str().contains("a%2Fb%20&%3F/p%2F%23%20&/15.mkv"));
        assert_ne!(p.account_key(), ProviderSession::new("http://example.test/iptv", "other", "p").unwrap().account_key());
    }
    #[test]
    fn account_requires_authenticated_active_status() {
        for value in [serde_json::json!({"user_info":{"auth":0,"status":"Active"}}), serde_json::json!({"user_info":{"auth":1,"status":"Expired"}}), serde_json::json!({})] {
            assert!(validate_account(&value).is_err());
        }
        assert!(validate_account(&serde_json::json!({"user_info":{"auth":"1","status":"Active"}})).is_ok());
        assert!(validate_account(&serde_json::json!({"user_info":{"auth":1,"status":"Active"}})).is_ok());
    }
}
use crate::models::AppError;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use url::Url;

fn invalid(message: &str) -> AppError { AppError::new("validation", message, false) }
pub fn normalize_base(value: &str) -> Result<Url, AppError> {
    let mut base = Url::parse(value.trim()).map_err(|_| invalid("Enter a complete HTTP or HTTPS provider address."))?;
    if !["http", "https"].contains(&base.scheme()) || base.host_str().is_none()
        || !base.username().is_empty() || base.password().is_some() || base.query().is_some() || base.fragment().is_some() {
        return Err(invalid("Use an HTTP or HTTPS provider address without embedded credentials, query, or fragment."));
    }
    if !base.path().ends_with('/') { let path = format!("{}/", base.path()); base.set_path(&path); }
    Ok(base)
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Credentials { pub base_url: String, pub username: String, pub password: String }

#[derive(Clone)]
pub struct ProviderSession { pub credentials: Credentials, pub base: Url, client: reqwest::Client }
impl ProviderSession {
    pub fn new(base: &str, username: &str, password: &str) -> Result<Self, AppError> {
        let base = normalize_base(base)?;
        if username.trim().is_empty() || password.is_empty() || username.len() > 4096 || password.len() > 4096 {
            return Err(invalid("Enter a valid username and password."));
        }
        let client = reqwest::Client::builder().timeout(std::time::Duration::from_secs(30))
            .connect_timeout(std::time::Duration::from_secs(15)).redirect(reqwest::redirect::Policy::none())
            .user_agent("Marquee/0.1.0").build().map_err(|_| AppError::new("network", "Unable to initialize provider networking.", true))?;
        Ok(Self { credentials: Credentials { base_url: base.as_str().into(), username: username.into(), password: password.into() }, base, client })
    }
    pub fn account_key(&self) -> String {
        let mut hash = Sha256::new();
        hash.update(self.base.as_str().as_bytes()); hash.update([0]); hash.update(self.credentials.username.as_bytes());
        format!("{:x}", hash.finalize())
    }
    pub fn api_url(&self, action: &str, parameters: &[(&str,&str)]) -> Result<Url, AppError> {
        let mut url = self.base.join("player_api.php").map_err(|_| invalid("Invalid provider address."))?;
        let mut query = url.query_pairs_mut();
        query.append_pair("username", &self.credentials.username).append_pair("password", &self.credentials.password);
        if !action.is_empty() { query.append_pair("action", action); }
        for (key,value) in parameters { query.append_pair(key,value); }
        drop(query); Ok(url)
    }
    pub fn stream_url(&self, kind: &str, id: &str, extension: &str) -> Result<Url, AppError> {
        if !["movie", "series", "live"].contains(&kind) || id.is_empty() { return Err(invalid("Invalid media identity.")); }
        let extension = if extension.is_empty() { if kind == "live" { "ts" } else { "mp4" } } else { extension };
        if extension.len() > 10 || !extension.bytes().all(|b| b.is_ascii_alphanumeric()) { return Err(invalid("Unsupported media extension.")); }
        let mut url = self.base.clone();
        url.path_segments_mut().map_err(|_| invalid("Invalid provider address."))?
            .pop_if_empty().push(kind).push(&self.credentials.username).push(&self.credentials.password).push(&format!("{id}.{extension}"));
        Ok(url)
    }
    pub async fn request(&self, action: &str, parameters: &[(&str,&str)]) -> Result<Value, AppError> {
        let url = self.api_url(action, parameters)?;
        let mut response = self.client.get(url).send().await.map_err(|_| AppError::new("network", "Provider request failed. Check the connection and try again.", true))?;
        if response.status() == 401 || response.status() == 403 { return Err(AppError::unauthenticated("Provider rejected these credentials.")); }
        if !response.status().is_success() { return Err(AppError::network("Provider returned an unsuccessful response.", true)); }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| AppError::network("Provider response was interrupted.", true))? {
            if bytes.len() + chunk.len() > 64 * 1024 * 1024 { return Err(AppError::provider("Provider response exceeds the supported size.", false)); }
            bytes.extend_from_slice(&chunk);
        }
        serde_json::from_slice(&bytes).map_err(|_| AppError::provider("Provider returned malformed data.", true))
    }
    pub async fn authenticate(&self) -> Result<(), AppError> { validate_account(&self.request("", &[]).await?) }
}
pub fn validate_account(value: &Value) -> Result<(), AppError> {
    let info = &value["user_info"];
    let authenticated = info["auth"].as_i64() == Some(1) || info["auth"].as_str() == Some("1");
    if authenticated && info["status"].as_str().is_some_and(|s| s.eq_ignore_ascii_case("active")) { Ok(()) }
    else { Err(AppError::unauthenticated("Account is not active or credentials were rejected.")) }
}

