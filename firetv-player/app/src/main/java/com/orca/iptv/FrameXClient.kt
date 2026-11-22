package com.orca.iptv

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * frameX (api.framextv.xyz) is an aggregator fronting ~20 scraper servers behind
 * one JSON API. Unlike a link site, it answers with plain HLS playlists, which is
 * the one shape ExoPlayer already handles here.
 *
 * It is a *fallback*, never a catalogue: titles are addressed by TMDB id, and a
 * stream URL is resolved per play because the servers hand out short-lived,
 * signed links. Nothing is stored beyond the id.
 */
object FrameX {

    private const val PREFIX = "fx:"
    const val TYPE_TV = "tv"
    const val TYPE_MOVIE = "movie"

    fun seriesId(tmdb: String): String = "$PREFIX$TYPE_TV:$tmdb"

    fun movieId(tmdb: String): String = "$PREFIX$TYPE_MOVIE:$tmdb"

    fun isOnline(id: String): Boolean = id.startsWith(PREFIX)

    /** "fx:tv:1396" -> "tv"; "" for provider ids. */
    fun typeOf(id: String): String =
        if (isOnline(id)) id.removePrefix(PREFIX).substringBefore(':') else ""

    /** "fx:tv:1396" -> "1396"; "" for provider ids. */
    fun tmdbOf(id: String): String =
        if (isOnline(id)) id.removePrefix(PREFIX).substringAfter(':', "") else ""

    /** What the player queue carries for an episode whose URL is not resolved yet. */
    fun episodeSpec(seriesId: String, season: Int, episode: Int): String =
        if (isOnline(seriesId)) "$seriesId|$season|$episode" else ""
}

class FrameXClient {

    data class Hit(
        val id: String,
        val name: String,
        val poster: String,
        val year: String,
        val rating: String,
        val mode: Int
    )

    data class Playback(val url: String, val server: String)

    fun search(query: String, limit: Int = 18): List<Hit> {
        val trimmed = query.trim()
        if (trimmed.length < 3) return emptyList()
        val json = get("/api/search?q=${URLEncoder.encode(trimmed, "UTF-8")}") ?: return emptyList()
        val array = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Hit>(minOf(limit, array.length()))
        for (i in 0 until array.length()) {
            if (out.size >= limit) break
            val o = array.optJSONObject(i) ?: continue
            val tmdb = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val type = o.optString("type")
            val mode = when (type) {
                FrameX.TYPE_TV -> MODE_SERIES
                "movie" -> MODE_VOD
                else -> continue
            }
            val name = o.optString("title").ifBlank { o.optString("name") }
            if (name.isBlank()) continue
            out.add(
                Hit(
                    id = if (mode == MODE_SERIES) FrameX.seriesId(tmdb) else FrameX.movieId(tmdb),
                    name = name,
                    poster = o.optString("poster").ifBlank { o.optString("image") },
                    year = o.optString("year"),
                    rating = number(o, "rating"),
                    mode = mode
                )
            )
        }
        return out
    }

    /**
     * Seasons arrive with their episode counts but no episodes, so opening a
     * ten-season show costs one request instead of ten; [season] fills one in when
     * the viewer picks it.
     */
    fun seriesDetails(seriesId: String): XtreamClient.SeriesDetails? {
        val tmdb = FrameX.tmdbOf(seriesId)
        if (tmdb.isBlank()) return null
        val json = get("/api/details?type=${FrameX.TYPE_TV}&id=$tmdb") ?: return null
        if (!json.optBoolean("success", true)) return null

        val seasonsJson = json.optJSONArray("seasons")
        val seasons = ArrayList<XtreamClient.SeasonDetails>(seasonsJson?.length() ?: 0)
        if (seasonsJson != null) {
            for (i in 0 until seasonsJson.length()) {
                val o = seasonsJson.optJSONObject(i) ?: continue
                // season 0 is TMDB's "Specials" bucket and has no provider streams
                val number = o.optInt("season_number", -1)
                if (number <= 0) continue
                if (o.optInt("episode_count", 0) <= 0) continue
                seasons.add(
                    XtreamClient.SeasonDetails(
                        number = number,
                        episodes = if (number == 1) episodes(tmdb, number) else emptyList()
                    )
                )
            }
        }
        if (seasons.isEmpty()) {
            // No season list on this title: still let season 1 try to load.
            seasons.add(
                XtreamClient.SeasonDetails(
                    number = 1,
                    episodes = episodes(tmdb, 1)
                )
            )
        }
        seasons.sortBy { it.number }

        return XtreamClient.SeriesDetails(
            name = json.optString("title"),
            backdrop = json.optString("backdrop"),
            poster = json.optString("poster"),
            plot = json.optString("overview"),
            genre = genres(json),
            releaseDate = json.optString("release_date"),
            rating = number(json, "rating"),
            runtimeMinutes = 0,
            seasons = seasons
        )
    }

    fun seasonEpisodes(seriesId: String, season: Int): List<XtreamClient.EpisodeDetails> =
        episodes(FrameX.tmdbOf(seriesId), season)

    private fun episodes(tmdb: String, season: Int): List<XtreamClient.EpisodeDetails> {
        if (tmdb.isBlank()) return emptyList()
        val json = get("/api/tv/$tmdb/season/$season") ?: return emptyList()
        val array = json.optJSONArray("episodes") ?: return emptyList()
        val out = ArrayList<XtreamClient.EpisodeDetails>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val number = o.optInt("episode_number", o.optInt("number", 0))
            if (number <= 0) continue
            out.add(
                XtreamClient.EpisodeDetails(
                    id = o.optString("id", "$season-$number"),
                    season = o.optInt("season_number", season),
                    number = number,
                    title = o.optString("name").ifBlank { "Episode $number" },
                    ext = "hls",
                    image = o.optString("still"),
                    plot = o.optString("overview"),
                    duration = "",
                    durationSeconds = 0,
                    rating = number(o, "vote_average")
                )
            )
        }
        return out
    }

    fun movieDetails(movieId: String): XtreamClient.MovieDetails? {
        val tmdb = FrameX.tmdbOf(movieId)
        if (tmdb.isBlank()) return null
        val json = get("/api/details?type=${FrameX.TYPE_MOVIE}&id=$tmdb") ?: return null
        if (!json.optBoolean("success", true)) return null
        return XtreamClient.MovieDetails(
            name = json.optString("title"),
            backdrop = json.optString("backdrop"),
            poster = json.optString("poster"),
            plot = json.optString("overview"),
            genre = genres(json),
            releaseDate = json.optString("release_date"),
            rating = number(json, "rating"),
            durationMinutes = json.optInt("runtime", 0)
        )
    }

    /** Turns a queue spec ("fx:tv:1396|1|1") back into a title id and coordinates. */
    fun resolveSpec(spec: String): Playback? {
        val parts = spec.split('|')
        if (parts.size != 3) return null
        val season = parts[1].toIntOrNull() ?: return null
        val episode = parts[2].toIntOrNull() ?: return null
        return resolve(parts[0], season, episode)
    }

    /**
     * The API answers with several copies of the same stream (direct, a Cloudflare
     * relay, and its own VPS proxy). Any of them can be geo-blocked or expired, so
     * each is opened once and only a real playlist is handed to the player.
     */
    fun resolve(titleId: String, season: Int = 0, episode: Int = 0): Playback? {
        val type = FrameX.typeOf(titleId).ifBlank { return null }
        val tmdb = FrameX.tmdbOf(titleId)
        if (tmdb.isBlank()) return null

        val query = StringBuilder("/api/stream?type=").append(type).append("&id=").append(tmdb)
        if (type == FrameX.TYPE_TV) {
            query.append("&season=").append(if (season > 0) season else 1)
            query.append("&episode=").append(if (episode > 0) episode else 1)
        }
        val json = get(query.toString()) ?: return null
        val sources = json.optJSONArray("sources") ?: return null

        for (i in 0 until sources.length()) {
            val o = sources.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            if (playable(url)) {
                return Playback(url, o.optString("server").ifBlank { "online" })
            }
        }
        Log.w(TAG, "no playable source for $titleId s$season e$episode")
        return null
    }

    /** True when the URL answers with a playlist or a media file, not a 403 page. */
    private fun playable(url: String): Boolean {
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return false
        return try {
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = PROBE_TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code !in 200..299) return false
            val head = conn.inputStream.use { stream ->
                val buffer = ByteArray(512)
                val read = stream.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            }
            head.contains("#EXTM3U") || conn.contentType.orEmpty().contains("mpegurl") ||
                url.endsWith(".mp4", ignoreCase = true)
        } catch (error: IOException) {
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Hosts are the same deployment behind different domains; try them in turn. */
    private fun get(path: String): JSONObject? {
        var failure: Exception? = null
        for (host in HOSTS) {
            try {
                return JSONObject(fetch("$host$path"))
            } catch (error: Exception) {
                failure = error
            }
        }
        Log.w(TAG, "request failed: $path", failure)
        return null
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun genres(json: JSONObject): String {
        val array = json.optJSONArray("genres") ?: return ""
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) out.add(value)
        }
        return out.joinToString(", ")
    }

    private fun number(json: JSONObject, key: String): String {
        val raw = json.opt(key) ?: return ""
        val value = when (raw) {
            is Number -> raw.toDouble()
            else -> raw.toString().toDoubleOrNull() ?: return ""
        }
        if (value <= 0.0) return ""
        return if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)
    }

    companion object {
        private const val TAG = "MarqueeFrameX"
        private const val CONNECT_TIMEOUT = 8000
        private const val READ_TIMEOUT = 25_000
        private const val PROBE_TIMEOUT = 10_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        private val HOSTS = listOf(
            "https://api.framextv.xyz",
            "https://api.framextv.cc",
            "https://api.framextv.tech"
        )
    }
}
