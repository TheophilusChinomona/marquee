package com.orca.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

/**
 * The app ships catalogue snapshots in `assets/`, which go stale the moment the
 * provider adds titles. This refreshes them from the API into app storage, in the
 * same JSON shape, and [Catalog] prefers the cached copy.
 *
 * Everything is streamed straight to disk, one provider category at a time: an
 * earlier version collected 58k rows in memory on top of the copy Search was
 * already holding, and a 1 GB Fire TV responds to that by killing the app.
 */
object CatalogSync {

    private const val DIR = "catalog"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val HOME_PER_CATEGORY = 20

    private val lock = Any()

    fun cacheDir(context: Context) = File(context.filesDir, DIR)

    fun cacheFile(context: Context, name: String) = File(cacheDir(context), name)

    fun isStale(context: Context): Boolean {
        val file = cacheFile(context, "vod_catalog.json")
        if (!file.exists()) return true
        return System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
    }

    /** Returns true when every catalogue was fetched and written. */
    suspend fun refresh(
        context: Context,
        client: XtreamClient,
        onProgress: (String) -> Unit,
        shouldContinue: () -> Boolean = { true }
    ): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                val dir = cacheDir(context).apply { mkdirs() }

                stream(client, dir, "live_catalog", "get_live_categories", "get_live_streams",
                    series = false, label = "channels", onProgress, shouldContinue) &&
                    stream(client, dir, "vod_catalog", "get_vod_categories", "get_vod_streams",
                        series = false, label = "movies", onProgress, shouldContinue) &&
                    stream(client, dir, "series_catalog", "get_series_categories", "",
                        series = true, label = "series", onProgress, shouldContinue)
            }.onFailure { error ->
                android.util.Log.e("MarqueeCatalog", "catalogue refresh failed", error)
            }.getOrDefault(false)
        }
    }

    /**
     * Writes `<name>.json` (everything) and `<name>_home.json` (the first twenty of
     * each category) in one pass, so no list larger than a single category is held.
     */
    private fun stream(
        client: XtreamClient,
        dir: File,
        name: String,
        categoryAction: String,
        itemAction: String,
        series: Boolean,
        label: String,
        onProgress: (String) -> Unit,
        shouldContinue: () -> Boolean
    ): Boolean {
        val categories = client.categories(categoryAction)
        if (categories.isEmpty()) return false

        val fullTemp = File(dir, "$name.json.tmp")
        val homeTemp = File(dir, "${name}_home.json.tmp")
        val header = "{\"categories\":" +
            JSONArray(categories.map { listOf(it.id, it.name) }).toString() +
            ",\"items\":["

        try {
            BufferedWriter(OutputStreamWriter(fullTemp.outputStream(), Charsets.UTF_8)).use { full ->
                BufferedWriter(OutputStreamWriter(homeTemp.outputStream(), Charsets.UTF_8)).use { home ->
                    full.write(header)
                    home.write(header)
                    var fullFirst = true
                    var homeFirst = true
                    var homeRows = 0

                    categories.forEachIndexed { index, category ->
                        if (!shouldContinue()) return false
                        onProgress("$label ${index + 1}/${categories.size}")

                        val rows = if (series) {
                            client.series(category.id).map {
                                listOf(it.id, it.name, category.id, it.icon, "")
                            }
                        } else {
                            client.streams(itemAction, category.id).map {
                                listOf(it.id, it.name, category.id, it.icon, it.ext)
                            }
                        }

                        rows.forEach { row ->
                            val json = JSONArray(row).toString()
                            if (!fullFirst) full.write(",")
                            full.write(json)
                            fullFirst = false

                            if (homeRows < HOME_PER_CATEGORY) {
                                if (!homeFirst) home.write(",")
                                home.write(json)
                                homeFirst = false
                                homeRows++
                            }
                        }
                        homeRows = 0
                    }

                    full.write("]}")
                    home.write("]}")
                }
            }
        } catch (error: Throwable) {
            fullTemp.delete()
            homeTemp.delete()
            throw error
        }

        replace(fullTemp, File(dir, "$name.json"))
        replace(homeTemp, File(dir, "${name}_home.json"))
        return true
    }

    private fun replace(temp: File, target: File) {
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}
