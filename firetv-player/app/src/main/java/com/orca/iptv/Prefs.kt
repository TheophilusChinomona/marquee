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
}
