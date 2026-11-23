use crate::{
    models::{AppError, MediaDetails, MediaItem},
    online::ResolverSession,
    provider::normalize_search_name,
    store::{Store, storage_error},
};
use rusqlite::params;

impl Store {
    pub fn ensure_online_scope(&mut self, session: &ResolverSession) -> Result<(), AppError> {
        let scope = session.scope();
        let tx = self.connection.transaction().map_err(storage_error)?;
        tx.execute("INSERT INTO providers(id,account_key,base_url,active_generation) VALUES(?1,?1,?2,'online') ON CONFLICT(id) DO NOTHING", params![scope,session.base.as_str()]).map_err(storage_error)?;
        for kind in ["movie", "series"] {
            tx.execute("INSERT OR IGNORE INTO categories(provider_id,generation,kind,id,name) VALUES(?1,'online',?2,'online','Online Library')", params![scope,kind]).map_err(storage_error)?;
        }
        tx.commit().map_err(storage_error)?;
        if self.profiles()?.is_empty() {
            self.create_profile("My profile", "red")?;
        }
        self.set_preference("online_scope", &scope)?;
        self.set_preference("online_base_url", session.base.as_str())
    }
    pub fn cache_online_item(
        &self,
        scope: &str,
        item: &MediaItem,
        details: Option<&MediaDetails>,
    ) -> Result<(), AppError> {
        let json = details
            .map(serde_json::to_string)
            .transpose()
            .map_err(|_| AppError::storage("Unable to save online title metadata.", true))?;
        self.connection.execute("INSERT INTO catalogue_items(provider_id,generation,kind,id,name,normalized_name,category_id,poster,backdrop,year,details) VALUES(?1,'online',?2,?3,?4,?5,'online',?6,?7,?8,?9) ON CONFLICT(provider_id,generation,kind,id) DO UPDATE SET name=excluded.name,normalized_name=excluded.normalized_name,poster=COALESCE(excluded.poster,poster),backdrop=COALESCE(excluded.backdrop,backdrop),year=COALESCE(excluded.year,year),details=COALESCE(excluded.details,details)",params![scope,item.media_ref.kind.as_str(),item.media_ref.id,item.name,normalize_search_name(&item.name),item.poster_url,item.backdrop_url,item.year,json]).map_err(storage_error)?;
        // Keep saved/history titles; bound transient search metadata across sessions.
        self.connection.execute("DELETE FROM catalogue_items WHERE provider_id=?1 AND generation='online' AND rowid NOT IN (SELECT rowid FROM catalogue_items WHERE provider_id=?1 ORDER BY rowid DESC LIMIT 2000) AND NOT EXISTS (SELECT 1 FROM favorites f WHERE f.provider_id=?1 AND f.kind=catalogue_items.kind AND f.media_id=catalogue_items.id) AND NOT EXISTS (SELECT 1 FROM history h WHERE h.provider_id=?1 AND h.kind=catalogue_items.kind AND h.media_id=catalogue_items.id)", [scope]).map_err(storage_error)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use crate::{online::ResolverSession, store::Store};

    #[test]
    fn online_saved_titles_preserve_iptv_and_isolate_profiles_and_servers() {
        let mut store = Store::open_memory().unwrap();
        let iptv = store
            .connect_provider("iptv", "https://iptv.test/")
            .unwrap();
        let session = ResolverSession::new("https://resolver.test", "id", "secret").unwrap();
        store.ensure_online_scope(&session).unwrap();
        assert_eq!(
            store.active_provider_id().unwrap().as_deref(),
            Some(iptv.as_str())
        );
        let item = session
            .map_item(&serde_json::json!({"id":"/m12345","kind":"movie","name":"Saved movie"}))
            .unwrap();
        store
            .cache_online_item(&session.scope(), &item, None)
            .unwrap();
        let profile = store.active_profile().unwrap().unwrap();
        store
            .set_favorite(
                &session.scope(),
                &profile,
                "movie",
                &item.media_ref.id,
                true,
            )
            .unwrap();
        assert_eq!(
            store
                .favorites_page(&session.scope(), &profile, 0, 20)
                .unwrap()
                .items[0]
                .name,
            "Saved movie"
        );
        let other = store.create_profile("Other", "blue").unwrap();
        assert_eq!(
            store
                .favorites_page(&session.scope(), &other.id, 0, 20)
                .unwrap()
                .total,
            0
        );
        let other_server = ResolverSession::new("https://other.test", "id", "secret").unwrap();
        store.ensure_online_scope(&other_server).unwrap();
        assert_eq!(
            store
                .favorites_page(&other_server.scope(), &profile, 0, 20)
                .unwrap()
                .total,
            0
        );
    }
}
