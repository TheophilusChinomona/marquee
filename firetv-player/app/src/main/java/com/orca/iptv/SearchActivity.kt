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
    private data class SearchHit(
        val item: Catalog.Item,
        val mode: Int,
        val index: Int,
        val length: Int
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
        val text = query.trim()
        if (text.isEmpty()) {
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
            return
        }

        recentRow.visibility = View.GONE
        val sections = filter(text)
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

    private fun isFavorite(result: SearchResult): Boolean =
        FavoriteStore.isFavorite(this, ProfileStore.activeId(this), result.item.id, result.mode)

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
        return listOf(
            "Movies" to rank(Catalog.searchVodItems, MODE_VOD, needle),
            "Series" to rank(Catalog.searchSeriesItems, MODE_SERIES, needle),
            "Channels" to rank(Catalog.liveItems, MODE_LIVE, needle)
        )
    }

    private fun rank(items: List<Catalog.Item>, mode: Int, needle: String): List<SearchResult> =
        items.asSequence()
            .mapNotNull { item ->
                val name = normalise(Catalog.displayName(item.name))
                val at = name.indexOf(needle)
                if (at < 0) null else SearchHit(item, mode, at, name.length)
            }
            .sortedWith(compareBy({ it.index }, { it.length }))
            .take(40)
            .map { SearchResult(it.item, it.mode) }
            .toList()

    private fun normalise(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()
            .replace(NON_ALNUM, " ")
            .trim()

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
        private val MARKS = Regex("\\p{Mn}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        fun intent(context: Context) = Intent(context, SearchActivity::class.java)
    }
}
