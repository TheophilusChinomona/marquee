package com.orca.iptv

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Everything that is not in the provider's catalogue.
 *
 * Two backends sit behind this facade, both addressed by the same synthetic ids:
 *
 *  - `gd:` - our own resolver (`/resolver` in this repo). It returns a plain,
 *    signed MP4 or HLS link, so it needs a host that is running when you watch.
 *  - `fx:` - frameX, a public aggregator that answers with HLS but depends on
 *    whatever its scrapers can reach today.
 *
 * The resolver is asked first; frameX covers the case where the resolver's host is
 * asleep. Both are gated by the "Online sources" switch.
 */
object Online {

    private const val PREFIX = "gd:"

    fun isOnline(id: String): Boolean = id.startsWith(PREFIX) || FrameX.isOnline(id)

    fun isResolver(id: String): Boolean = id.startsWith(PREFIX)

    /** "gd:tv:tYOM5N" -> "tv" */
    fun typeOf(id: String): String = when {
        isResolver(id) -> id.removePrefix(PREFIX).substringBefore(':')
        else -> FrameX.typeOf(id)
    }

    /** "gd:tv:tYOM5N" -> "tYOM5N" */
    fun keyOf(id: String): String = when {
        isResolver(id) -> id.removePrefix(PREFIX).substringAfter(':')
        else -> FrameX.tmdbOf(id)
    }

    fun resolverId(kind: String, key: String): String =
        PREFIX + kind + ":" + key.removePrefix("/")

    fun episodeSpec(titleId: String, season: Int, episode: Int): String =
        if (isOnline(titleId)) "$titleId|$season|$episode" else ""

    data class Hit(
        val id: String,
        val name: String,
        val poster: String,
        val year: String,
        val rating: String,
        val mode: Int
    )

    data class Playback(val url: String, val container: String)

    fun search(context: Context, query: String): List<Hit> {
        val local = ResolverClient(Prefs.resolverBase(context), Prefs.resolverKey(context)).search(query)
        if (local.isNotEmpty()) return local
        return FrameXClient().search(query).map {
            Hit(it.id, it.name, it.poster, it.year, it.rating, it.mode)
        }
    }

    fun seriesDetails(context: Context, titleId: String): XtreamClient.SeriesDetails? = when {
        isResolver(titleId) -> ResolverClient(Prefs.resolverBase(context), Prefs.resolverKey(context)).series(titleId)
        else -> FrameXClient().seriesDetails(titleId)
    }

    fun seasonEpisodes(context: Context, titleId: String, season: Int): List<XtreamClient.EpisodeDetails> =
        when {
            isResolver(titleId) -> ResolverClient(Prefs.resolverBase(context), Prefs.resolverKey(context)).episodes(titleId, season)
            else -> FrameXClient().seasonEpisodes(titleId, season)
        }

    fun movieDetails(context: Context, titleId: String): XtreamClient.MovieDetails? = when {
        isResolver(titleId) -> ResolverClient(Prefs.resolverBase(context), Prefs.resolverKey(context)).movie(titleId)
        else -> FrameXClient().movieDetails(titleId)
    }

    fun resolve(context: Context, titleId: String, season: Int = 0, episode: Int = 0): Playback? = when {
        isResolver(titleId) -> ResolverClient(Prefs.resolverBase(context), Prefs.resolverKey(context)).resolve(titleId, season, episode)
        else -> FrameXClient().resolve(titleId, season, episode)?.let { Playback(it.url, "hls") }
    }

    fun resolveSpec(context: Context, spec: String): Playback? {
        val parts = spec.split('|')
        if (parts.size != 3) return null
        val season = parts[1].toIntOrNull() ?: return null
        val episode = parts[2].toIntOrNull() ?: return null
        return resolve(context, parts[0], season, episode)
    }
}

/** Talks to our own resolver: one host on the LAN, three small endpoints. */
class ResolverClient(private val base: String, private val key: String = "") {

    private val root = base.trim().trimEnd('/')

    fun search(query: String): List<Online.Hit> {
        val trimmed = query.trim()
        if (root.isBlank() || trimmed.length < 3) return emptyList()
        val json = get("/search?q=" + URLEncoder.encode(trimmed, "UTF-8")) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Online.Hit>(results.length())
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            val key = row.optString("id").removePrefix("/")
            if (key.isBlank()) continue
            val kind = row.optString("kind").ifBlank { if (key.startsWith("m")) "movie" else "tv" }
            val mode = if (kind == "tv") MODE_SERIES else MODE_VOD
            out.add(
                Online.Hit(
                    id = Online.resolverId(kind, key),
                    name = row.optString("name"),
                    poster = row.optString("poster"),
                    year = row.optString("year"),
                    rating = row.optString("rating"),
                    mode = mode
                )
            )
        }
        return out
    }

    fun series(titleId: String): XtreamClient.SeriesDetails? {
        val json = get("/show?id=" + encode(keyOf(titleId))) ?: return null
        val seasonsJson = json.optJSONArray("seasons")
        val seasons = ArrayList<XtreamClient.SeasonDetails>(seasonsJson?.length() ?: 0)
        if (seasonsJson != null) {
            for (i in 0 until seasonsJson.length()) {
                val season = seasonsJson.optJSONObject(i) ?: continue
                val number = season.optInt("number", i + 1)
                seasons.add(
                    XtreamClient.SeasonDetails(
                        number = number,
                        episodes = episodesOf(season, number)
                    )
                )
            }
        }
        return XtreamClient.SeriesDetails(
            name = json.optString("name"),
            backdrop = json.optString("poster"),
            poster = json.optString("poster"),
            plot = json.optString("plot"),
            genre = "",
            releaseDate = json.optString("year"),
            rating = "",
            runtimeMinutes = 0,
            seasons = seasons
        )
    }

    fun episodes(titleId: String, number: Int): List<XtreamClient.EpisodeDetails> {
        val details = series(titleId) ?: return emptyList()
        return details.seasons.firstOrNull { it.number == number }?.episodes.orEmpty()
    }

    private fun episodesOf(season: JSONObject, number: Int): List<XtreamClient.EpisodeDetails> {
        val array = season.optJSONArray("episodes") ?: return emptyList()
        val out = ArrayList<XtreamClient.EpisodeDetails>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val episodeNumber = row.optInt("number", i + 1)
            out.add(
                XtreamClient.EpisodeDetails(
                    id = row.optString("id"),
                    season = row.optInt("season", number),
                    number = episodeNumber,
                    title = row.optString("name").ifBlank { "Episode $episodeNumber" },
                    ext = "mp4",
                    image = row.optString("still"),
                    plot = row.optString("plot"),
                    duration = "",
                    durationSeconds = 0,
                    rating = ""
                )
            )
        }
        out.sortBy { it.number }
        return out
    }

    fun movie(titleId: String): XtreamClient.MovieDetails? {
        val json = get("/show?id=" + encode(keyOf(titleId))) ?: return null
        return XtreamClient.MovieDetails(
            name = json.optString("name"),
            backdrop = json.optString("poster"),
            poster = json.optString("poster"),
            plot = json.optString("plot"),
            genre = "",
            releaseDate = json.optString("year"),
            rating = "",
            durationMinutes = 0
        )
    }

    fun resolve(titleId: String, season: Int, episode: Int): Online.Playback? {
        val query = StringBuilder("/resolve?id=").append(encode(keyOf(titleId)))
        if (typeOf(titleId) == "tv") {
            query.append("&season=").append(if (season > 0) season else 1)
            query.append("&episode=").append(if (episode > 0) episode else 1)
        }
        val json = get(query.toString(), readTimeoutMs = 110_000) ?: return null
        val url = json.optString("url")
        if (url.isBlank()) return null
        val container = json.optString("container").ifBlank { "mp4" }
        return Online.Playback(url, container)
    }

    private fun keyOf(titleId: String): String =
        if (Online.isResolver(titleId)) Online.keyOf(titleId) else titleId

    private fun typeOf(titleId: String): String = Online.typeOf(titleId)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(path: String, readTimeoutMs: Int = 30_000): JSONObject? {
        if (root.isBlank()) return null
        val conn = runCatching { URL(root + path).openConnection() as HttpURLConnection }.getOrNull()
            ?: return null
        return try {
            conn.connectTimeout = 4_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            applyCredential(conn)
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "resolver $path -> HTTP $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (error: IOException) {
            Log.w(TAG, "resolver $path failed", error)
            null
        } catch (error: org.json.JSONException) {
            Log.w(TAG, "resolver $path returned junk", error)
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * `clientId:clientSecret` is a Cloudflare Access service token, which the edge
     * checks before the request ever reaches the house. Anything else is sent as a
     * bearer token for the resolver's own RESOLVER_TOKEN.
     */
    private fun applyCredential(conn: HttpURLConnection) {
        val value = key.trim()
        if (value.isEmpty()) return
        if (value.contains(':')) {
            val parts = value.split(':', limit = 2)
            conn.setRequestProperty("CF-Access-Client-Id", parts[0].trim())
            conn.setRequestProperty("CF-Access-Client-Secret", parts.getOrElse(1) { "" }.trim())
        } else {
            conn.setRequestProperty("Authorization", "Bearer $value")
        }
    }

    companion object {
        private const val TAG = "MarqueeResolver"
    }
}
