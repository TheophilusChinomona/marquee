package com.orca.iptv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

/** Durable learning evidence; independent of the disposable Continue Watching list. */
class WatchLedger(private val context: Context, name: String = "watch-ledger.db") : Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
            db.execSQL("CREATE TABLE watch(profile TEXT,session TEXT,source TEXT,title TEXT,kind TEXT,year INTEGER,played INTEGER,duration INTEGER,explicit INTEGER,approximate INTEGER,updated INTEGER,revision INTEGER,dirty INTEGER,PRIMARY KEY(profile,session))")
            db.execSQL("CREATE INDEX watch_pending ON watch(profile,dirty,updated)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }.apply { setWriteAheadLoggingEnabled(true) }
    private val db get() = helper.writableDatabase

    @Synchronized fun get(key: String): String? = db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun put(key: String, value: String) { db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun deviceId(): String = get("device") ?: UUID.randomUUID().toString().also { put("device", it) }
    fun serverProfile(profile: String): String? = get("profile:$profile")
    fun epoch(profile: String): Int = get("epoch:$profile")?.toIntOrNull() ?: 0

    @Synchronized fun map(profile: String, server: String, epoch: Int) {
        db.beginTransaction()
        try {
            if (serverProfile(profile) != null && serverProfile(profile) != server) {
                db.execSQL("UPDATE watch SET dirty=1 WHERE profile=?", arrayOf(profile))
                db.delete("meta", "k=?", arrayOf("page:$profile"))
            }
            if (serverProfile(profile) != null && epoch(profile) != epoch) {
                db.delete("watch", "profile=?", arrayOf(profile))
                db.delete("meta", "k=?", arrayOf("page:$profile"))
                // Never re-import old resume entries after the user resets taste.
                put("migrated:$profile", "1")
            }
            put("profile:$profile", server); put("epoch:$profile", epoch.toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun record(profile: String, session: String, title: String, source: String, kind: String,
                             playedMs: Long, durationMs: Long, explicit: Boolean = false, year: Int = 0, approximate: Boolean = false,
                             expectedEpoch: Int = epoch(profile)) {
        if (title.isBlank() || source.isBlank() || kind !in listOf("tv", "movie")) return
        db.beginTransaction()
        try {
        if (expectedEpoch != epoch(profile)) return
        var revision = 1L; var played = playedMs.coerceAtLeast(0)
        db.rawQuery("SELECT revision,played FROM watch WHERE profile=? AND session=?", arrayOf(profile, session)).use {
            if (it.moveToFirst()) { revision = it.getLong(0) + 1; played = maxOf(played, it.getLong(1)) }
        }
        db.insertWithOnConflict("watch", null, ContentValues().apply {
            put("profile", profile); put("session", session); put("title", Catalog.displayName(title)); put("source", source)
            put("kind", kind); put("year", year.takeIf { it > 0 } ?: ProviderTitleIndex.yearOf(title)); put("played", played.coerceAtMost(7 * 86400000L)); put("duration", durationMs.coerceIn(0, 7 * 86400000L))
            put("explicit", if (explicit) 1 else 0); put("approximate", if (approximate) 1 else 0)
            put("updated", System.currentTimeMillis()); put("revision", revision); put("dirty", 1)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun seen(profile: String, kind: String, title: String, sourceId: String = ""): Boolean = db.rawQuery(
        "SELECT 1 FROM watch WHERE profile=? AND kind=? AND (played>=300000 OR explicit=1) AND (source=? OR lower(title)=lower(?)) LIMIT 1",
        arrayOf(profile, kind, sourceId, Catalog.displayName(title))).use { it.moveToFirst() }

    @Synchronized fun pending(profile: String): JSONArray {
        val out = JSONArray()
        db.rawQuery("SELECT session,source,title,kind,year,played,duration,explicit,approximate,updated,revision FROM watch WHERE profile=? AND dirty=1 ORDER BY updated,session LIMIT 50", arrayOf(profile)).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put("sessionId", c.getString(0)).put("sourceId", c.getString(1))
                .put("title", c.getString(2)).put("kind", c.getString(3)).put("year", c.getInt(4))
                .put("playedMs", c.getLong(5)).put("durationMs", c.getLong(6)).put("explicitWatched", c.getInt(7) == 1)
                .put("approximate", c.getInt(8) == 1).put("updatedAt", c.getLong(9)).put("revision", c.getLong(10))
                .put("completed", c.getInt(7) == 1 || (c.getInt(8) == 0 && c.getLong(6) > 0 && c.getLong(5).toDouble() / c.getLong(6) >= .8)))
        }
        return out
    }
    @Synchronized fun acknowledge(profile: String, session: String, revision: Long) {
        db.execSQL("UPDATE watch SET dirty=0 WHERE profile=? AND session=? AND revision<=?", arrayOf(profile, session, revision))
    }
    @Synchronized fun migrate(profile: String) {
        if (get("migrated:$profile") != null) return
        db.beginTransaction()
        try {
            HistoryStore.all(context, profile).filter { it.mode != MODE_LIVE }.forEach { h ->
                record(profile, "import:${h.mode}:${h.id}", h.name, h.seriesId.ifBlank { h.id },
                    if (h.mode == MODE_SERIES) "tv" else "movie", minOf(h.positionMs, 1200000), h.durationMs, approximate = true)
            }
            put("migrated:$profile", "1"); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun clearProfile(profile: String) {
        db.delete("watch", "profile=?", arrayOf(profile))
        db.delete("meta", "k IN (?,?,?)", arrayOf("profile:$profile", "epoch:$profile", "page:$profile"))
    }
    override fun close() = helper.close()
}
