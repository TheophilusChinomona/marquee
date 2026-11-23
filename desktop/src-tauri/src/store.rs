use crate::models::{
    AppError, AppSettings, CacheStatus, Category, HistoryItem, MediaItem, MediaKind, MediaRef,
    Page, Profile,
};
use crate::provider::{ParsedCatalogItem, ParsedCategory, normalize_search_name};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

pub type Result<T> = std::result::Result<T, AppError>;

pub(crate) fn storage_error(_: rusqlite::Error) -> AppError {
    AppError::storage(
        "Unable to read or save local data. Check available disk space and try again.",
        true,
    )
}

fn invalid(message: &str) -> AppError {
    AppError::validation(message)
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub struct Store {
    pub(crate) connection: Connection,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryEntry {
    pub kind: String,
    pub media_id: String,
    pub episode_id: String,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub updated_at: i64,
    pub completed: bool,
}

pub const STALE_AFTER_MS: i64 = 24 * 60 * 60 * 1000;

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        Self::initialize(Connection::open(path).map_err(storage_error)?)
    }

    #[cfg(test)]
    pub fn open_memory() -> Result<Self> {
        Self::initialize(Connection::open_in_memory().map_err(storage_error)?)
    }

    fn initialize(mut connection: Connection) -> Result<Self> {
        connection
            .busy_timeout(std::time::Duration::from_secs(5))
            .map_err(storage_error)?;
        connection
            .execute_batch("PRAGMA foreign_keys=ON;")
            .map_err(storage_error)?;
        let version: i32 = connection
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .map_err(storage_error)?;
        if version > 1 {
            return Err(AppError::storage(
                "Local data was created by a newer Marquee version.",
                false,
            ));
        }
        if version == 0 {
            let tx = connection.transaction().map_err(storage_error)?;
            tx.execute_batch(include_str!("../migrations/001_initial.sql"))
                .map_err(storage_error)?;
            tx.commit().map_err(storage_error)?;
        }
        Ok(Self { connection })
    }

    pub fn preference(&self, key: &str) -> Result<Option<String>> {
        self.connection
            .query_row("SELECT value FROM preferences WHERE key=?1", [key], |r| {
                r.get(0)
            })
            .optional()
            .map_err(storage_error)
    }

    pub fn set_preference(&self, key: &str, value: &str) -> Result<()> {
        self.connection
            .execute(
                "INSERT INTO preferences(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                params![key, value],
            )
            .map_err(storage_error)?;
        Ok(())
    }

    pub fn clear_preference(&self, key: &str) -> Result<()> {
        self.connection
            .execute("DELETE FROM preferences WHERE key=?1", [key])
            .map_err(storage_error)?;
        Ok(())
    }

    pub fn settings(&self) -> Result<AppSettings> {
        let mut settings = AppSettings::default();
        if let Some(v) = self.preference("setting_autoplay_next")? {
            settings.autoplay_next = v == "true";
        }
        if let Some(v) = self.preference("setting_subtitles_default")? {
            settings.subtitles_default = v == "true";
        }
        if let Some(v) = self.preference("setting_preferred_audio")?
            && !v.trim().is_empty()
        {
            settings.preferred_audio = v;
        }
        if let Some(v) = self.preference("setting_compact_library")? {
            settings.compact_library = v == "true";
        }
        Ok(settings)
    }

    pub fn update_settings(
        &self,
        autoplay_next: Option<bool>,
        subtitles_default: Option<bool>,
        preferred_audio: Option<&str>,
        compact_library: Option<bool>,
    ) -> Result<AppSettings> {
        if let Some(v) = autoplay_next {
            self.set_preference("setting_autoplay_next", if v { "true" } else { "false" })?;
        }
        if let Some(v) = subtitles_default {
            self.set_preference(
                "setting_subtitles_default",
                if v { "true" } else { "false" },
            )?;
        }
        if let Some(v) = preferred_audio {
            let trimmed = v.trim();
            if trimmed.is_empty() || trimmed.len() > 64 {
                return Err(invalid("Invalid preferred audio language."));
            }
            self.set_preference("setting_preferred_audio", trimmed)?;
        }
        if let Some(v) = compact_library {
            self.set_preference("setting_compact_library", if v { "true" } else { "false" })?;
        }
        self.settings()
    }

    pub fn active_provider_id(&self) -> Result<Option<String>> {
        self.preference("active_provider")
    }

    pub fn provider_generation(&self, provider_id: &str) -> Result<(Option<String>, Option<i64>)> {
        self.connection
            .query_row(
                "SELECT active_generation, refreshed_at FROM providers WHERE id=?1",
                [provider_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()
            .map_err(storage_error)
            .map(|opt| opt.unwrap_or((None, None)))
    }

    pub fn cache_status(&self, provider_id: Option<&str>, refreshing: bool) -> Result<CacheStatus> {
        let Some(provider_id) = provider_id else {
            return Ok(CacheStatus {
                refreshing,
                ..CacheStatus::default()
            });
        };
        let (generation_opt, refreshed_at) = self.provider_generation(provider_id)?;
        let Some(generation) = generation_opt else {
            return Ok(CacheStatus {
                has_catalogue: false,
                stale: true,
                refreshed_at_ms: refreshed_at,
                live_count: 0,
                movie_count: 0,
                series_count: 0,
                refreshing,
            });
        };

        let mut live_count = 0i64;
        let mut movie_count = 0i64;
        let mut series_count = 0i64;
        let mut stmt = self
            .connection
            .prepare(
                "SELECT kind, COUNT(*) FROM catalogue_items WHERE provider_id=?1 AND generation=?2 GROUP BY kind",
            )
            .map_err(storage_error)?;
        let rows = stmt
            .query_map(params![provider_id, generation], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
            })
            .map_err(storage_error)?;
        for row in rows {
            let (kind, count) = row.map_err(storage_error)?;
            match kind.as_str() {
                "live" => live_count = count,
                "movie" => movie_count = count,
                "series" => series_count = count,
                _ => {}
            }
        }
        let stale = refreshed_at
            .map(|ts| now_ms().saturating_sub(ts) > STALE_AFTER_MS)
            .unwrap_or(true);
        Ok(CacheStatus {
            has_catalogue: true,
            stale,
            refreshed_at_ms: refreshed_at,
            live_count,
            movie_count,
            series_count,
            refreshing,
        })
    }

    pub fn connect_provider(&mut self, account_key: &str, base_url: &str) -> Result<String> {
        let tx = self.connection.transaction().map_err(storage_error)?;
        let existing: Option<String> = tx
            .query_row(
                "SELECT id FROM providers WHERE account_key=?1",
                [account_key],
                |r| r.get(0),
            )
            .optional()
            .map_err(storage_error)?;
        let id = existing.unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
        tx.execute(
            "INSERT INTO providers(id,account_key,base_url) VALUES(?1,?2,?3) ON CONFLICT(account_key) DO UPDATE SET base_url=excluded.base_url",
            params![id, account_key, base_url],
        )
        .map_err(storage_error)?;
        tx.execute(
            "INSERT INTO preferences(key,value) VALUES('active_provider',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            [&id],
        )
        .map_err(storage_error)?;
        let count: i64 = tx
            .query_row("SELECT COUNT(*) FROM profiles", [], |r| r.get(0))
            .map_err(storage_error)?;
        if count == 0 {
            let profile_id = uuid::Uuid::new_v4().to_string();
            tx.execute(
                "INSERT INTO profiles VALUES(?1,'My profile','red')",
                [&profile_id],
            )
            .map_err(storage_error)?;
            tx.execute(
                "INSERT INTO preferences(key,value) VALUES('active_profile',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                [&profile_id],
            )
            .map_err(storage_error)?;
        }
        tx.commit().map_err(storage_error)?;
        Ok(id)
    }

    pub fn profiles(&self) -> Result<Vec<Profile>> {
        let mut statement = self
            .connection
            .prepare("SELECT id,name,avatar FROM profiles ORDER BY rowid")
            .map_err(storage_error)?;
        statement
            .query_map([], |r| {
                Ok(Profile {
                    id: r.get(0)?,
                    name: r.get(1)?,
                    avatar: r.get(2)?,
                })
            })
            .map_err(storage_error)?
            .collect::<std::result::Result<_, _>>()
            .map_err(storage_error)
    }

    fn valid_profile(name: &str, avatar: &str) -> Result<()> {
        if name.trim().is_empty() || name.chars().count() > 40 {
            return Err(invalid("Profile name must contain 1–40 characters."));
        }
        if !["red", "blue", "green", "purple", "orange"].contains(&avatar) {
            return Err(invalid("Choose a valid profile avatar."));
        }
        Ok(())
    }

    pub fn create_profile(&self, name: &str, avatar: &str) -> Result<Profile> {
        Self::valid_profile(name, avatar)?;
        let profile = Profile {
            id: uuid::Uuid::new_v4().to_string(),
            name: name.trim().into(),
            avatar: avatar.into(),
        };
        self.connection
            .execute(
                "INSERT INTO profiles VALUES(?1,?2,?3)",
                params![profile.id, profile.name, profile.avatar],
            )
            .map_err(storage_error)?;
        if self.active_profile()?.is_none() {
            self.set_preference("active_profile", &profile.id)?;
        }
        Ok(profile)
    }

    pub fn rename_profile(&self, id: &str, name: &str, avatar: &str) -> Result<Profile> {
        Self::valid_profile(name, avatar)?;
        let trimmed = name.trim();
        let changed = self
            .connection
            .execute(
                "UPDATE profiles SET name=?2,avatar=?3 WHERE id=?1",
                params![id, trimmed, avatar],
            )
            .map_err(storage_error)?;
        if changed == 0 {
            return Err(invalid("Profile no longer exists."));
        }
        Ok(Profile {
            id: id.into(),
            name: trimmed.into(),
            avatar: avatar.into(),
        })
    }

    pub fn active_profile(&self) -> Result<Option<String>> {
        self.preference("active_profile")
    }

    pub fn activate_profile(&self, id: &str) -> Result<()> {
        if !self.profiles()?.iter().any(|p| p.id == id) {
            return Err(invalid("Profile no longer exists."));
        }
        self.set_preference("active_profile", id)
    }

    pub fn delete_profile(&mut self, id: &str) -> Result<String> {
        let profiles = self.profiles()?;
        if !profiles.iter().any(|p| p.id == id) {
            return Err(invalid("Profile no longer exists."));
        }
        if profiles.len() < 2 {
            return Err(invalid("Keep at least one profile."));
        }
        let survivor = profiles.iter().find(|p| p.id != id).unwrap().id.clone();
        let tx = self.connection.transaction().map_err(storage_error)?;
        tx.execute(
            "UPDATE preferences SET value=?1 WHERE key='active_profile' AND value=?2",
            params![survivor, id],
        )
        .map_err(storage_error)?;
        tx.execute("DELETE FROM profiles WHERE id=?1", [id])
            .map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;
        let active = self.active_profile()?.unwrap_or(survivor);
        Ok(active)
    }

    pub fn set_favorite(
        &self,
        provider: &str,
        profile: &str,
        kind: &str,
        id: &str,
        saved: bool,
    ) -> Result<()> {
        if !["movie", "series", "live"].contains(&kind) || id.trim().is_empty() {
            return Err(invalid("Invalid media identity."));
        }
        if saved {
            self.connection
                .execute(
                    "INSERT OR IGNORE INTO favorites VALUES(?1,?2,?3,?4)",
                    params![provider, profile, kind, id],
                )
                .map_err(storage_error)?;
        } else {
            self.connection
                .execute(
                    "DELETE FROM favorites WHERE provider_id=?1 AND profile_id=?2 AND kind=?3 AND media_id=?4",
                    params![provider, profile, kind, id],
                )
                .map_err(storage_error)?;
        }
        Ok(())
    }

    #[allow(dead_code)]
    pub fn favorite_ids(&self, provider: &str, profile: &str, kind: &str) -> Result<Vec<String>> {
        let mut statement = self
            .connection
            .prepare(
                "SELECT media_id FROM favorites WHERE provider_id=?1 AND profile_id=?2 AND kind=?3 ORDER BY rowid DESC",
            )
            .map_err(storage_error)?;
        statement
            .query_map(params![provider, profile, kind], |r| r.get(0))
            .map_err(storage_error)?
            .collect::<std::result::Result<_, _>>()
            .map_err(storage_error)
    }

    pub fn is_favorite(&self, provider: &str, profile: &str, kind: &str, id: &str) -> Result<bool> {
        let exists: Option<i32> = self
            .connection
            .query_row(
                "SELECT 1 FROM favorites WHERE provider_id=?1 AND profile_id=?2 AND kind=?3 AND media_id=?4",
                params![provider, profile, kind, id],
                |r| r.get(0),
            )
            .optional()
            .map_err(storage_error)?;
        Ok(exists.is_some())
    }

    pub fn all_favorite_refs(&self, provider: &str, profile: &str) -> Result<Vec<MediaRef>> {
        let mut stmt = self
            .connection
            .prepare(
                "SELECT kind, media_id FROM favorites WHERE provider_id=?1 AND profile_id=?2 ORDER BY rowid DESC",
            )
            .map_err(storage_error)?;
        let rows = stmt
            .query_map(params![provider, profile], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
            })
            .map_err(storage_error)?;
        let mut out = Vec::new();
        for row in rows {
            let (kind, id) = row.map_err(storage_error)?;
            if let Ok(k) = MediaKind::parse(&kind) {
                out.push(MediaRef { kind: k, id });
            }
        }
        Ok(out)
    }

    pub fn favorites_page(
        &self,
        provider: &str,
        profile: &str,
        offset: usize,
        limit: usize,
    ) -> Result<Page<MediaItem>> {
        let limit = limit.clamp(1, 200);
        let (generation, _) = self.provider_generation(provider)?;
        let Some(generation) = generation else {
            return Ok(Page {
                items: vec![],
                total: 0,
                offset,
                limit,
            });
        };

        let total: i64 = self
            .connection
            .query_row(
                "SELECT COUNT(*) FROM favorites f
                 INNER JOIN catalogue_items c
                   ON c.provider_id = f.provider_id
                  AND c.generation = ?3
                  AND c.kind = f.kind
                  AND c.id = f.media_id
                 WHERE f.provider_id=?1 AND f.profile_id=?2",
                params![provider, profile, generation],
                |r| r.get(0),
            )
            .map_err(storage_error)?;

        let mut stmt = self
            .connection
            .prepare(
                "SELECT c.kind, c.id, c.name, c.category_id, cat.name, c.poster, c.backdrop, c.year, c.rating, c.extension, c.added
                 FROM favorites f
                 INNER JOIN catalogue_items c
                   ON c.provider_id = f.provider_id
                  AND c.generation = ?3
                  AND c.kind = f.kind
                  AND c.id = f.media_id
                 LEFT JOIN categories cat
                   ON cat.provider_id = c.provider_id
                  AND cat.generation = c.generation
                  AND cat.kind = c.kind
                  AND cat.id = c.category_id
                 WHERE f.provider_id=?1 AND f.profile_id=?2
                 ORDER BY f.rowid DESC
                 LIMIT ?4 OFFSET ?5",
            )
            .map_err(storage_error)?;
        let rows = stmt
            .query_map(
                params![provider, profile, generation, limit as i64, offset as i64],
                row_to_media_item,
            )
            .map_err(storage_error)?;
        let mut items = Vec::new();
        for row in rows {
            items.push(row.map_err(storage_error)?);
        }
        Ok(Page {
            items,
            total: total.max(0) as usize,
            offset,
            limit,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn save_history(
        &mut self,
        provider: &str,
        profile: &str,
        kind: &str,
        id: &str,
        episode: &str,
        position: i64,
        duration: i64,
        updated: i64,
    ) -> Result<()> {
        if id.trim().is_empty() || duration <= 0 || kind == "live" {
            return Ok(());
        }
        let position = position.clamp(0, duration);
        let completed = position as f64 / duration as f64 >= 0.95;
        let tx = self.connection.transaction().map_err(storage_error)?;
        tx.execute(
            "INSERT INTO history VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)
             ON CONFLICT(provider_id,profile_id,kind,media_id,episode_id)
             DO UPDATE SET position_ms=excluded.position_ms,duration_ms=excluded.duration_ms,updated_at=excluded.updated_at,completed=excluded.completed",
            params![provider, profile, kind, id, episode, position, duration, updated, completed],
        )
        .map_err(storage_error)?;
        tx.execute(
            "DELETE FROM history WHERE provider_id=?1 AND profile_id=?2 AND rowid NOT IN (
                SELECT rowid FROM history WHERE provider_id=?1 AND profile_id=?2 ORDER BY updated_at DESC, rowid DESC LIMIT 40
            )",
            params![provider, profile],
        )
        .map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;
        Ok(())
    }

    pub fn continue_history(&self, provider: &str, profile: &str) -> Result<Vec<HistoryEntry>> {
        let mut statement = self
            .connection
            .prepare(
                "SELECT kind,media_id,episode_id,position_ms,duration_ms,updated_at,completed
                 FROM history
                 WHERE provider_id=?1 AND profile_id=?2 AND completed=0
                 ORDER BY updated_at DESC, rowid DESC
                 LIMIT 40",
            )
            .map_err(storage_error)?;
        let raw: Vec<HistoryEntry> = statement
            .query_map(params![provider, profile], |r| {
                Ok(HistoryEntry {
                    kind: r.get(0)?,
                    media_id: r.get(1)?,
                    episode_id: r.get(2)?,
                    position_ms: r.get(3)?,
                    duration_ms: r.get(4)?,
                    updated_at: r.get(5)?,
                    completed: r.get::<_, i64>(6)? != 0,
                })
            })
            .map_err(storage_error)?
            .collect::<std::result::Result<_, _>>()
            .map_err(storage_error)?;

        // Deduplicate by (kind, media_id), keeping the most recent episode for a series.
        let mut seen = std::collections::HashSet::new();
        let mut out = Vec::with_capacity(raw.len());
        for entry in raw {
            if seen.insert((entry.kind.clone(), entry.media_id.clone())) {
                out.push(entry);
            }
        }
        Ok(out)
    }

    pub fn all_history(&self, provider: &str, profile: &str) -> Result<Vec<HistoryEntry>> {
        let mut statement = self
            .connection
            .prepare(
                "SELECT kind,media_id,episode_id,position_ms,duration_ms,updated_at,completed
                 FROM history
                 WHERE provider_id=?1 AND profile_id=?2
                 ORDER BY updated_at DESC, rowid DESC
                 LIMIT 40",
            )
            .map_err(storage_error)?;
        statement
            .query_map(params![provider, profile], |r| {
                Ok(HistoryEntry {
                    kind: r.get(0)?,
                    media_id: r.get(1)?,
                    episode_id: r.get(2)?,
                    position_ms: r.get(3)?,
                    duration_ms: r.get(4)?,
                    updated_at: r.get(5)?,
                    completed: r.get::<_, i64>(6)? != 0,
                })
            })
            .map_err(storage_error)?
            .collect::<std::result::Result<_, _>>()
            .map_err(storage_error)
    }

    pub fn history_items(&self, provider: &str, profile: &str) -> Result<Vec<HistoryItem>> {
        let entries = self.continue_history(provider, profile)?;
        let (generation, _) = self.provider_generation(provider)?;
        let mut out = Vec::with_capacity(entries.len());
        for entry in entries {
            let Ok(kind) = MediaKind::parse(&entry.kind) else {
                continue;
            };
            let item = if let Some(ref generation_id) = generation {
                self.get_catalogue_item_for_generation(
                    provider,
                    generation_id,
                    kind,
                    &entry.media_id,
                )?
            } else {
                None
            };
            let progress = if entry.duration_ms > 0 {
                Some((entry.position_ms as f64 / entry.duration_ms as f64).clamp(0.0, 1.0))
            } else {
                None
            };
            let enriched_item = item.map(|mut m| {
                m.progress = progress;
                m.episode_id = if entry.episode_id.is_empty() {
                    None
                } else {
                    Some(entry.episode_id.clone())
                };
                m.position_ms = Some(entry.position_ms);
                m.duration_ms = Some(entry.duration_ms);
                m
            });
            out.push(HistoryItem {
                media_ref: MediaRef {
                    kind,
                    id: entry.media_id,
                },
                episode_id: if entry.episode_id.is_empty() {
                    None
                } else {
                    Some(entry.episode_id)
                },
                position_ms: entry.position_ms,
                duration_ms: entry.duration_ms,
                updated_at_ms: entry.updated_at,
                completed: entry.completed,
                item: enriched_item,
            });
        }
        Ok(out)
    }

    pub fn get_catalogue_item_for_generation(
        &self,
        provider: &str,
        generation: &str,
        kind: MediaKind,
        id: &str,
    ) -> Result<Option<MediaItem>> {
        self.connection
            .query_row(
                "SELECT c.kind, c.id, c.name, c.category_id, cat.name, c.poster, c.backdrop, c.year, c.rating, c.extension, c.added
                 FROM catalogue_items c
                 LEFT JOIN categories cat
                   ON cat.provider_id = c.provider_id
                  AND cat.generation = c.generation
                  AND cat.kind = c.kind
                  AND cat.id = c.category_id
                 WHERE c.provider_id=?1 AND c.generation=?2 AND c.kind=?3 AND c.id=?4",
                params![provider, generation, kind.as_str(), id],
                row_to_media_item,
            )
            .optional()
            .map_err(storage_error)
    }

    pub fn get_item(
        &self,
        provider: &str,
        kind: MediaKind,
        id: &str,
    ) -> Result<Option<(MediaItem, Option<String>)>> {
        let (generation, _) = self.provider_generation(provider)?;
        let Some(generation) = generation else {
            return Ok(None);
        };
        self.connection
            .query_row(
                "SELECT c.kind, c.id, c.name, c.category_id, cat.name, c.poster, c.backdrop, c.year, c.rating, c.extension, c.added, c.details
                 FROM catalogue_items c
                 LEFT JOIN categories cat
                   ON cat.provider_id = c.provider_id
                  AND cat.generation = c.generation
                  AND cat.kind = c.kind
                  AND cat.id = c.category_id
                 WHERE c.provider_id=?1 AND c.generation=?2 AND c.kind=?3 AND c.id=?4",
                params![provider, generation, kind.as_str(), id],
                |r| Ok((row_to_media_item(r)?, r.get::<_, Option<String>>(11)?)),
            )
            .optional()
            .map_err(storage_error)
    }

    pub fn save_item_details(
        &self,
        provider: &str,
        kind: MediaKind,
        id: &str,
        details_json: &str,
    ) -> Result<()> {
        let (generation, _) = self.provider_generation(provider)?;
        let Some(generation) = generation else {
            return Ok(());
        };
        self.connection
            .execute(
                "UPDATE catalogue_items SET details=?5
                 WHERE provider_id=?1 AND generation=?2 AND kind=?3 AND id=?4",
                params![provider, generation, kind.as_str(), id, details_json],
            )
            .map_err(storage_error)?;
        Ok(())
    }

    pub fn begin_refresh_run(&self, refresh_id: &str, provider_id: &str) -> Result<()> {
        self.connection
            .execute(
                "INSERT INTO refresh_runs(id, provider_id, started_at, status) VALUES(?1,?2,?3,'running')",
                params![refresh_id, provider_id, now_ms()],
            )
            .map_err(storage_error)?;
        Ok(())
    }

    pub fn stage_categories(
        &mut self,
        provider_id: &str,
        generation: &str,
        kind: MediaKind,
        categories: &[ParsedCategory],
    ) -> Result<()> {
        let tx = self.connection.transaction().map_err(storage_error)?;
        {
            let mut stmt = tx
                .prepare_cached(
                    "INSERT OR REPLACE INTO categories(provider_id, generation, kind, id, name)
                     VALUES(?1, ?2, ?3, ?4, ?5)",
                )
                .map_err(storage_error)?;
            for cat in categories {
                stmt.execute(params![
                    provider_id,
                    generation,
                    kind.as_str(),
                    cat.id,
                    cat.name
                ])
                .map_err(storage_error)?;
            }
        }
        tx.commit().map_err(storage_error)?;
        Ok(())
    }

    pub fn stage_items(
        &mut self,
        provider_id: &str,
        generation: &str,
        items: &[ParsedCatalogItem],
    ) -> Result<()> {
        let tx = self.connection.transaction().map_err(storage_error)?;
        {
            let mut stmt = tx
                .prepare_cached(
                    "INSERT OR REPLACE INTO catalogue_items(
                        provider_id, generation, kind, id, name, normalized_name, category_id,
                        poster, backdrop, year, rating, extension, added
                     ) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
                )
                .map_err(storage_error)?;
            for item in items {
                stmt.execute(params![
                    provider_id,
                    generation,
                    item.kind.as_str(),
                    item.id,
                    item.name,
                    item.normalized_name,
                    item.category_id,
                    item.poster,
                    item.backdrop,
                    item.year,
                    item.rating,
                    item.extension,
                    item.added
                ])
                .map_err(storage_error)?;
            }
        }
        tx.commit().map_err(storage_error)?;
        Ok(())
    }

    /// Atomically publishes a staged generation and removes superseded generations.
    pub fn publish_generation(
        &mut self,
        provider_id: &str,
        generation: &str,
        refresh_id: &str,
    ) -> Result<()> {
        let tx = self.connection.transaction().map_err(storage_error)?;
        let active: Option<String> = tx
            .query_row(
                "SELECT value FROM preferences WHERE key='active_provider'",
                [],
                |r| r.get(0),
            )
            .optional()
            .map_err(storage_error)?;
        if active.as_deref() != Some(provider_id) {
            // Provider changed mid-refresh: discard staged rows without modifying active state.
            tx.execute(
                "DELETE FROM catalogue_items WHERE provider_id=?1 AND generation=?2",
                params![provider_id, generation],
            )
            .map_err(storage_error)?;
            tx.execute(
                "DELETE FROM categories WHERE provider_id=?1 AND generation=?2",
                params![provider_id, generation],
            )
            .map_err(storage_error)?;
            tx.execute(
                "UPDATE refresh_runs SET status='cancelled' WHERE id=?1",
                [refresh_id],
            )
            .map_err(storage_error)?;
            tx.commit().map_err(storage_error)?;
            return Err(AppError::provider(
                "Provider changed before refresh finished.",
                false,
            ));
        }

        // Preserve previously cached details JSON for matching items when re-refreshing.
        let prev_generation: Option<String> = tx
            .query_row(
                "SELECT active_generation FROM providers WHERE id=?1",
                [provider_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(storage_error)?
            .flatten();

        if let Some(prev) = prev_generation.as_deref()
            && prev != generation
        {
            tx.execute(
                "UPDATE catalogue_items
                     SET details = (
                         SELECT old.details FROM catalogue_items old
                         WHERE old.provider_id = catalogue_items.provider_id
                           AND old.generation = ?3
                           AND old.kind = catalogue_items.kind
                           AND old.id = catalogue_items.id
                           AND old.details IS NOT NULL
                     )
                     WHERE provider_id = ?1 AND generation = ?2 AND details IS NULL",
                params![provider_id, generation, prev],
            )
            .map_err(storage_error)?;
        }

        tx.execute(
            "UPDATE providers SET active_generation=?2, refreshed_at=?3 WHERE id=?1",
            params![provider_id, generation, now_ms()],
        )
        .map_err(storage_error)?;
        tx.execute(
            "DELETE FROM catalogue_items WHERE provider_id=?1 AND generation!=?2",
            params![provider_id, generation],
        )
        .map_err(storage_error)?;
        tx.execute(
            "DELETE FROM categories WHERE provider_id=?1 AND generation!=?2",
            params![provider_id, generation],
        )
        .map_err(storage_error)?;
        tx.execute(
            "UPDATE refresh_runs SET status='completed' WHERE id=?1",
            [refresh_id],
        )
        .map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;
        Ok(())
    }

    pub fn discard_generation(
        &mut self,
        provider_id: &str,
        generation: &str,
        refresh_id: &str,
        status: &str,
    ) -> Result<()> {
        let tx = self.connection.transaction().map_err(storage_error)?;
        tx.execute(
            "DELETE FROM catalogue_items WHERE provider_id=?1 AND generation=?2",
            params![provider_id, generation],
        )
        .map_err(storage_error)?;
        tx.execute(
            "DELETE FROM categories WHERE provider_id=?1 AND generation=?2",
            params![provider_id, generation],
        )
        .map_err(storage_error)?;
        tx.execute(
            "UPDATE refresh_runs SET status=?2 WHERE id=?1",
            params![refresh_id, status],
        )
        .map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;
        Ok(())
    }

    pub fn list_categories(&self, provider_id: &str, kind: MediaKind) -> Result<Vec<Category>> {
        let (generation, _) = self.provider_generation(provider_id)?;
        let Some(generation) = generation else {
            return Ok(vec![]);
        };
        let mut stmt = self
            .connection
            .prepare(
                "SELECT cat.id, cat.name, COUNT(c.id)
                 FROM categories cat
                 LEFT JOIN catalogue_items c
                   ON c.provider_id = cat.provider_id
                  AND c.generation = cat.generation
                  AND c.kind = cat.kind
                  AND c.category_id = cat.id
                 WHERE cat.provider_id=?1 AND cat.generation=?2 AND cat.kind=?3
                 GROUP BY cat.id, cat.name
                 ORDER BY cat.rowid ASC",
            )
            .map_err(storage_error)?;
        let rows = stmt
            .query_map(params![provider_id, generation, kind.as_str()], |r| {
                Ok(Category {
                    id: r.get(0)?,
                    name: r.get(1)?,
                    kind,
                    count: r.get(2)?,
                })
            })
            .map_err(storage_error)?;
        rows.collect::<std::result::Result<_, _>>()
            .map_err(storage_error)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn query_catalog(
        &self,
        provider_id: &str,
        kind: Option<MediaKind>,
        category_id: Option<&str>,
        query: Option<&str>,
        offset: usize,
        limit: usize,
        sort: Option<&str>,
    ) -> Result<Page<MediaItem>> {
        let limit = limit.clamp(1, 200);
        let (generation, _) = self.provider_generation(provider_id)?;
        let Some(generation) = generation else {
            return Ok(Page {
                items: vec![],
                total: 0,
                offset,
                limit,
            });
        };

        let mut where_clauses = vec![
            "c.provider_id = ?1".to_string(),
            "c.generation = ?2".to_string(),
        ];
        let mut bind_values: Vec<rusqlite::types::Value> = vec![
            rusqlite::types::Value::Text(provider_id.to_string()),
            rusqlite::types::Value::Text(generation),
        ];

        if let Some(k) = kind {
            bind_values.push(rusqlite::types::Value::Text(k.as_str().to_string()));
            where_clauses.push(format!("c.kind = ?{}", bind_values.len()));
        }
        if let Some(cat) = category_id.filter(|s| !s.trim().is_empty()) {
            bind_values.push(rusqlite::types::Value::Text(cat.to_string()));
            where_clauses.push(format!("c.category_id = ?{}", bind_values.len()));
        }
        if let Some(q) = query.map(normalize_search_name).filter(|s| !s.is_empty()) {
            // Escape LIKE wildcards so special characters search literally.
            let escaped = q
                .replace('\\', "\\\\")
                .replace('%', "\\%")
                .replace('_', "\\_");
            bind_values.push(rusqlite::types::Value::Text(format!("%{escaped}%")));
            where_clauses.push(format!(
                "c.normalized_name LIKE ?{} ESCAPE '\\'",
                bind_values.len()
            ));
        }

        let where_sql = where_clauses.join(" AND ");
        let count_sql = format!("SELECT COUNT(*) FROM catalogue_items c WHERE {where_sql}");
        let total: i64 = self
            .connection
            .query_row(
                &count_sql,
                rusqlite::params_from_iter(bind_values.iter()),
                |r| r.get(0),
            )
            .map_err(storage_error)?;

        let order_sql = match sort.unwrap_or("recommended") {
            "title" => "c.normalized_name ASC, c.id ASC",
            "year" => "COALESCE(c.year, '') DESC, c.rating DESC, c.rowid ASC",
            "added" => "COALESCE(c.added, 0) DESC, c.rowid DESC",
            _ => "COALESCE(c.rating, 0) DESC, COALESCE(c.added, 0) DESC, c.rowid ASC",
        };

        let mut query_binds = bind_values;
        query_binds.push(rusqlite::types::Value::Integer(limit as i64));
        let limit_idx = query_binds.len();
        query_binds.push(rusqlite::types::Value::Integer(offset as i64));
        let offset_idx = query_binds.len();

        let select_sql = format!(
            "SELECT c.kind, c.id, c.name, c.category_id, cat.name, c.poster, c.backdrop, c.year, c.rating, c.extension, c.added
             FROM catalogue_items c
             LEFT JOIN categories cat
               ON cat.provider_id = c.provider_id
              AND cat.generation = c.generation
              AND cat.kind = c.kind
              AND cat.id = c.category_id
             WHERE {where_sql}
             ORDER BY {order_sql}
             LIMIT ?{limit_idx} OFFSET ?{offset_idx}"
        );
        let mut stmt = self
            .connection
            .prepare(&select_sql)
            .map_err(storage_error)?;
        let rows = stmt
            .query_map(
                rusqlite::params_from_iter(query_binds.iter()),
                row_to_media_item,
            )
            .map_err(storage_error)?;
        let mut items = Vec::new();
        for row in rows {
            items.push(row.map_err(storage_error)?);
        }
        Ok(Page {
            items,
            total: total.max(0) as usize,
            offset,
            limit,
        })
    }

    pub fn related_items(
        &self,
        provider_id: &str,
        kind: MediaKind,
        category_id: &str,
        exclude_id: &str,
        limit: usize,
    ) -> Result<Vec<MediaItem>> {
        let (generation, _) = self.provider_generation(provider_id)?;
        let Some(generation) = generation else {
            return Ok(vec![]);
        };
        let mut stmt = self
            .connection
            .prepare(
                "SELECT c.kind, c.id, c.name, c.category_id, cat.name, c.poster, c.backdrop, c.year, c.rating, c.extension, c.added
                 FROM catalogue_items c
                 LEFT JOIN categories cat
                   ON cat.provider_id = c.provider_id
                  AND cat.generation = c.generation
                  AND cat.kind = c.kind
                  AND cat.id = c.category_id
                 WHERE c.provider_id=?1 AND c.generation=?2 AND c.kind=?3 AND c.category_id=?4 AND c.id!=?5
                 ORDER BY COALESCE(c.rating, 0) DESC, c.rowid ASC
                 LIMIT ?6",
            )
            .map_err(storage_error)?;
        let rows = stmt
            .query_map(
                params![
                    provider_id,
                    generation,
                    kind.as_str(),
                    category_id,
                    exclude_id,
                    limit as i64
                ],
                row_to_media_item,
            )
            .map_err(storage_error)?;
        rows.collect::<std::result::Result<_, _>>()
            .map_err(storage_error)
    }

    pub fn item_history_entries(
        &self,
        provider: &str,
        profile: &str,
        kind: MediaKind,
        media_id: &str,
    ) -> Result<Vec<HistoryEntry>> {
        let mut stmt = self
            .connection
            .prepare(
                "SELECT kind,media_id,episode_id,position_ms,duration_ms,updated_at,completed
                 FROM history
                 WHERE provider_id=?1 AND profile_id=?2 AND kind=?3 AND media_id=?4
                 ORDER BY updated_at DESC, rowid DESC",
            )
            .map_err(storage_error)?;
        stmt.query_map(params![provider, profile, kind.as_str(), media_id], |r| {
            Ok(HistoryEntry {
                kind: r.get(0)?,
                media_id: r.get(1)?,
                episode_id: r.get(2)?,
                position_ms: r.get(3)?,
                duration_ms: r.get(4)?,
                updated_at: r.get(5)?,
                completed: r.get::<_, i64>(6)? != 0,
            })
        })
        .map_err(storage_error)?
        .collect::<std::result::Result<_, _>>()
        .map_err(storage_error)
    }

    pub fn sample_items_by_kind(
        &self,
        provider_id: &str,
        kind: MediaKind,
        limit: usize,
        order_by: &str,
    ) -> Result<Vec<MediaItem>> {
        let (generation, _) = self.provider_generation(provider_id)?;
        let Some(generation) = generation else {
            return Ok(vec![]);
        };
        let sql = format!(
            "SELECT c.kind, c.id, c.name, c.category_id, cat.name, c.poster, c.backdrop, c.year, c.rating, c.extension, c.added
             FROM catalogue_items c
             LEFT JOIN categories cat
               ON cat.provider_id = c.provider_id
              AND cat.generation = c.generation
              AND cat.kind = c.kind
              AND cat.id = c.category_id
             WHERE c.provider_id=?1 AND c.generation=?2 AND c.kind=?3
             ORDER BY {order_by}
             LIMIT ?4"
        );
        let mut stmt = self.connection.prepare(&sql).map_err(storage_error)?;
        let rows = stmt
            .query_map(
                params![provider_id, generation, kind.as_str(), limit as i64],
                row_to_media_item,
            )
            .map_err(storage_error)?;
        rows.collect::<std::result::Result<_, _>>()
            .map_err(storage_error)
    }
}

pub(crate) fn row_to_media_item(r: &rusqlite::Row<'_>) -> rusqlite::Result<MediaItem> {
    let kind_str: String = r.get(0)?;
    let kind = MediaKind::parse(&kind_str).unwrap_or(MediaKind::Movie);
    Ok(MediaItem {
        media_ref: MediaRef {
            kind,
            id: r.get(1)?,
        },
        name: r.get(2)?,
        category_id: r.get(3)?,
        category_name: r.get(4)?,
        poster_url: r.get(5)?,
        backdrop_url: r.get(6)?,
        year: r.get(7)?,
        rating: r.get(8)?,
        container_extension: r.get(9)?,
        added: r.get(10)?,
        progress: None,
        episode_id: None,
        position_ms: None,
        duration_ms: None,
        rank: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn favorites_are_isolated_by_provider_profile_and_kind() {
        let mut db = Store::open_memory().unwrap();
        let a = db.connect_provider("account-a", "http://a.test/").unwrap();
        let b = db.connect_provider("account-b", "http://b.test/").unwrap();
        let p = db.profiles().unwrap()[0].id.clone();
        let q = db.create_profile("Alex", "blue").unwrap().id;
        db.set_favorite(&a, &p, "movie", "7", true).unwrap();
        assert_eq!(db.favorite_ids(&a, &p, "movie").unwrap(), vec!["7"]);
        assert!(db.favorite_ids(&b, &p, "movie").unwrap().is_empty());
        assert!(db.favorite_ids(&a, &q, "movie").unwrap().is_empty());
        assert!(db.favorite_ids(&a, &p, "series").unwrap().is_empty());
    }

    #[test]
    fn last_profile_is_preserved_and_active_deletion_selects_survivor() {
        let mut db = Store::open_memory().unwrap();
        db.connect_provider("a", "http://a.test/").unwrap();
        let first = db.profiles().unwrap()[0].id.clone();
        assert!(db.delete_profile(&first).is_err());
        let second = db.create_profile("Alex", "blue").unwrap();
        db.activate_profile(&second.id).unwrap();
        let survivor = db.delete_profile(&second.id).unwrap();
        assert_eq!(survivor, first);
        assert_eq!(
            db.active_profile().unwrap().as_deref(),
            Some(first.as_str())
        );
    }

    #[test]
    fn rename_and_reopen_preserve_identity_favorites_and_migration() {
        let path =
            std::env::temp_dir().join(format!("marquee-test-{}.sqlite", uuid::Uuid::new_v4()));
        let mut db = Store::open(&path).unwrap();
        let provider = db.connect_provider("a", "http://a.test/").unwrap();
        let p = db.profiles().unwrap()[0].id.clone();
        db.set_favorite(&provider, &p, "series", "9", true).unwrap();
        db.rename_profile(&p, "Renamed", "red").unwrap();
        db.update_settings(
            Some(false),
            Some(true),
            Some("Original language"),
            Some(true),
        )
        .unwrap();
        drop(db);
        let db = Store::open(&path).unwrap();
        assert_eq!(db.profiles().unwrap()[0].id, p);
        assert_eq!(db.profiles().unwrap()[0].name, "Renamed");
        assert_eq!(db.favorite_ids(&provider, &p, "series").unwrap(), vec!["9"]);
        let settings = db.settings().unwrap();
        assert!(!settings.autoplay_next);
        assert!(settings.subtitles_default);
        assert_eq!(settings.preferred_audio, "Original language");
        assert!(settings.compact_library);
        drop(db);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn history_threshold_episode_identity_and_recent_limit() {
        let mut db = Store::open_memory().unwrap();
        let a = db.connect_provider("a", "http://a.test/").unwrap();
        let b = db.connect_provider("b", "http://b.test/").unwrap();
        let p = db.profiles().unwrap()[0].id.clone();
        db.save_history(&a, &p, "series", "1", "ep-one", 940, 1000, 1)
            .unwrap();
        assert_eq!(db.continue_history(&a, &p).unwrap()[0].episode_id, "ep-one");
        assert!(db.continue_history(&b, &p).unwrap().is_empty());
        db.save_history(&a, &p, "series", "1", "ep-one", 950, 1000, 2)
            .unwrap();
        assert!(db.continue_history(&a, &p).unwrap().is_empty());
        // Completed entry is still retained in all_history for recommendation affinity
        assert_eq!(db.all_history(&a, &p).unwrap().len(), 1);
        assert!(db.all_history(&a, &p).unwrap()[0].completed);
        db.save_history(&a, &p, "movie", "unknown", "", 200, 0, 3)
            .unwrap();
        for i in 0..50 {
            db.save_history(&a, &p, "movie", &i.to_string(), "", 1, 100, i + 10)
                .unwrap();
        }
        assert_eq!(db.continue_history(&a, &p).unwrap().len(), 40);
        assert_eq!(db.continue_history(&a, &p).unwrap()[0].media_id, "49");
    }
}
