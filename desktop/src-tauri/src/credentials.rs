#[cfg(test)]
mod tests {
    use super::*;
    use crate::provider::Credentials;
    struct Locked;
    impl CredentialStore for Locked {
        fn load(&self) -> Result<Option<Credentials>, CredentialError> { Err(CredentialError) }
        fn save(&self, _: &Credentials) -> Result<(), CredentialError> { Err(CredentialError) }
        fn delete(&self) -> Result<(), CredentialError> { Err(CredentialError) }
    }
    #[test]
    fn locked_store_never_reports_remembered_login() {
        let credentials = Credentials { base_url:"http://a.test/".into(),username:"user".into(),password:"secret".into() };
        assert_eq!(save_login(&Locked, &credentials, true), CredentialStatus::Session);
        assert_eq!(save_login(&Locked, &credentials, false), CredentialStatus::Session);
    }
}
use crate::provider::Credentials;
use serde::Serialize;

#[derive(Debug)]
pub struct CredentialError;
pub trait CredentialStore: Send + Sync {
    fn load(&self) -> Result<Option<Credentials>, CredentialError>;
    fn save(&self, credentials: &Credentials) -> Result<(), CredentialError>;
    fn delete(&self) -> Result<(), CredentialError>;
}
pub struct OsCredentials;
impl OsCredentials {
    fn entry(&self) -> Result<keyring::Entry, CredentialError> {
        keyring::Entry::new("app.marquee.desktop", "active-provider").map_err(|_| CredentialError)
    }
}
impl CredentialStore for OsCredentials {
    fn load(&self) -> Result<Option<Credentials>, CredentialError> {
        match self.entry()?.get_password() {
            Ok(value) => serde_json::from_str(&value).map(Some).map_err(|_| CredentialError),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(_) => Err(CredentialError),
        }
    }
    fn save(&self, credentials: &Credentials) -> Result<(), CredentialError> {
        let value = serde_json::to_string(credentials).map_err(|_| CredentialError)?;
        self.entry()?.set_password(&value).map_err(|_| CredentialError)
    }
    fn delete(&self) -> Result<(), CredentialError> {
        match self.entry()?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(_) => Err(CredentialError),
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all="lowercase")]
pub enum CredentialStatus { None, Session, Remembered }
pub fn save_login(store: &dyn CredentialStore, credentials: &Credentials, remember: bool) -> CredentialStatus {
    if remember && store.save(credentials).is_ok() { CredentialStatus::Remembered }
    else { let _ = store.delete(); CredentialStatus::Session }
}

