package com.orca.iptv

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAB_HOME = 0
private const val TAB_LIVE = 1
private const val TAB_MOVIES = 2
private const val TAB_SERIES = 3
private const val TAB_FAVORITES = 4

class HomeActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient
    private lateinit var rows: LinearLayout
    private lateinit var progress: View
    private lateinit var status: TextView
    private lateinit var navHome: Button
    private lateinit var navLive: Button
    private lateinit var navMovies: Button
    private lateinit var navSeries: Button
    private lateinit var navFavorites: Button
    private lateinit var navSearch: Button
    private lateinit var navProfile: Button
    private var tab = TAB_HOME
    private var activeProfileId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this) ?: run {
            finish()
            return
        }
        client = XtreamClient(creds.server, creds.user, creds.pass)

        setContentView(R.layout.activity_home)
        rows = findViewById(R.id.rows)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        navHome = findViewById(R.id.navHome)
        navLive = findViewById(R.id.navLive)
        navMovies = findViewById(R.id.navMovies)
        navSeries = findViewById(R.id.navSeries)
        navFavorites = findViewById(R.id.navFavorites)
        navSearch = findViewById(R.id.navSearch)
        navProfile = findViewById(R.id.navProfile)

        navHome.setOnClickListener { selectTab(TAB_HOME) }
        navLive.setOnClickListener { selectTab(TAB_LIVE) }
        navMovies.setOnClickListener { selectTab(TAB_MOVIES) }
        navSeries.setOnClickListener { selectTab(TAB_SERIES) }
        navFavorites.setOnClickListener { selectTab(TAB_FAVORITES) }
        navSearch.setOnClickListener { startActivity(SearchActivity.intent(this)) }
        navProfile.setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }

        activeProfileId = ProfileStore.activeId(this)
        selectTab(TAB_HOME)
    }

    override fun onResume() {
        super.onResume()
        if (!::navProfile.isInitialized) return
        val active = ProfileStore.activeId(this)
        navProfile.text = ProfileStore.active(this).name.uppercase()
        if (active != activeProfileId) {
            activeProfileId = active
            selectTab(tab)
        }
    }

    private fun selectTab(newTab: Int) {
        tab = newTab
        updateNav()
        render()
    }

    private fun render() {
        progress.visibility = View.VISIBLE
        status.text = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Catalog.ensureHome(applicationContext) }
                android.util.Log.i(
                    "FireIPTV",
                    "home catalog live=${Catalog.liveSections.size} vod=${Catalog.vodSections.size} series=${Catalog.seriesSections.size}"
                )
                buildRows()
            } catch (error: Exception) {
                status.text = "Could not load catalog: ${error.message}"
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun buildRows() {
        rows.removeAllViews()
        when (tab) {
            TAB_HOME -> {
                Catalog.vodSections.firstOrNull()?.let {
                    addGroup("FEATURED", listOf(it), MODE_VOD, large = true)
                }
                addGroup(
                    "LIVE TV",
                    Catalog.liveSections.sortedByDescending { it.items.size }.take(4),
                    MODE_LIVE
                )
                val movieSections = Catalog.vodSections.drop(1).ifEmpty {
                    Catalog.vodSections
                }
                addGroup("MOVIES", movieSections.take(6), MODE_VOD)
                addGroup("SERIES", Catalog.seriesSections.take(4), MODE_SERIES)
            }
            TAB_LIVE -> addGroup(
                "LIVE TV",
                Catalog.liveSections.sortedByDescending { it.items.size }.take(15),
                MODE_LIVE
            )
            TAB_MOVIES -> addGroup("MOVIES", Catalog.vodSections.take(15), MODE_VOD)
            TAB_SERIES -> addGroup("SERIES", Catalog.seriesSections, MODE_SERIES)
            TAB_FAVORITES -> addFavorites()
        }
        if (rows.childCount == 0) {
            status.text = "No content available."
        }
    }

    private fun addGroup(
        title: String,
        sections: List<Catalog.Section>,
        mode: Int,
        large: Boolean = false
    ) {
        if (sections.isEmpty()) return
        rows.addView(headerView(title))
        sections.forEach { addSection(it, mode, large) }
    }

    private fun addSection(section: Catalog.Section, mode: Int, large: Boolean = false) {
        val isLive = mode == MODE_LIVE
        val label = TextView(this).apply {
            text = Catalog.displayName(section.name)
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.text))
            textSize = 20f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(dp(48), dp(20), dp(48), dp(8))
        }
        rows.addView(label)

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity, RecyclerView.HORIZONTAL, false)
            setHasFixedSize(true)
            clipToPadding = false
            clipChildren = false
            setPadding(dp(40), dp(8), dp(40), dp(12))
        }
        val items = section.items.take(if (large) 8 else 20)
        val profileId = ProfileStore.activeId(this)
        val adapter = CardAdapter(
            items = items,
            live = isLive,
            largePoster = large,
            isFavorite = { FavoriteStore.isFavorite(this, profileId, it.id, mode) },
            onLongClick = { toggleFavorite(it, mode) }
        ) { item ->
            open(item, mode)
        }
        if (!large) {
            adapter.onSeeAll = {
                startActivity(BrowseActivity.intent(this, mode, section.id))
            }
        }
        recycler.adapter = adapter

        val height = when {
            large -> dp(430)
            isLive -> dp(200)
            else -> dp(300)
        }
        rows.addView(
            recycler,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
        )
    }

    private fun open(item: Catalog.Item, mode: Int) {
        when (mode) {
            MODE_LIVE -> startActivity(
                PlayerActivity.intent(this, client.liveUrl(item.id), item.name)
            )
            MODE_VOD -> startActivity(
                PlayerActivity.intent(this, client.vodUrl(item.id, item.ext), item.name)
            )
            MODE_SERIES -> startActivity(SeriesActivity.intent(this, item.id, item.name))
        }
    }

    private fun addFavorites() {
        val profileId = ProfileStore.activeId(this)
        val favorites = FavoriteStore.all(this, profileId)
        rows.addView(headerView("FAVORITES"))
        if (favorites.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No favorites yet.\nLong-press any card to add it."
                setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.text_dim))
                textSize = 18f
                setPadding(dp(48), dp(4), dp(48), dp(48))
            }
            rows.addView(empty)
            return
        }
        favorites.forEach { favorite ->
            val row = layoutInflater.inflate(R.layout.item_result, rows, false)
            row.findViewById<TextView>(R.id.name).text =
                Catalog.displayName(favorite.name)
            row.findViewById<TextView>(R.id.subtitle).text = when (favorite.mode) {
                MODE_LIVE -> "LIVE TV"
                MODE_VOD -> "MOVIE"
                else -> "SERIES"
            }
            ImageLoader.load(favorite.icon, row.findViewById(R.id.image))
            row.setOnClickListener { open(favorite.toItem(), favorite.mode) }
            row.setOnLongClickListener {
                FavoriteStore.remove(this, profileId, favorite.id, favorite.mode)
                Toast.makeText(
                    this,
                    "Removed from favorites",
                    Toast.LENGTH_SHORT
                ).show()
                render()
                true
            }
            rows.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(48)
                    rightMargin = dp(48)
                    bottomMargin = dp(8)
                }
            )
        }
    }

    private fun toggleFavorite(item: Catalog.Item, mode: Int) {
        val added = FavoriteStore.toggle(
            this,
            ProfileStore.activeId(this),
            item,
            mode
        )
        Toast.makeText(
            this,
            if (added) "Added to favorites" else "Removed from favorites",
            Toast.LENGTH_SHORT
        ).show()
        if (tab == TAB_FAVORITES) render()
    }

    private fun headerView(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.text))
        textSize = 32f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        letterSpacing = -0.02f
        setPadding(dp(48), dp(34), dp(48), dp(6))
    }

    private fun updateNav() {
        val active = ContextCompat.getColor(this, R.color.accent)
        val idle = ContextCompat.getColor(this, R.color.text_dim)
        navHome.setTextColor(if (tab == TAB_HOME) active else idle)
        navLive.setTextColor(if (tab == TAB_LIVE) active else idle)
        navMovies.setTextColor(if (tab == TAB_MOVIES) active else idle)
        navSeries.setTextColor(if (tab == TAB_SERIES) active else idle)
        navFavorites.setTextColor(if (tab == TAB_FAVORITES) active else idle)
        navSearch.setTextColor(idle)
        navProfile.setTextColor(ContextCompat.getColor(this, R.color.text))
        navProfile.text = ProfileStore.active(this).name.uppercase()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
