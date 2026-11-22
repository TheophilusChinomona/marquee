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

    /**
     * The online fallback (frameX) is on out of the box, so a title the provider
     * does not carry can still be found. Every call it makes is behind this flag,
     * because it is a third-party service that can be slow or disappear.
     */
    fun onlineEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONLINE, true)

    fun setOnlineEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONLINE, enabled).apply()
    }

    private const val KEY_ONLINE = "online_sources"
    private const val KEY_RESOLVER = "resolver_base"

    /** Where our own resolver listens. Empty disables it and leaves frameX alone. */
    fun resolverBase(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_RESOLVER, DEFAULT_RESOLVER_BASE).orEmpty()

    fun setResolverBase(context: Context, base: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_RESOLVER, base.trim()).apply()
    }

    /**
     * Optional credential for the resolver host. Either a Cloudflare Access service
     * token written as `clientId:clientSecret`, or a plain bearer token that
     * RESOLVER_TOKEN accepts. Blank means the host is open on the LAN.
     */
    fun resolverKey(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_RESOLVER_KEY, "").orEmpty()

    fun setResolverKey(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_RESOLVER_KEY, key.trim()).apply()
    }

    private const val KEY_RESOLVER_KEY = "resolver_key"

    // Shipped builds must not point at anybody's server: the resolver is opt-in and
    // configured per device (Profiles -> Server).
    private const val DEFAULT_RESOLVER_BASE = ""

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
