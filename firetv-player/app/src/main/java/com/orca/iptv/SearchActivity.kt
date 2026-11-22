package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient
    private lateinit var queryView: TextView
    private lateinit var header: TextView
    private lateinit var resultsView: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var recentRow: LinearLayout
    private val adapter = SearchGridAdapter(
        onClick = { result -> open(result) },
        onLongClick = { result -> toggleFavorite(result) }
    )
    private val gridColumns = 5
    private var query = ""
    private var catalogReady = false
    private var refreshing = false
    private var renderToken = 0
    @Volatile
    private var index: SearchIndex? = null
    private var favoriteKeys: Set<String> = emptySet()
    private data class SearchIndex(
        val vod: List<SearchHit>,
        val series: List<SearchHit>,
        val live: List<SearchHit>,
        val source: List<Catalog.Item>,
        val seriesSource: List<Catalog.Item>,
        val liveSource: List<Catalog.Item>
    )

    private data class SearchHit(
        val item: Catalog.Item,
        val mode: Int,
        val index: Int,
        val length: Int,
        val name: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this) ?: run {
            finish()
            return
        }
        client = XtreamClient(creds.server, creds.user, creds.pass)
        buildUi()
        loadCatalog()
        refreshIfStale()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarqueeUi.GROUND)
        }

        val nav = MarqueeNav(this, "search") { tab ->
            if (tab != "search") MarqueeNav.navigate(this, tab)
        }
        root.addView(nav, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(80)
        ))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(18), dp(36), dp(12))
        }
        body.addView(left, LinearLayout.LayoutParams(dp(520), LinearLayout.LayoutParams.MATCH_PARENT))

        queryView = MarqueeUi.text(this, "", 28f, MarqueeUi.DUST, MarqueeUi.body()).apply {
            background = MarqueeUi.fill(this@SearchActivity, MarqueeUi.VELVET, 10, 2, MarqueeUi.LINE2)
            setPadding(dp(20), 0, dp(20), 0)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(64)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        left.addView(queryView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(64)
        ))

        val hint = MarqueeUi.text(this, "Type a title, genre or channel", 14f, MarqueeUi.SAND, MarqueeUi.body())
        left.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        left.addView(keypad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        addKeyRow(keypad, "ABCDEF")
        addKeyRow(keypad, "GHIJKL")
        addKeyRow(keypad, "MNOPQR")
        addKeyRow(keypad, "STUVWX")

        val last = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        last.addView(key("Y"), keyParams(dp(54)))
        last.addView(key("Z"), keyParams(dp(54)))
        last.addView(key("Space"), keyParams(dp(126)))
        last.addView(key("⌫"), keyParams(dp(64)))
        last.addView(key("Clear"), keyParams(dp(80)))
        keypad.addView(last)

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(48), dp(20))
        }
        body.addView(right, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        ))

        header = MarqueeUi.text(this, "Trending at home", 24f, MarqueeUi.INK, MarqueeUi.medium())
        right.addView(header)

        recentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        right.addView(recentRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        resultsView = RecyclerView(this).apply {
            val grid = GridLayoutManager(this@SearchActivity, gridColumns)
            grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (this@SearchActivity.adapter.getItemViewType(position) == 0) gridColumns else 1
            }
            layoutManager = grid
            adapter = this@SearchActivity.adapter
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(14), dp(16), dp(20))
        }
        right.addView(resultsView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        status = MarqueeUi.text(this, "", 18f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            gravity = Gravity.CENTER
        }
        right.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        right.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        setContentView(root)
        updateQueryView()
        keypad.post {
            (keypad.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
        }
    }

    private fun addKeyRow(parent: LinearLayout, letters: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        letters.forEach { char ->
            row.addView(key(char.toString()), keyParams(dp(54)))
        }
        parent.addView(row)
    }

    private fun key(label: String): TextView =
        MarqueeUi.text(this, label, if (label.length > 2) 16f else 22f, MarqueeUi.INK, MarqueeUi.medium()).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            background = MarqueeUi.focusSelector(this@SearchActivity, MarqueeUi.VELVET, MarqueeUi.BULB, 8)
            setTextColor(MarqueeUi.textColors(MarqueeUi.INK, MarqueeUi.GROUND))
            setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.05f) }
            setOnClickListener {
                when (label) {
                    "Space" -> setQuery(query + " ")
                    "⌫" -> setQuery(query.dropLast(1))
                    "Clear" -> setQuery("")
                    else -> setQuery(query + label.lowercase())
                }
            }
        }

    private fun keyParams(width: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, dp(54)).apply {
            marginEnd = dp(6)
        }

    private fun loadCatalog() {
        progress.visibility = View.VISIBLE
        scope.launch {
            withContext(Dispatchers.IO) { Catalog.ensureSearch(applicationContext) }
            catalogReady = true
            progress.visibility = View.GONE
            render()
            // Prepare the search index while the screen sits idle, so the first
            // letter typed does not have to wait for 58k titles to be normalised.
            withContext(Dispatchers.Default) { ensureIndex() }
        }
    }

    /**
     * Titles the provider added after this app was built only appear once the
     * catalogue is refreshed. It runs behind the UI: searching keeps working against
     * the previous copy while the download happens.
     */
    private fun refreshIfStale() {
        if (refreshing || !CatalogSync.isStale(this)) return
        refreshing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                CatalogSync.refresh(
                    applicationContext,
                    client,
                    onProgress = { label ->
                        scope.launch {
                            if (query.trim().isEmpty()) status.text = "Updating library · $label"
                        }
                    },
                    shouldContinue = { !isFinishing }
                )
            }
            refreshing = false
            if (ok) {
                // Reloading here would hold two catalogues at once on a 1 GB stick;
                // the next screen to open picks the refreshed copy up instead.
                Catalog.invalidate()
                if (query.trim().isEmpty()) status.text = "Library updated"
            } else if (!isFinishing && query.trim().isEmpty()) {
                status.text = "Could not update the library"
            }
        }
    }

    private fun setQuery(value: String) {
        query = value.take(40)
        updateQueryView()
        if (catalogReady) render()
    }

    private fun updateQueryView() {
        if (!::queryView.isInitialized) return
        if (query.isBlank()) {
            queryView.text = "Search titles, channels, people"
            queryView.setTextColor(MarqueeUi.DUST)
        } else {
            queryView.text = query
            queryView.setTextColor(MarqueeUi.INK)
        }
    }

    private fun render() {
        refreshFavorites()
        val text = query.trim()
        if (text.isEmpty()) {
            renderTrending()
            return
        }

        // Matching 58k titles takes a moment; keep it off the UI thread so typing
        // stays responsive, and drop results the user has already typed past.
        val token = ++renderToken
        status.text = "Searching…"
        scope.launch {
            val sections = withContext(Dispatchers.Default) { filter(text) }
            if (token != renderToken || isFinishing) return@launch
            showSections(text, sections)
        }
    }

    private fun renderTrending() {
        header.text = "Trending at home"
        status.text = "Type to search live TV, movies and series"
        renderRecents()
        val trending = Catalog.vodSections.flatMap { it.items }
            .take(gridColumns * 3)
            .map { item ->
                val result = SearchResult(item, MODE_VOD)
                SearchRow.Item(result, isFavorite(result))
            }
        adapter.submit(trending)
    }

    private fun showSections(text: String, sections: List<Pair<String, List<SearchResult>>>) {
        recentRow.visibility = View.GONE
        header.text = "Results for “$text”"
        val total = sections.sumOf { it.second.size }
        status.text = when {
            total == 0 -> "No matches. Try a shorter title, or a channel name."
            else -> "$total ${if (total == 1) "result" else "results"}"
        }
        adapter.submit(
            sections.flatMap { (title, results) ->
                if (results.isEmpty()) {
                    emptyList()
                } else {
                    listOf(SearchRow.Header(title, results.size)) +
                        results.map { SearchRow.Item(it, isFavorite(it)) }
                }
            }
        )
    }

    /** One read per render, not one per result. */
    private fun refreshFavorites() {
        val profile = ProfileStore.activeId(this)
        favoriteKeys = FavoriteStore.all(this, profile)
            .map { "${it.mode}:${it.id}" }
            .toHashSet()
    }

    private fun isFavorite(result: SearchResult): Boolean =
        favoriteKeys.contains("${result.mode}:${result.item.id}")

    private fun renderRecents() {
        val recents = Prefs.recents(this)
        recentRow.removeAllViews()
        recentRow.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        if (recents.isEmpty()) return

        recentRow.addView(
            MarqueeUi.text(this, "RECENT", 11f, MarqueeUi.DUST, MarqueeUi.medium()).apply {
                letterSpacing = 0.14f
                setPadding(0, 0, dp(14), 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        )
        recents.forEach { value ->
            val chip = MarqueeUi.button(this, value).apply {
                setOnClickListener {
                    setQuery(value)
                    resultsView.post { resultsView.requestFocus() }
                }
            }
            recentRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(8)
            })
        }
    }

    /**
     * Accent- and punctuation-insensitive matching, so "greys" finds "Grey's" and
     * "documentarios" finds "Documentários". Prefix hits rank above mid-word hits.
     */
    private fun filter(text: String): List<Pair<String, List<SearchResult>>> {
        val needle = normalise(text)
        if (needle.isEmpty()) return emptyList()
        val index = ensureIndex()
        return listOf(
            "Movies" to rank(index.vod, MODE_VOD, needle),
            "Series" to rank(index.series, MODE_SERIES, needle),
            "Channels" to rank(index.live, MODE_LIVE, needle)
        )
    }

    /**
     * Normalising 58k titles with two regexes each, on every keystroke, is what made
     * typing freeze once search covered the whole library. Do it once, off the UI
     * thread, and keep the prepared names; a catalogue refresh rebuilds it.
     */
    private fun ensureIndex(): SearchIndex {
        val current = index
        if (current != null && current.source === Catalog.searchVodItems) return current

        fun prepare(items: List<Catalog.Item>, mode: Int): List<SearchHit> =
            items.map { item ->
                // Matching runs on the raw name: stripping quality tags and language
                // prefixes costs four regex passes per title and buys nothing here,
                // because a substring search finds "greys" inside "EN - Grey's" anyway.
                val name = normalise(item.name)
                SearchHit(item, mode, 0, name.length, name)
            }

        // Reuse the existing index when only one list changed.
        val vod = current?.vod?.takeIf { current.source === Catalog.searchVodItems }
            ?: prepare(Catalog.searchVodItems, MODE_VOD)
        val series = current?.series?.takeIf { current.seriesSource === Catalog.searchSeriesItems }
            ?: prepare(Catalog.searchSeriesItems, MODE_SERIES)
        val live = current?.live?.takeIf { current.liveSource === Catalog.liveItems }
            ?: prepare(Catalog.liveItems, MODE_LIVE)

        val built = SearchIndex(
            vod = vod,
            series = series,
            live = live,
            source = Catalog.searchVodItems,
            seriesSource = Catalog.searchSeriesItems,
            liveSource = Catalog.liveItems
        )
        index = built
        return built
    }

    /**
     * Keeps the best [LIMIT] matches in order instead of sorting every candidate:
     * a single letter matches tens of thousands of titles, and sorting that list
     * cost more than the scan did.
     */
    private fun rank(entries: List<SearchHit>, mode: Int, needle: String): List<SearchResult> {
        val chosen = ArrayList<SearchHit>(LIMIT)
        var worstAt = Int.MAX_VALUE

        entries.forEach { entry ->
            val at = entry.name.indexOf(needle)
            if (at < 0) return@forEach
            if (chosen.size == LIMIT && at > worstAt) return@forEach

            var low = 0
            var high = chosen.size
            while (low < high) {
                val mid = (low + high) / 2
                val other = chosen[mid]
                if (other.index < at || (other.index == at && other.length <= entry.length)) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }
            chosen.add(low, entry.copy(index = at))
            if (chosen.size > LIMIT) chosen.removeAt(LIMIT)
            worstAt = chosen[chosen.size - 1].index
        }

        return chosen.map { SearchResult(it.item, mode) }
    }

    private fun normalise(value: String): String {
        var plain = true
        var i = 0
        while (i < value.length) {
            if (value[i].code > 127) {
                plain = false
                break
            }
            i++
        }
        if (plain) return asciiFold(value)
        return asciiFold(
            java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replace(MARKS, "")
        )
    }

    /** Lowercase, non-alphanumerics to spaces, no regex and no allocation churn. */
    private fun asciiFold(value: String): String {
        val out = StringBuilder(value.length)
        var space = true
        value.forEach { char ->
            val folded = if (char in 'A'..'Z') char + 32 else char
            val keep = folded in 'a'..'z' || folded in '0'..'9'
            if (keep) {
                out.append(folded)
                space = false
            } else if (!space) {
                out.append(' ')
                space = true
            }
        }
        while (out.isNotEmpty() && out[out.length - 1] == ' ') out.deleteCharAt(out.length - 1)
        return out.toString()
    }

    private fun toggleFavorite(result: SearchResult) {
        val added = FavoriteStore.toggle(
            this,
            ProfileStore.activeId(this),
            result.item,
            result.mode
        )
        android.widget.Toast.makeText(
            this,
            if (added) "Added to My List" else "Removed from My List",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        adapter.notifyDataSetChanged()
    }

    private fun open(result: SearchResult) {
        Prefs.addRecent(this, query)
        when (result.mode) {
            MODE_LIVE -> startActivity(
                PlayerActivity.intent(this, client.liveUrl(result.item.id), result.item.name)
            )
            MODE_VOD -> startActivity(
                PlayerActivity.intent(
                    this,
                    client.vodUrl(result.item.id, result.item.ext),
                    result.item.name,
                    result.item.id,
                    MODE_VOD,
                    result.item.icon,
                    result.item.categoryId
                )
            )
            else -> startActivity(
                SeriesActivity.intent(this, result.item.id, result.item.name)
            )
        }
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val LIMIT = 40
        private val MARKS = Regex("\\p{Mn}+")

        fun intent(context: Context) = Intent(context, SearchActivity::class.java)
    }
}
