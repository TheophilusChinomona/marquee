package com.orca.iptv

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One release to offer. Mirrors `version.json` in the repository root.
 */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String
)

/**
 * Reads the small `version.json` published on the default branch and compares it
 * with the installed build. This is the app's only call to a server it does not
 * otherwise need: see "Privacy" in the README.
 *
 * Failure is always silent. A missing file, a bad payload, no network or a
 * provider firewall must never be visible to the user or delayed on the UI thread.
 */
object UpdateChecker {

    private const val MANIFEST_URL =
        "https://github.com/TheophilusChinomona/marquee/releases/latest/download/version.json"
    private const val LEGACY_MANIFEST_URL =
        "https://raw.githubusercontent.com/TheophilusChinomona/marquee/master/version.json"

    fun installedVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info)
    }.getOrDefault(0L)

    fun check(context: Context): AppUpdate? {
        val text = fetch(MANIFEST_URL, bustCache = true) ?: fetch(LEGACY_MANIFEST_URL, bustCache = true) ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val code = json.optLong("versionCode", 0L)
        val url = json.optString("apkUrl").trim()
        if (code <= 0L || code <= installedVersionCode(context) || url.isBlank()) return null
        return AppUpdate(
            versionCode = code,
            versionName = json.optString("versionName").ifBlank { code.toString() },
            apkUrl = url,
            sha256 = json.optString("sha256").trim(),
            notes = json.optString("notes").trim()
        )
    }

    internal fun fetch(url: String, bustCache: Boolean = false): String? = runCatching {
        val target = if (bustCache) {
            val separator = if (url.contains('?')) '&' else '?'
            "$url${separator}t=${System.currentTimeMillis()}"
        } else {
            url
        }
        val conn = URL(target).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
