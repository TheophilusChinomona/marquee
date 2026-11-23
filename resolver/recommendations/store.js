'use strict';
const sqlite = require('node:sqlite');
const { mkdirSync } = require('node:fs');
const { dirname } = require('node:path');
const { randomUUID } = require('node:crypto');
const { normalizeTitle } = require('./rank');

class ApiError extends Error {
  constructor(status, message, extra = {}) { super(message); this.status = status; Object.assign(this, extra); }
}
function text(value, name, max = 200) {
  if (typeof value !== 'string' || !value.trim() || value.length > max) throw new ApiError(400, `invalid ${name}`);
  return value.trim();
}
function uuid(value, name) {
  text(value, name, 36);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) throw new ApiError(400, `invalid ${name}`);
  return value;
}
function integer(value, name, max = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < 0 || value > max) throw new ApiError(400, `invalid ${name}`);
  return value;
}

class Store {
  constructor(path) {
    if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
    this.db = new (sqlite.Database || sqlite.DatabaseSync)(path);
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=3000;
      CREATE TABLE IF NOT EXISTS profiles(id TEXT PRIMARY KEY, name TEXT NOT NULL, epoch INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY);
      CREATE TABLE IF NOT EXISTS mappings(device TEXT REFERENCES devices(id), local TEXT, profile TEXT REFERENCES profiles(id), PRIMARY KEY(device,local));
      CREATE TABLE IF NOT EXISTS sessions(profile TEXT REFERENCES profiles(id), session TEXT, revision INTEGER,
        kind TEXT, source TEXT, title TEXT, year INTEGER, tmdb INTEGER, played INTEGER, duration INTEGER,
        completed INTEGER, explicit INTEGER, updated INTEGER, approximate INTEGER NOT NULL DEFAULT 0,
        normalized TEXT NOT NULL DEFAULT '', PRIMARY KEY(profile,session));
      CREATE INDEX IF NOT EXISTS sessions_profile_recent ON sessions(profile,updated DESC);
      CREATE TABLE IF NOT EXISTS cache(key TEXT PRIMARY KEY, body TEXT, expires INTEGER, touched INTEGER);
      CREATE TABLE IF NOT EXISTS rankings(profile TEXT PRIMARY KEY REFERENCES profiles(id), epoch INTEGER,
        revision TEXT, generated INTEGER, body TEXT);
      PRAGMA user_version=1;`);
    if (!this.db.prepare('PRAGMA table_info(sessions)').all().some(c => c.name === 'approximate')) {
      this.db.exec('ALTER TABLE sessions ADD COLUMN approximate INTEGER NOT NULL DEFAULT 0');
    }
    if (!this.db.prepare('PRAGMA table_info(sessions)').all().some(c => c.name === 'normalized')) {
      this.db.exec("ALTER TABLE sessions ADD COLUMN normalized TEXT NOT NULL DEFAULT ''");
      for (const r of this.db.prepare('SELECT profile,session,title FROM sessions').iterate())
        this.db.prepare('UPDATE sessions SET normalized=? WHERE profile=? AND session=?').run(normalizeTitle(r.title),r.profile,r.session);
    }
    this.db.exec('CREATE INDEX IF NOT EXISTS sessions_canonical ON sessions(profile,kind,tmdb); CREATE INDEX IF NOT EXISTS sessions_title ON sessions(profile,kind,normalized)');
  }
  close() { if (this.db) { this.db.close(); this.db = null; } }
  tx(work) { this.db.exec('BEGIN IMMEDIATE'); try { const r = work(); this.db.exec('COMMIT'); return r; } catch (e) { this.db.exec('ROLLBACK'); throw e; } }
  profile(id) {
    const p = this.db.prepare('SELECT * FROM profiles WHERE id=?').get(text(id, 'profileId', 36));
    if (!p) throw new ApiError(404, 'profile not found');
    return { id: p.id, name: p.name, epoch: p.epoch };
  }
  profiles() { return this.db.prepare('SELECT id,name,epoch FROM profiles ORDER BY rowid LIMIT 100').all(); }
  register(deviceId, profiles) {
    uuid(deviceId, 'deviceId');
    if (!Array.isArray(profiles) || profiles.length > 20) throw new ApiError(400, 'invalid profiles');
    const clean = profiles.map(p => { if(!p||typeof p!=='object')throw new ApiError(400,'invalid profile'); return { localId: text(p.localId, 'localId', 100), name: text(p.name, 'name', 80) }; });
    if (new Set(clean.map(p => p.localId)).size !== clean.length) throw new ApiError(400, 'duplicate local profile');
    return this.tx(() => {
      this.db.prepare('INSERT OR IGNORE INTO devices VALUES(?)').run(deviceId);
      return clean.map(p => {
        let m = this.db.prepare('SELECT profile FROM mappings WHERE device=? AND local=?').get(deviceId, p.localId);
        if (!m) {
          if (this.profiles().length >= 100) throw new ApiError(409, 'household profile limit reached');
          const id = randomUUID();
          this.db.prepare('INSERT INTO profiles(id,name) VALUES(?,?)').run(id, p.name);
          this.db.prepare('INSERT INTO mappings VALUES(?,?,?)').run(deviceId, p.localId, id); m = { profile: id };
        }
        return { localId: p.localId, profileId: m.profile, epoch: this.profile(m.profile).epoch };
      });
    });
  }
  link(deviceId, localId, profileId) {
    uuid(deviceId, 'deviceId'); text(localId, 'localId', 100); const p = this.profile(profileId);
    if (!this.db.prepare('SELECT id FROM devices WHERE id=?').get(deviceId)) throw new ApiError(400, 'register device first');
    const old = this.db.prepare('SELECT profile FROM mappings WHERE device=? AND local=?').get(deviceId, localId);
    if (old && old.profile !== profileId) throw new ApiError(409, 'profile mapping already exists');
    this.db.prepare('INSERT OR IGNORE INTO mappings VALUES(?,?,?)').run(deviceId, localId, profileId);
    return { localId, profileId, epoch: p.epoch };
  }
  batch(deviceId, updates) {
    uuid(deviceId, 'deviceId');
    if (!Array.isArray(updates) || updates.length > 50) throw new ApiError(400, 'batch must contain at most 50 updates');
    const clean = updates.map(u => {
      if(!u||typeof u!=='object')throw new ApiError(400,'invalid watch update');
      const profileId = uuid(u.profileId, 'profileId');
      const sessionId = text(u.sessionId, 'sessionId', 100);
      const revision = integer(u.revision, 'revision'); const epoch = integer(u.epoch, 'epoch');
      const kind = u.kind;
      if (!['tv', 'movie'].includes(kind)) throw new ApiError(400, 'invalid kind');
      const source = text(u.sourceId, 'sourceId', 150);
      if (/https?:|[?\r\n]/i.test(source)) throw new ApiError(400, 'sourceId must not be a URL');
      const title = text(u.title, 'title', 300);
      const year = integer(u.year || 0, 'year', 2200);
      const fx = /^fx:(tv|movie):(\d+)$/.exec(source);
      if (fx && fx[1] !== kind) throw new ApiError(400, 'source kind does not match');
      const tmdb = integer(u.tmdbId || (fx ? Number(fx[2]) : 0), 'tmdbId');
      const played = integer(u.playedMs, 'playedMs', 7 * 86400000);
      const duration = integer(u.durationMs || 0, 'durationMs', 7 * 86400000);
      const updated = integer(u.updatedAt, 'updatedAt');
      if (updated > Date.now() + 86400000) throw new ApiError(400, 'updatedAt is in the future');
      const explicit = u.explicitWatched === true;
      // Completion is evidence-based: a seek to 95% must not imply a whole watch.
      const approximate = u.approximate === true;
      const completed = explicit || (!approximate && duration > 0 && played / duration >= .8);
      return { profileId, sessionId, revision, epoch, kind, source, title, year, tmdb, played, duration, updated, explicit, completed, approximate };
    });
    return this.tx(() => {
      for (const u of clean) {
        if (!this.db.prepare('SELECT 1 FROM mappings WHERE device=? AND profile=?').get(deviceId, u.profileId)) throw new ApiError(403, 'profile is not linked to this device');
        const p = this.profile(u.profileId);
        if (p.epoch !== u.epoch) throw new ApiError(409, 'watch epoch changed', { profileId: p.id, epoch: p.epoch });
      }
      const sql = this.db.prepare(`INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(profile,session) DO UPDATE SET revision=excluded.revision,
        played=MAX(sessions.played,excluded.played),duration=MAX(sessions.duration,excluded.duration),
        completed=MAX(sessions.completed,excluded.completed),explicit=MAX(sessions.explicit,excluded.explicit),updated=MAX(sessions.updated,excluded.updated)
        WHERE excluded.revision>sessions.revision`);
      return clean.map(u => {
        const prior = this.db.prepare('SELECT revision FROM sessions WHERE profile=? AND session=?').get(u.profileId,u.sessionId);
        sql.run(u.profileId,u.sessionId,u.revision,u.kind,u.source,u.title,u.year,u.tmdb,u.played,u.duration,+u.completed,+u.explicit,u.updated,+u.approximate,normalizeTitle(u.title));
        if (!prior || u.revision > prior.revision) this.db.prepare('UPDATE rankings SET generated=0 WHERE profile=?').run(u.profileId);
        return { profileId: u.profileId, sessionId: u.sessionId,
          revision: this.db.prepare('SELECT revision FROM sessions WHERE profile=? AND session=?').get(u.profileId,u.sessionId).revision };
      });
    });
  }
  seeds(profileId, limit = 12) {
    this.profile(profileId);
    // Group episodes by source series identity, not by playback-session identity.
    const grouped = `SELECT kind,source AS sourceId,title,year,tmdb AS tmdbId,
      MAX(played) AS playedMs,MAX(duration) AS durationMs,MAX(completed) AS completed,
      MAX(explicit) AS explicitWatched,MIN(approximate) AS approximate,MAX(updated) AS updatedAt FROM sessions
      WHERE profile=? AND (played>=300000 OR explicit=1)
      GROUP BY kind,source`;
    const recent = this.db.prepare(grouped+' ORDER BY updatedAt DESC LIMIT 12').all(profileId);
    const established = this.db.prepare(grouped+' ORDER BY completed DESC,explicitWatched DESC,playedMs DESC,updatedAt DESC LIMIT 12').all(profileId);
    const unique = new Map();
    for (const r of [...recent.slice(0,6),...established,...recent]) {
      const k=`${r.kind}:${r.sourceId}`;
      if (!unique.has(k) && unique.size < Math.min(12,limit)) unique.set(k,{...r,completed:!!r.completed,explicitWatched:!!r.explicitWatched});
    }
    return [...unique.values()];
  }
  seen(profileId, kind, tmdbId, title, year) {
    return !!this.db.prepare(`SELECT 1 FROM sessions WHERE profile=? AND kind=? AND (played>=300000 OR explicit=1)
      AND ((tmdb>0 AND tmdb=?) OR (normalized=? AND (year=0 OR ?=0 OR year=?))) LIMIT 1`)
      .get(profileId,kind,tmdbId,normalizeTitle(title),year,year);
  }
  reset(profileId) {
    return this.tx(() => {
      const p = this.profile(profileId);
      this.db.prepare('UPDATE profiles SET epoch=epoch+1 WHERE id=?').run(profileId);
      this.db.prepare('DELETE FROM sessions WHERE profile=?').run(profileId);
      this.db.prepare('DELETE FROM rankings WHERE profile=?').run(profileId);
      return p.epoch + 1;
    });
  }
  cached(key, withExpiry = false) {
    const r = this.db.prepare('SELECT body,expires FROM cache WHERE key=?').get(key);
    if (!r || r.expires <= Date.now()) return undefined;
    this.db.prepare('UPDATE cache SET touched=? WHERE key=?').run(Date.now(),key);
    const value=JSON.parse(r.body);
    return withExpiry ? { value, expiresAt:r.expires } : value;
  }
  cache(key,value,ttlMs) {
    this.db.prepare('INSERT OR REPLACE INTO cache VALUES(?,?,?,?)').run(key,JSON.stringify(value),Date.now()+ttlMs,Date.now());
    this.db.prepare('DELETE FROM cache WHERE expires<=?').run(Date.now());
    this.db.exec('DELETE FROM cache WHERE key IN (SELECT key FROM cache ORDER BY touched DESC LIMIT -1 OFFSET 5000)');
  }
  ranking(profileId) {
    const r = this.db.prepare('SELECT * FROM rankings WHERE profile=?').get(profileId);
    return r ? { epoch:r.epoch,revision:r.revision,generatedAt:r.generated,items:JSON.parse(r.body) } : null;
  }
  saveRanking(profileId,epoch,items) {
    if (this.profile(profileId).epoch !== epoch) return false;
    this.db.prepare('INSERT OR REPLACE INTO rankings VALUES(?,?,?,?,?)').run(profileId,epoch,randomUUID(),Date.now(),JSON.stringify(items.slice(0,240)));
    return true;
  }
}
module.exports = { Store, ApiError, text, uuid, integer };
