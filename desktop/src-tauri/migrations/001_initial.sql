CREATE TABLE providers (
  id TEXT PRIMARY KEY, account_key TEXT NOT NULL UNIQUE, base_url TEXT NOT NULL,
  active_generation TEXT, refreshed_at INTEGER
);
CREATE TABLE profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, avatar TEXT NOT NULL);
CREATE TABLE preferences (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE categories (
  provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  generation TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
  PRIMARY KEY(provider_id,generation,kind,id)
);
CREATE TABLE catalogue_items (
  provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  generation TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
  normalized_name TEXT NOT NULL, category_id TEXT NOT NULL, poster TEXT, backdrop TEXT,
  year TEXT, rating REAL, extension TEXT, added INTEGER, details TEXT,
  PRIMARY KEY(provider_id,generation,kind,id)
);
CREATE INDEX catalogue_search ON catalogue_items(provider_id,generation,kind,normalized_name);
CREATE INDEX catalogue_category ON catalogue_items(provider_id,generation,kind,category_id);
CREATE TABLE refresh_runs (
  id TEXT PRIMARY KEY, provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  started_at INTEGER NOT NULL, status TEXT NOT NULL
);
CREATE TABLE favorites (
  provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK(kind IN ('movie','series','live')), media_id TEXT NOT NULL,
  PRIMARY KEY(provider_id,profile_id,kind,media_id)
);
CREATE TABLE history (
  provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK(kind IN ('movie','series','live')), media_id TEXT NOT NULL,
  episode_id TEXT NOT NULL DEFAULT '', position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, completed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(provider_id,profile_id,kind,media_id,episode_id)
);
CREATE INDEX history_recent ON history(provider_id,profile_id,updated_at DESC);
PRAGMA user_version = 1;
