package com.orca.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class XtreamClient(server: String, private val user: String, private val pass: String) {

    data class Category(val id: String, val name: String)
    data class Stream(val id: String, val name: String, val icon: String, val ext: String)
    data class EpisodeDetails(
        val id: String,
        val season: Int,
        val number: Int,
        val title: String,
        val ext: String,
        val image: String,
        val plot: String,
        val duration: String,
        val durationSeconds: Int,
        val rating: String
    )

    data class SeasonDetails(
        val number: Int,
        val episodes: List<EpisodeDetails>
    )

    data class SeriesDetails(
        val name: String,
        val backdrop: String,
        val poster: String,
        val plot: String,
        val genre: String,
        val releaseDate: String,
        val rating: String,
        val runtimeMinutes: Int,
        val seasons: List<SeasonDetails>
    )

    private val base = server.trim().trimEnd('/')
    private val auth = "username=${enc(user)}&password=${enc(pass)}"

    fun accountInfo(): JSONObject = JSONObject(fetch("$base/player_api.php?$auth"))

    fun categories(action: String): List<Category> {
        val arr = JSONArray(fetch("$base/player_api.php?$auth&action=$action"))
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("category_id")
            if (id.isBlank()) continue
            out.add(Category(id, o.optString("category_name").ifBlank { id }))
        }
        return out
    }

    fun streams(action: String, categoryId: String): List<Stream> {
        val url = "$base/player_api.php?$auth&action=$action&category_id=${enc(categoryId)}"
        val arr = JSONArray(fetch(url))
        val out = ArrayList<Stream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("stream_id")
            if (id.isBlank()) continue
            out.add(
                Stream(
                    id = id,
                    name = o.optString("name").ifBlank { "Channel" },
                    icon = o.optString("stream_icon"),
                    ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
                )
            )
        }
        return out
    }

    fun series(categoryId: String): List<Stream> {
        val url = "$base/player_api.php?$auth&action=get_series&category_id=${enc(categoryId)}"
        val arr = JSONArray(fetch(url))
        val out = ArrayList<Stream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("series_id")
            if (id.isBlank()) continue
            out.add(
                Stream(
                    id = id,
                    name = o.optString("name").ifBlank { "Series" },
                    icon = o.optString("cover"),
                    ext = ""
                )
            )
        }
        return out
    }

    fun seriesDetails(seriesId: String): SeriesDetails {
        val url = "$base/player_api.php?$auth&action=get_series_info&series_id=${enc(seriesId)}"
        val root = JSONObject(fetch(url))
        val info = root.optJSONObject("info") ?: JSONObject()
        val episodes = root.optJSONObject("episodes") ?: JSONObject()

        val seasons = ArrayList<SeasonDetails>()
        for (seasonKey in episodes.keys()) {
            val array = episodes.optJSONArray(seasonKey) ?: continue
            val list = ArrayList<EpisodeDetails>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                val episodeInfo = o.optJSONObject("info") ?: JSONObject()
                list.add(
                    EpisodeDetails(
                        id = id,
                        season = o.optInt("season", seasonKey.toIntOrNull() ?: 0),
                        number = o.optInt("episode_num", i + 1),
                        title = o.optString("title"),
                        ext = o.optString("container_extension", "mp4").ifBlank { "mp4" },
                        image = episodeInfo.optString("movie_image"),
                        plot = episodeInfo.optString("plot"),
                        duration = episodeInfo.optString("duration"),
                        durationSeconds = episodeInfo.optInt("duration_secs"),
                        rating = episodeInfo.opt("rating")?.toString().orEmpty()
                    )
                )
            }
            if (list.isNotEmpty()) {
                seasons.add(SeasonDetails(seasonKey.toIntOrNull() ?: 0, list.sortedBy { it.number }))
            }
        }
        seasons.sortBy { it.number }

        val backdrop = when (val raw = info.opt("backdrop_path")) {
            is JSONArray -> if (raw.length() > 0) raw.optString(0) else ""
            is String -> raw
            else -> ""
        }.ifBlank { info.optString("cover") }

        val genre = when (val raw = info.opt("genre")) {
            is JSONArray -> (0 until raw.length()).joinToString(", ") { raw.optString(it) }
            else -> info.optString("genre")
        }

        return SeriesDetails(
            name = info.optString("name"),
            backdrop = backdrop,
            poster = info.optString("cover"),
            plot = info.optString("plot"),
            genre = genre,
            releaseDate = info.optString("releaseDate"),
            rating = info.opt("rating")?.toString().orEmpty(),
            runtimeMinutes = info.optInt("episode_run_time"),
            seasons = seasons
        )
    }

    fun liveUrl(id: String) = "$base/live/$user/$pass/$id.ts"
    fun liveHlsUrl(id: String) = "$base/live/$user/$pass/$id.m3u8"
    fun vodUrl(id: String, ext: String) = "$base/movie/$user/$pass/$id.$ext"
    fun seriesUrl(id: String, ext: String) = "$base/series/$user/$pass/$id.$ext"

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "IPTVSmartersPlayer")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
