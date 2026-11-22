use crate::{
    credentials::{CredentialStatus, CredentialStore, save_login},
    models::{AppError, AppSettings, Bootstrap, HistoryItem, MediaItem, MediaRef, Page, Profile},
    provider::ProviderSession,
    store::Store,
};
use std::{
    path::PathBuf,
    sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

struct ConnectionState {
    restored: bool,
    session: Option<ProviderSession>,
    status: CredentialStatus,
    warning: Option<String>,
}

pub struct AppState {
    path: PathBuf,
    store: Mutex<Option<Store>>,
    connection: Mutex<ConnectionState>,
    secrets: Box<dyn CredentialStore>,
    pub change_gate: tokio::sync::Mutex<()>,
    pub refreshing: AtomicBool,
}

fn unavailable() -> AppError {
    AppError::storage("Local state is unavailable. Please retry.", true)
}

impl AppState {
    pub fn new(path: PathBuf, secrets: Box<dyn CredentialStore>) -> Self {
        Self {
            path,
            store: Mutex::new(None),
            connection: Mutex::new(ConnectionState {
                restored: false,
                session: None,
                status: CredentialStatus::None,
                warning: None,
            }),
            secrets,
            change_gate: tokio::sync::Mutex::new(()),
            refreshing: AtomicBool::new(false),
        }
    }

    pub(crate) fn with_store<T>(
        &self,
        action: impl FnOnce(&mut Store) -> Result<T, AppError>,
    ) -> Result<T, AppError> {
        let mut guard = self.store.lock().map_err(|_| unavailable())?;
        if guard.is_none() {
            if let Some(parent) = self.path.parent() {
                std::fs::create_dir_all(parent).map_err(|_| unavailable())?;
            }
            *guard = Some(Store::open(&self.path)?);
        }
        action(guard.as_mut().ok_or_else(unavailable)?)
    }

    pub fn active_session(&self) -> Result<Option<ProviderSession>, AppError> {
        let conn = self.connection.lock().map_err(|_| unavailable())?;
        Ok(conn.session.clone())
    }

    pub fn require_session(&self) -> Result<(ProviderSession, String), AppError> {
        let session = self
            .active_session()?
            .ok_or_else(|| AppError::unauthenticated("Connect a provider to continue."))?;
        let provider_id = self
            .with_store(|s| s.active_provider_id())?
            .ok_or_else(|| AppError::unauthenticated("No active provider configured."))?;
        Ok((session, provider_id))
    }

    pub fn active_scope(&self) -> Result<(String, String), AppError> {
        self.with_store(|store| {
            let provider = store
                .active_provider_id()?
                .ok_or_else(|| AppError::unauthenticated("No provider configured yet."))?;
            let profile = store
                .active_profile()?
                .ok_or_else(|| AppError::validation("No active profile selected."))?;
            Ok((provider, profile))
        })
    }

    pub fn bootstrap(&self) -> Result<Bootstrap, AppError> {
        self.with_store(|store| {
            let mut connection = self.connection.lock().map_err(|_| unavailable())?;
            if !connection.restored {
                if let Some(key) = store.preference("remembered_account")? {
                    match self.secrets.load() {
                        Ok(Some(saved)) => {
                            if let Ok(session) = ProviderSession::new(
                                &saved.base_url,
                                &saved.username,
                                &saved.password,
                            ) {
                                if session.account_key() == key {
                                    connection.session = Some(session);
                                    connection.status = CredentialStatus::Remembered;
                                }
                            }
                        }
                        Ok(None) => {}
                        Err(_) => {
                            connection.warning = Some(
                                "Secure credential storage is unavailable. Reconnect for this session."
                                    .into(),
                            );
                        }
                    }
                }
                connection.restored = true;
            }
            let mut value = Bootstrap::disconnected();
            value.connected = connection.session.is_some();
            value.credential_status = connection.status;
            value.credential_warning = connection.warning.clone();
            value.active_provider_id = store.active_provider_id()?;
            value.provider_url = if let Some(id) = &value.active_provider_id {
                store
                    .connection
                    .query_row("SELECT base_url FROM providers WHERE id=?1", [id], |r| {
                        r.get(0)
                    })
                    .ok()
            } else {
                None
            };
            value.profiles = store.profiles()?;
            value.active_profile_id = store.active_profile()?;
            value.cache_status = store.cache_status(
                value.active_provider_id.as_deref(),
                self.refreshing.load(Ordering::Relaxed),
            )?;
            value.settings = store.settings()?;
            Ok(value)
        })
    }

    pub async fn connect(
        &self,
        base: &str,
        username: &str,
        password: &str,
        remember: bool,
    ) -> Result<Bootstrap, AppError> {
        let _gate = self.change_gate.lock().await;
        let session = ProviderSession::new(base, username, password)?;
        session.authenticate().await?;
        let key = session.account_key();
        self.with_store(|store| {
            store.connect_provider(&key, session.base.as_str())?;
            // Clear restoration marker before changing OS secrets so a failed save can
            // never accidentally restore a prior login on the next launch.
            store.clear_preference("remembered_account")?;
            let status = save_login(self.secrets.as_ref(), &session.credentials, remember);
            if status == CredentialStatus::Remembered {
                store.set_preference("remembered_account", &key)?;
            }
            let warning = if remember && status == CredentialStatus::Session {
                Some(
                    "Secure credential storage is unavailable. This login lasts until Marquee closes."
                        .into(),
                )
            } else {
                None
            };
            *self.connection.lock().map_err(|_| unavailable())? = ConnectionState {
                restored: true,
                session: Some(session),
                status,
                warning,
            };
            Ok(())
        })?;
        self.bootstrap()
    }

    pub fn disconnect(&self) -> Result<(), AppError> {
        self.with_store(|store| {
            store.clear_preference("remembered_account")?;
            *self.connection.lock().map_err(|_| unavailable())? = ConnectionState {
                restored: true,
                session: None,
                status: CredentialStatus::None,
                warning: None,
            };
            if self.secrets.delete().is_err() {
                return Err(AppError::storage(
                    "Disconnected. Secure storage could not delete the saved entry; unlock it and retry Disconnect.",
                    true,
                ));
            }
            Ok(())
        })
    }

    pub fn profiles_list(&self) -> Result<Vec<Profile>, AppError> {
        self.with_store(|s| s.profiles())
    }

    pub fn profile_create(&self, name: &str, avatar: &str) -> Result<Profile, AppError> {
        self.with_store(|s| s.create_profile(name, avatar))
    }

    pub fn profile_update(&self, id: &str, name: &str, avatar: &str) -> Result<Profile, AppError> {
        self.with_store(|s| s.rename_profile(id, name, avatar))
    }

    pub fn profile_remove(&self, id: &str) -> Result<String, AppError> {
        self.with_store(|s| s.delete_profile(id))
    }

    pub fn profile_activate(&self, id: &str) -> Result<(), AppError> {
        self.with_store(|s| s.activate_profile(id))
    }

    pub fn favorite_set(&self, media_ref: &MediaRef, saved: bool) -> Result<(), AppError> {
        let (provider, profile) = self.active_scope()?;
        self.with_store(|s| {
            s.set_favorite(
                &provider,
                &profile,
                media_ref.kind.as_str(),
                &media_ref.id,
                saved,
            )
        })
    }

    pub fn favorites_list(&self, offset: usize, limit: usize) -> Result<Page<MediaItem>, AppError> {
        let (provider, profile) = self.active_scope()?;
        self.with_store(|s| s.favorites_page(&provider, &profile, offset, limit))
    }

    pub fn history_list(&self) -> Result<Vec<HistoryItem>, AppError> {
        let (provider, profile) = self.active_scope()?;
        self.with_store(|s| s.history_items(&provider, &profile))
    }

    pub fn settings_update(
        &self,
        autoplay_next: Option<bool>,
        subtitles_default: Option<bool>,
        preferred_audio: Option<&str>,
        compact_library: Option<bool>,
    ) -> Result<AppSettings, AppError> {
        self.with_store(|s| {
            s.update_settings(
                autoplay_next,
                subtitles_default,
                preferred_audio,
                compact_library,
            )
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::credentials::CredentialError;
    use crate::provider::Credentials;
    use std::sync::{Arc, Mutex};

    #[derive(Clone, Default)]
    struct MemorySecrets(Arc<Mutex<Option<Credentials>>>);

    impl CredentialStore for MemorySecrets {
        fn load(&self) -> Result<Option<Credentials>, CredentialError> {
            Ok(self.0.lock().unwrap().clone())
        }
        fn save(&self, value: &Credentials) -> Result<(), CredentialError> {
            *self.0.lock().unwrap() = Some(value.clone());
            Ok(())
        }
        fn delete(&self) -> Result<(), CredentialError> {
            *self.0.lock().unwrap() = None;
            Ok(())
        }
    }

    struct FailingSecrets;
    impl CredentialStore for FailingSecrets {
        fn load(&self) -> Result<Option<Credentials>, CredentialError> {
            Err(CredentialError)
        }
        fn save(&self, _: &Credentials) -> Result<(), CredentialError> {
            Err(CredentialError)
        }
        fn delete(&self) -> Result<(), CredentialError> {
            Err(CredentialError)
        }
    }

    #[test]
    fn failed_auth_preserves_previous_connection_logout_clears_secret_and_reopen_restores() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/base/", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            for auth in [1, 0] {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut buffer = [0; 8192];
                let n = stream.read(&mut buffer).unwrap();
                assert!(
                    String::from_utf8_lossy(&buffer[..n]).starts_with("GET /base/player_api.php?")
                );
                let body = format!(r#"{{"user_info":{{"auth":{auth},"status":"Active"}}}}"#);
                write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                )
                .unwrap();
            }
        });
        let path = std::env::temp_dir().join(format!(
            "marquee-state-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let secrets = MemorySecrets::default();
        let state = AppState::new(path.clone(), Box::new(secrets.clone()));
        tauri::async_runtime::block_on(async {
            let first = state.connect(&base, "user", "good", true).await.unwrap();
            assert!(first.connected);
            assert_eq!(first.credential_status, CredentialStatus::Remembered);
            assert!(state.connect(&base, "user", "bad", true).await.is_err());
            assert_eq!(secrets.load().unwrap().unwrap().password, "good");
            assert_eq!(
                state.bootstrap().unwrap().active_provider_id,
                first.active_provider_id
            );
        });
        server.join().unwrap();
        drop(state);
        let reopened = AppState::new(path.clone(), Box::new(secrets.clone()));
        assert!(reopened.bootstrap().unwrap().connected);
        reopened.disconnect().unwrap();
        assert!(secrets.load().unwrap().is_none());
        assert!(!reopened.bootstrap().unwrap().connected);
        drop(reopened);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn locked_secret_store_with_remember_returns_explicit_session_only_and_warning() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buffer = [0; 4096];
            let _ = stream.read(&mut buffer).unwrap();
            let body = r#"{"user_info":{"auth":1,"status":"Active"}}"#;
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            )
            .unwrap();
        });
        let path = std::env::temp_dir().join(format!(
            "marquee-locked-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let state = AppState::new(path.clone(), Box::new(FailingSecrets));
        tauri::async_runtime::block_on(async {
            let boot = state.connect(&base, "user", "pass", true).await.unwrap();
            assert!(boot.connected);
            assert_eq!(boot.credential_status, CredentialStatus::Session);
            assert!(boot.credential_warning.is_some());
        });
        server.join().unwrap();
        drop(state);
        let reopened = AppState::new(path.clone(), Box::new(FailingSecrets));
        let boot = reopened.bootstrap().unwrap();
        assert!(!boot.connected);
        assert_eq!(boot.credential_status, CredentialStatus::None);
        drop(reopened);
        std::fs::remove_file(path).unwrap();
    }
}
