package com.orca.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(
    val id: String,
    val name: String,
    val icon: String,
    val ext: String,
    val mode: Int
) {
    fun toItem() = Catalog.Item(id, name, "", icon, ext)
}

/** Favorites are stored per profile, newest first. */
object FavoriteStore {
    private const val FILE = "favorites"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(profileId: String) = "items_$profileId"

    fun all(context: Context, profileId: String): List<Favorite> {
        val raw = prefs(context).getString(key(profileId), null) ?: return emptyList()
        val list = ArrayList<Favorite>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isBlank()) continue
                list.add(
                    Favorite(
                        id = id,
                        name = obj.optString("name"),
                        icon = obj.optString("icon"),
                        ext = obj.optString("ext"),
                        mode = obj.optInt("mode", MODE_VOD)
                    )
                )
            }
        }
        return list
    }

    fun isFavorite(context: Context, profileId: String, id: String, mode: Int): Boolean =
        all(context, profileId).any { it.id == id && it.mode == mode }

    /** Returns true when the item was added, false when it was removed. */
    fun toggle(context: Context, profileId: String, item: Catalog.Item, mode: Int): Boolean {
        val list = all(context, profileId).toMutableList()
        val index = list.indexOfFirst { it.id == item.id && it.mode == mode }
        if (index >= 0) {
            list.removeAt(index)
            save(context, profileId, list)
            return false
        }
        list.add(0, Favorite(item.id, item.name, item.icon, item.ext, mode))
        save(context, profileId, list)
        return true
    }

    fun remove(context: Context, profileId: String, id: String, mode: Int) {
        val list = all(context, profileId).filterNot { it.id == id && it.mode == mode }
        save(context, profileId, list)
    }

    fun clear(context: Context, profileId: String) {
        prefs(context).edit().remove(key(profileId)).apply()
    }

    private fun save(context: Context, profileId: String, list: List<Favorite>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("icon", it.icon)
                    .put("ext", it.ext)
                    .put("mode", it.mode)
            )
        }
        prefs(context).edit().putString(key(profileId), array.toString()).apply()
    }
}
