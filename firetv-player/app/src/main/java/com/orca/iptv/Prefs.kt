package com.orca.iptv

import android.content.Context

data class Creds(val server: String, val user: String, val pass: String)

object Prefs {
    private const val FILE = "iptv"

    fun load(context: Context): Creds? {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val server = p.getString("server", null) ?: return null
        return Creds(
            server,
            p.getString("user", "").orEmpty(),
            p.getString("pass", "").orEmpty()
        )
    }

    fun save(context: Context, creds: Creds) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("server", creds.server)
            .putString("user", creds.user)
            .putString("pass", creds.pass)
            .apply()
    }

    private const val RECENT_FILE = "search"
    private const val RECENT_KEY = "recent"
    private const val RECENT_LIMIT = 6

    fun recents(context: Context): List<String> {
        val raw = context.getSharedPreferences(RECENT_FILE, Context.MODE_PRIVATE)
            .getString(RECENT_KEY, null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }

    fun addRecent(context: Context, query: String) {
        val value = query.trim()
        if (value.length < 2) return
        val updated = (listOf(value) + recents(context).filterNot { it.equals(value, ignoreCase = true) })
            .take(RECENT_LIMIT)
        val array = org.json.JSONArray()
        updated.forEach(array::put)
        context.getSharedPreferences(RECENT_FILE, Context.MODE_PRIVATE)
            .edit().putString(RECENT_KEY, array.toString()).apply()
    }
}
