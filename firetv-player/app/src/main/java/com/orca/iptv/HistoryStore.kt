package com.orca.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: String,
    val name: String,
    val icon: String,
    val ext: String,
    val categoryId: String,
    val mode: Int,
    val seriesId: String,
    val episode: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun toItem(): Catalog.Item = Catalog.Item(id, name, categoryId, icon, ext)
}

/**
 * Per-profile playback history. This is deliberately local: it powers
 * Continue Watching and the recommendation surfaces without sending anything
 * off the device.
 */
object HistoryStore {

    private const val FILE = "watch_history"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(profileId: String) = "history_$profileId"

    fun all(context: Context, profileId: String): List<HistoryEntry> {
        val raw = prefs(context).getString(key(profileId), null) ?: return emptyList()
        val list = ArrayList<HistoryEntry>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                list.add(
                    HistoryEntry(
                        id = id,
                        name = o.optString("name"),
                        icon = o.optString("icon"),
                        ext = o.optString("ext"),
                        categoryId = o.optString("category"),
                        mode = o.optInt("mode", MODE_VOD),
                        seriesId = o.optString("seriesId"),
                        episode = o.optString("episode"),
                        positionMs = o.optLong("position"),
                        durationMs = o.optLong("duration"),
                        updatedAt = o.optLong("updated")
                    )
                )
            }
        }
        return list.sortedByDescending { it.updatedAt }
    }

    fun record(
        context: Context,
        profileId: String,
        itemId: String,
        name: String,
        icon: String,
        ext: String,
        categoryId: String,
        mode: Int,
        seriesId: String = "",
        episode: String = "",
        positionMs: Long,
        durationMs: Long
    ) {
        if (itemId.isBlank() || durationMs <= 0L) return
        val progress = positionMs.toFloat() / durationMs
        val list = all(context, profileId).filterNot { it.id == itemId && it.mode == mode }.toMutableList()
        if (progress >= 0.95f) {
            save(context, profileId, list)
            return
        }
        list.add(
            0,
            HistoryEntry(
                id = itemId,
                name = name,
                icon = icon,
                ext = ext,
                categoryId = categoryId,
                mode = mode,
                seriesId = seriesId,
                episode = episode,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis()
            )
        )
        save(context, profileId, list.take(40))
    }

    fun clear(context: Context, profileId: String) {
        prefs(context).edit().remove(key(profileId)).apply()
    }

    private fun save(context: Context, profileId: String, list: List<HistoryEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("icon", it.icon)
                    .put("ext", it.ext)
                    .put("category", it.categoryId)
                    .put("mode", it.mode)
                    .put("seriesId", it.seriesId)
                    .put("episode", it.episode)
                    .put("position", it.positionMs)
                    .put("duration", it.durationMs)
                    .put("updated", it.updatedAt)
            )
        }
        prefs(context).edit().putString(key(profileId), array.toString()).apply()
    }
}
