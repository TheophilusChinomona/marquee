package com.orca.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Profile(val id: String, val name: String)

/**
 * Device-local profiles. The provider login stays shared; each profile owns
 * only its favorites and UI state.
 */
object ProfileStore {
    private const val FILE = "profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun read(context: Context): ArrayList<Profile> {
        val raw = prefs(context).getString(KEY_PROFILES, null)
        val list = ArrayList<Profile>()
        if (raw != null) {
            runCatching {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id")
                    val name = obj.optString("name")
                    if (id.isNotBlank() && name.isNotBlank()) {
                        list.add(Profile(id, name))
                    }
                }
            }
        }
        if (list.isEmpty()) {
            list.add(Profile("default", "Me"))
            save(context, list)
        }
        return list
    }

    private fun save(context: Context, list: List<Profile>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name))
        }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun all(context: Context): List<Profile> = read(context)

    fun activeId(context: Context): String {
        val list = read(context)
        val stored = prefs(context).getString(KEY_ACTIVE, null)
        if (stored != null && list.any { it.id == stored }) return stored
        val fallback = list.first().id
        prefs(context).edit().putString(KEY_ACTIVE, fallback).apply()
        return fallback
    }

    fun active(context: Context): Profile {
        val id = activeId(context)
        return read(context).first { it.id == id }
    }

    fun setActive(context: Context, id: String) {
        if (read(context).any { it.id == id }) {
            prefs(context).edit().putString(KEY_ACTIVE, id).apply()
        }
    }

    fun add(context: Context, name: String): Profile {
        val profile = Profile(UUID.randomUUID().toString(), name.trim())
        val list = read(context)
        list.add(profile)
        save(context, list)
        return profile
    }

    fun remove(context: Context, id: String): Boolean {
        val list = read(context)
        if (list.size <= 1) return false
        val target = list.firstOrNull { it.id == id } ?: return false
        list.remove(target)
        if (activeId(context) == id) {
            prefs(context).edit().putString(KEY_ACTIVE, list.first().id).apply()
        }
        FavoriteStore.clear(context, id)
        HistoryStore.clear(context, id)
        save(context, list)
        return true
    }
}
