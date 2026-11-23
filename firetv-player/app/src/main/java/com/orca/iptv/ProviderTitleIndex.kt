package com.orca.iptv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import java.io.Closeable
import java.io.Reader
import java.text.Normalizer
import java.util.Locale

/** Streaming catalogue import and indexed lookups; no full JSON catalogue in RAM. */
class ProviderTitleIndex(private val context: Context, name: String = "provider-title-index.db") : Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE titles(kind TEXT,id TEXT,name TEXT,normalized TEXT,year INTEGER,category TEXT,icon TEXT,ext TEXT,PRIMARY KEY(kind,id))")
            db.execSQL("CREATE INDEX title_match ON titles(kind,normalized,year)")
            db.execSQL("CREATE TABLE imported(kind TEXT PRIMARY KEY,stamp TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val db get() = helper.writableDatabase
    fun ensure() {
        for ((kind, asset) in listOf("movie" to "vod_catalog.json", "tv" to "series_catalog.json")) {
            val cached = CatalogSync.cacheFile(context, asset)
            val stamp = "${context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime}:${cached.lastModified()}:${cached.length()}"
            val previous = db.rawQuery("SELECT stamp FROM imported WHERE kind=?", arrayOf(kind)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (stamp == previous) continue
            val reader = if (cached.exists() && cached.length() > 0) cached.reader() else context.assets.open(asset).reader()
            reader.use { importCatalog(kind, it) }
            db.insertWithOnConflict("imported", null, ContentValues().apply { put("kind", kind); put("stamp", stamp) }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
    fun importCatalog(kind: String, source: Reader) {
        db.beginTransaction()
        try {
            db.delete("titles", "kind=?", arrayOf(kind))
            val insert = db.compileStatement("INSERT OR REPLACE INTO titles VALUES(?,?,?,?,?,?,?,?)")
            JsonReader(source).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "items") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginArray(); val values = ArrayList<String>(5)
                        while (reader.hasNext()) { if (values.size < 5) values.add(reader.nextString()) else reader.skipValue() }
                        reader.endArray()
                        if (values.size < 3) continue
                        val raw = values[1]; val year = YEAR.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        listOf(kind, values[0], raw, normalize(raw), year.toString(), values[2], values.getOrElse(3) { "" }, values.getOrElse(4) { "mp4" }).forEachIndexed { i, v -> insert.bindString(i + 1, v) }
                        insert.executeInsert(); insert.clearBindings()
                    }
                    reader.endArray()
                }
                reader.endObject()
            }
            insert.close(); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun match(kind: String, title: String, year: Int): Catalog.Item? {
        val rows = ArrayList<Pair<Int, Catalog.Item>>()
        db.rawQuery("SELECT id,name,category,icon,ext,year FROM titles WHERE kind=? AND normalized=? AND (?=0 OR year=0 OR year=?) ORDER BY CASE WHEN name LIKE 'EN|%' THEN 0 WHEN name LIKE '%|%' THEN 2 ELSE 1 END LIMIT 20",
            arrayOf(kind, normalize(title), year.toString(), year.toString())).use { c ->
            while (c.moveToNext()) rows.add(c.getInt(5) to Catalog.Item(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
        }
        if (year == 0 && rows.map { it.first }.filter { it > 0 }.distinct().size > 1) return null
        return rows.firstOrNull { it.first == year }?.second ?: rows.firstOrNull()?.second
    }
    override fun close() = helper.close()
    companion object {
        private val YEAR = Regex("(?: - |\\()(\\d{4})\\)?$")
        fun yearOf(title: String): Int = YEAR.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        fun normalize(title: String): String = Normalizer.normalize(Catalog.displayName(title), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"), "").replace(Regex("\\s*\\(\\d{4}\\)$"), "")
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
