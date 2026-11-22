package com.orca.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The app ships catalogue snapshots in `assets/`, which go stale the moment the
 * provider adds titles. This refreshes them from the API into app storage, in the
 * same JSON shape, and [Catalog] prefers the cached copy.
 *
 * Live is included because providers add channels too; that is 140 small requests,
 * so callers must expect this to take a minute on a TV stick and run it in the
 * background.
 */
object CatalogSync {

    private const val DIR = "catalog"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val HOME_PER_CATEGORY = 20

    private val lock = Any()

    fun cacheDir(context: Context) = File(context.filesDir, DIR)

    fun cacheFile(context: Context, name: String) = File(cacheDir(context), name)

    fun cacheAgeMs(context: Context): Long? {
        val file = cacheFile(context, "vod_catalog.json")
        return if (file.exists()) System.currentTimeMillis() - file.lastModified() else null
    }

    fun isStale(context: Context): Boolean {
        val age = cacheAgeMs(context) ?: return true
        return age > MAX_AGE_MS
    }

    /** Returns true when every catalogue was fetched and written. */
    suspend fun refresh(
        context: Context,
        client: XtreamClient,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                val dir = cacheDir(context).apply { mkdirs() }

                val live = fetch(client, "get_live_categories", "get_live_streams", false, "channels", onProgress)
                write(dir, "live_catalog.json", live)

                val vod = fetch(client, "get_vod_categories", "get_vod_streams", false, "movies", onProgress)
                write(dir, "vod_catalog.json", vod)
                write(dir, "vod_home.json", homeSlice(vod))

                val series = fetch(client, "get_series_categories", "", true, "series", onProgress)
                write(dir, "series_catalog.json", series)
                write(dir, "series_home.json", homeSlice(series))

                true
            }.onFailure { error ->
                android.util.Log.e("MarqueeCatalog", "catalogue refresh failed", error)
            }.getOrDefault(false)
        }
    }

    private fun fetch(
        client: XtreamClient,
        categoryAction: String,
        itemAction: String,
        seriesMode: Boolean,
        label: String,
        onProgress: (String) -> Unit
    ): Pair<List<Pair<String, String>>, List<List<String>>> {
        val categories = client.categories(categoryAction)
        val items = ArrayList<List<String>>(8_000)

        categories.forEachIndexed { index, category ->
            onProgress("$label ${index + 1}/${categories.size}")
            if (seriesMode) {
                client.series(category.id).forEach { stream ->
                    items.add(listOf(stream.id, stream.name, category.id, stream.icon, ""))
                }
            } else {
                client.streams(itemAction, category.id).forEach { stream ->
                    items.add(listOf(stream.id, stream.name, category.id, stream.icon, stream.ext))
                }
            }
        }
        return categories.map { it.id to it.name } to items
    }

    private fun homeSlice(
        data: Pair<List<Pair<String, String>>, List<List<String>>>
    ): Pair<List<Pair<String, String>>, List<List<String>>> {
        val grouped = data.second.groupBy { it[2] }
        val keep = ArrayList<List<String>>(HOME_PER_CATEGORY * data.first.size)
        data.first.forEach { (id, _) -> keep.addAll(grouped[id].orEmpty().take(HOME_PER_CATEGORY)) }
        return data.first to keep
    }

    private fun write(
        dir: File,
        name: String,
        data: Pair<List<Pair<String, String>>, List<List<String>>>
    ) {
        val root = JSONObject()
        val categories = JSONArray()
        data.first.forEach { (id, label) ->
            categories.put(JSONArray().put(id).put(label))
        }
        val items = JSONArray()
        data.second.forEach { row -> items.put(JSONArray(row)) }
        root.put("categories", categories)
        root.put("items", items)

        // Write then rename, so a killed process cannot leave a half catalogue behind.
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.writeText(root.toString())
        if (target.exists()) target.delete()
        temp.renameTo(target)
    }
}
