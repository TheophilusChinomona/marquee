package com.orca.iptv

import android.content.Context
import org.json.JSONObject

object Catalog {

    data class Item(
        val id: String,
        val name: String,
        val categoryId: String,
        val icon: String,
        val ext: String
    )

    fun displayName(raw: String): String {
        var value = raw.replace(
            Regex(
                """\s*\[(MULTI-SUB|MULTI-LANG|MULTI-AUDIO|4K|FHD|HD|SD)\]\s*""",
                RegexOption.IGNORE_CASE
            ),
            " "
        )
        value = value.replace(Regex("""\s+-\s+\d{4}\s*$"""), "")
        value = value.replace(Regex("""^[A-Z]{2,3}\|\s*"""), "")
        return value.trim().replace(Regex("""\s{2,}"""), " ")
    }

    fun allVod(): List<Item> = vodSections.flatMap { it.items }

    fun allSeries(): List<Item> = seriesSections.flatMap { it.items }

    fun find(mode: Int, id: String): Item? = when (mode) {
        MODE_LIVE -> liveSections.asSequence().flatMap { it.items.asSequence() }
            .firstOrNull { it.id == id }
        MODE_SERIES -> seriesSections.asSequence().flatMap { it.items.asSequence() }
            .firstOrNull { it.id == id }
        else -> vodSections.asSequence().flatMap { it.items.asSequence() }
            .firstOrNull { it.id == id }
    }

    data class Section(
        val id: String,
        val name: String,
        val items: List<Item>
    )

    private val lock = Any()

    @Volatile
    private var homeLoaded = false

    @Volatile
    private var searchLoaded = false

    var liveSections: List<Section> = emptyList()
        private set
    var vodSections: List<Section> = emptyList()
        private set
    var seriesSections: List<Section> = emptyList()
        private set

    var liveItems: List<Item> = emptyList()
        private set
    var searchVodItems: List<Item> = emptyList()
        private set
    var searchSeriesItems: List<Item> = emptyList()
        private set

    fun ensureHome(context: Context) {
        if (homeLoaded) return
        synchronized(lock) {
            if (homeLoaded) return
            val live = load(context, "live_catalog.json")
            val vod = load(context, "vod_home.json")
            val series = load(context, "series_home.json")
            liveSections = live
            vodSections = vod
            seriesSections = series
            liveItems = live.flatMap { it.items }
            homeLoaded = true
        }
    }

    fun ensureSearch(context: Context) {
        ensureHome(context)
        if (searchLoaded) return
        synchronized(lock) {
            if (searchLoaded) return
            val vod = load(context, "vod_catalog.json")
            val series = load(context, "series_catalog.json")
            searchVodItems = vod.flatMap { it.items }
            searchSeriesItems = series.flatMap { it.items }
            searchLoaded = true
        }
    }

    private fun load(context: Context, asset: String): List<Section> {
        val text = context.assets.open(asset).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val categories = root.getJSONArray("categories")
        val items = root.getJSONArray("items")

        val byCategory = LinkedHashMap<String, MutableList<Item>>()
        for (i in 0 until items.length()) {
            val values = items.getJSONArray(i)
            val item = Item(
                id = values.getString(0),
                name = values.getString(1),
                categoryId = values.getString(2),
                icon = if (values.length() > 3) values.optString(3) else "",
                ext = if (values.length() > 4) values.optString(4) else ""
            )
            byCategory.getOrPut(item.categoryId) { ArrayList() }.add(item)
        }

        val sections = ArrayList<Section>(categories.length())
        for (i in 0 until categories.length()) {
            val category = categories.getJSONArray(i)
            val id = category.getString(0)
            val sectionItems = byCategory[id] ?: continue
            sections.add(Section(id, category.getString(1), sectionItems))
        }
        return sections
    }
}
