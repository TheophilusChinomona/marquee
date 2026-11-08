package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient
    private var mode = MODE_LIVE
    private var pendingCategoryId: String? = null
    private lateinit var allowedStreamIds: Set<String>
    private lateinit var allowedCategoryIds: Set<String>

    private lateinit var categoriesView: RecyclerView
    private lateinit var streamsView: RecyclerView
    private lateinit var progress: View
    private lateinit var status: TextView
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var streamAdapter: StreamAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this)
        if (creds == null) {
            finish()
            return
        }
        client = XtreamClient(creds.server, creds.user, creds.pass)
        allowedStreamIds = ScanAllowlist.streamIds(this)
        allowedCategoryIds = ScanAllowlist.categoryIds(this)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_LIVE)
        pendingCategoryId = intent.getStringExtra(EXTRA_CATEGORY)

        setContentView(R.layout.activity_browse)
        categoriesView = findViewById(R.id.categories)
        streamsView = findViewById(R.id.streams)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        val activeTab = when (mode) {
            MODE_LIVE -> "live"
            MODE_VOD -> "movies"
            else -> "series"
        }
        findViewById<FrameLayout>(R.id.navHost).addView(
            MarqueeNav(this, activeTab) { tab ->
                if (tab != activeTab) MarqueeNav.navigate(this, tab)
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (80 * resources.displayMetrics.density).toInt()
            )
        )

        categoriesView.layoutManager = LinearLayoutManager(this)
        configureGrid()
        categoryAdapter = CategoryAdapter { loadStreams(it) }
        streamAdapter = StreamAdapter(
            live = mode == MODE_LIVE,
            isFavorite = { stream ->
                FavoriteStore.isFavorite(
                    this,
                    ProfileStore.activeId(this),
                    stream.id,
                    mode
                )
            },
            onLongClick = { stream -> toggleFavorite(stream) }
        ) { play(it) }
        categoriesView.adapter = categoryAdapter
        streamsView.adapter = streamAdapter

        findViewById<Button>(R.id.tabLive).setOnClickListener { setMode(MODE_LIVE) }
        findViewById<Button>(R.id.tabVod).setOnClickListener { setMode(MODE_VOD) }
        findViewById<Button>(R.id.tabSeries).setOnClickListener { setMode(MODE_SERIES) }
        updateTabs()

        loadCategories()
    }

    private fun setMode(newMode: Int) {
        if (mode == newMode) return
        mode = newMode
        updateTabs()
        configureGrid()
        streamAdapter = StreamAdapter(
            live = mode == MODE_LIVE,
            isFavorite = { stream ->
                FavoriteStore.isFavorite(
                    this,
                    ProfileStore.activeId(this),
                    stream.id,
                    mode
                )
            },
            onLongClick = { stream -> toggleFavorite(stream) }
        ) { play(it) }
        streamsView.adapter = streamAdapter
        status.text = ""
        loadCategories()
    }

    private fun configureGrid() {
        streamsView.layoutManager =
            GridLayoutManager(this, if (mode == MODE_LIVE) 4 else 6)
    }

    private fun updateTabs() {
        val activeText = ContextCompat.getColor(this, R.color.focused_text)
        val idleText = ContextCompat.getColor(this, R.color.text)
        applyTab(findViewById(R.id.tabLive), mode == MODE_LIVE, activeText, idleText)
        applyTab(findViewById(R.id.tabVod), mode == MODE_VOD, activeText, idleText)
        applyTab(findViewById(R.id.tabSeries), mode == MODE_SERIES, activeText, idleText)
    }

    private fun applyTab(
        button: Button,
        selected: Boolean,
        activeText: Int,
        idleText: Int
    ) {
        button.isSelected = selected
        button.setTextColor(if (selected) activeText else idleText)
    }

    private fun loadCategories() {
        progress.visibility = View.VISIBLE
        val action = when (mode) {
            MODE_LIVE -> "get_live_categories"
            MODE_VOD -> "get_vod_categories"
            else -> "get_series_categories"
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.categories(action) } }
            progress.visibility = View.GONE
            result.onFailure {
                status.text = "Could not load categories: ${it.message}"
                return@launch
            }
            var list = result.getOrDefault(emptyList())
            if (mode == MODE_LIVE && allowedCategoryIds.isNotEmpty()) {
                list = list.filter { it.id in allowedCategoryIds }
            }
            android.util.Log.i("FireIPTV", "categories mode=$mode count=${list.size}")
            categoryAdapter.submit(list)
            val target = list.firstOrNull { it.id == pendingCategoryId } ?: list.firstOrNull()
            pendingCategoryId = null
            if (target != null) {
                loadStreams(target)
            } else {
                status.text = getString(R.string.no_streams)
            }
        }
    }

    private fun loadStreams(category: XtreamClient.Category) {
        progress.visibility = View.VISIBLE
        status.text = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (mode) {
                        MODE_LIVE -> {
                            val all = client.streams("get_live_streams", category.id)
                            if (allowedStreamIds.isEmpty()) all
                            else all.filter { it.id in allowedStreamIds }
                        }
                        MODE_VOD -> client.streams("get_vod_streams", category.id)
                        else -> client.series(category.id)
                    }
                }
            }
            progress.visibility = View.GONE
            result.onFailure {
                status.text = "Could not load items: ${it.message}"
                return@launch
            }
            val list = result.getOrDefault(emptyList())
            android.util.Log.i("FireIPTV", "items mode=$mode category=${category.name} count=${list.size}")
            streamAdapter.submit(list)
            status.text = if (list.isEmpty()) getString(R.string.no_streams) else ""
            streamsView.post {
                streamsView.scrollToPosition(0)
                streamsView.layoutManager?.findViewByPosition(0)?.requestFocus()
            }
        }
    }

    private fun toggleFavorite(stream: XtreamClient.Stream) {
        val added = FavoriteStore.toggle(
            this,
            ProfileStore.activeId(this),
            Catalog.Item(stream.id, stream.name, "", stream.icon, stream.ext),
            mode
        )
        android.widget.Toast.makeText(
            this,
            if (added) "Added to favorites" else "Removed from favorites",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun play(stream: XtreamClient.Stream) {
        when (mode) {
            MODE_LIVE -> startActivity(
                PlayerActivity.intent(this, client.liveUrl(stream.id), stream.name)
            )
            MODE_VOD -> startActivity(
                PlayerActivity.intent(
                    this,
                    client.vodUrl(stream.id, stream.ext),
                    stream.name,
                    stream.id,
                    MODE_VOD,
                    stream.icon,
                    ""
                )
            )
            else -> startActivity(SeriesActivity.intent(this, stream.id, stream.name))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_CATEGORY = "category"

        fun intent(context: Context, mode: Int, categoryId: String): Intent =
            Intent(context, BrowseActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_CATEGORY, categoryId)
    }
}
