package com.orca.iptv

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient
    private lateinit var root: FrameLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var content: LinearLayout
    private lateinit var nav: MarqueeNav
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var hero: MarqueeHeroView? = null
    private var myListRail: MarqueeRailView? = null
    private var catalogReady = false
    private var requestedTab: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this) ?: run {
            finish()
            return
        }
        client = XtreamClient(creds.server, creds.user, creds.pass)
        requestedTab = intent.getStringExtra(EXTRA_TAB)
        buildUi()
        loadCatalog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab = intent.getStringExtra(EXTRA_TAB)
        if (requestedTab == "list") scrollToMyList()
    }

    override fun onResume() {
        super.onResume()
        if (::nav.isInitialized) nav.refreshProfile()
        if (catalogReady) render()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(MarqueeUi.GROUND)
        }

        scroll = NestedScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(48))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(View(this).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(MarqueeUi.GROUND, 0xCC0A0E13.toInt(), 0x000A0E13),
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            )
            alpha = 0.95f
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(126)))

        nav = MarqueeNav(this, "home") { tab -> onNav(tab) }
        root.addView(nav, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(80)
        ))

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        status = MarqueeUi.text(this, "", 16f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        setContentView(root)
    }

    private fun loadCatalog() {
        progress.visibility = View.VISIBLE
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Catalog.ensureHome(applicationContext) }
                catalogReady = true
                render()
            } catch (error: Exception) {
                status.text = "Could not load catalog: ${error.message}"
                status.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        hero = null
        myListRail = null

        val profileId = ProfileStore.activeId(this)
        val profileName = ProfileStore.active(this).name
        val history = HistoryStore.all(this, profileId)
        val favorites = FavoriteStore.all(this, profileId)

        val featured = Catalog.vodSections.firstOrNull()?.items?.firstOrNull()
            ?: Catalog.seriesSections.firstOrNull()?.items?.firstOrNull()
        if (featured != null) {
            val isSeries = Catalog.vodSections.none { section ->
                section.items.any { it.id == featured.id }
            }
            val mode = if (isSeries) MODE_SERIES else MODE_VOD
            val view = MarqueeHeroView(
                this,
                onPlay = { item ->
                    if (mode == MODE_SERIES) openSeries(item)
                    else playVod(item)
                },
                onDetails = { item ->
                    if (mode == MODE_SERIES) openSeries(item)
                    else openDetails(item)
                },
                onList = { item ->
                    val inList = FavoriteStore.isFavorite(this, profileId, item.id, mode)
                    if (!inList) {
                        FavoriteStore.toggle(this, profileId, item, mode)
                    } else {
                        FavoriteStore.remove(this, profileId, item.id, mode)
                    }
                    hero?.setInList(!inList)
                    if (myListRail != null) render()
                }
            )
            hero = view
            content.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(430)
            ))
            view.bind(featured, null, mode)
            content.addView(View(this), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(10)
            ))
            if (mode == MODE_VOD) loadHeroDetails(featured, view)
        }

        val continueCards = history.mapNotNull { entry ->
            val item = Catalog.find(entry.mode, entry.id) ?: entry.toItem()
            MarqueeCardItem(
                item = item,
                mode = entry.mode,
                progress = entry.progress,
                meta = entry.episode.takeIf { it.isNotBlank() } ?: "Continue watching",
                positionMs = entry.positionMs
            )
        }
        addRail("Continue Watching", null, continueCards, MarqueeCardKind.LANDSCAPE)

        val liveSection = Catalog.liveSections.maxByOrNull { it.items.size }
        val liveCards = liveSection?.items.orEmpty().take(14).map {
            MarqueeCardItem(
                item = it,
                mode = MODE_LIVE,
                badge = "LIVE",
                meta = "Live TV"
            )
        }
        addRail("Live Now", "On now", liveCards, MarqueeCardKind.LIVE)

        val forYou = Recommendations.forYou(this, profileId)
        addRail(
            "For You",
            "Picked for $profileName",
            forYou.map { MarqueeCardItem(it.item, it.mode) },
            MarqueeCardKind.POSTER
        )

        Recommendations.becauseYouWatched(this, profileId)?.let { (title, list) ->
            addRail(
                "Because you watched $title",
                "Same shelf, different night",
                list.map { MarqueeCardItem(it.item, it.mode) },
                MarqueeCardKind.LANDSCAPE
            )
        }

        addRail(
            "Top 10 at home today",
            "Updated for $profileName",
            forYou.take(10).mapIndexed { index, rec ->
                MarqueeCardItem(
                    item = rec.item,
                    mode = rec.mode,
                    number = index + 1
                )
            },
            MarqueeCardKind.TOP
        )

        val newSection = Catalog.vodSections.drop(1).lastOrNull()
            ?: Catalog.vodSections.lastOrNull()
        val newCards = newSection?.items.orEmpty().take(14)
            .map { MarqueeCardItem(it, MODE_VOD, badge = "NEW") }
        addRail("New Releases", null, newCards, MarqueeCardKind.POSTER)

        val listCards = favorites.map {
            MarqueeCardItem(
                item = it.toItem(),
                mode = it.mode,
                badge = "★",
                meta = when (it.mode) {
                    MODE_LIVE -> "Live TV"
                    MODE_SERIES -> "Series"
                    else -> "Movie"
                }
            )
        }
        myListRail = addRail("My List", profileName, listCards, MarqueeCardKind.POSTER)

        if (content.childCount == 0) {
            status.text = "No content available."
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.GONE
        }

        if (requestedTab == "list") {
            content.post { scrollToMyList() }
        }
    }

    private fun addRail(
        title: String,
        subtitle: String?,
        cards: List<MarqueeCardItem>,
        kind: MarqueeCardKind
    ): MarqueeRailView? {
        if (cards.isEmpty()) return null
        val rail = MarqueeRailView(
            this,
            title,
            subtitle,
            cards,
            kind,
            onItem = { openCard(it) },
            onLongItem = { toggleFavorite(it) }
        )
        content.addView(rail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return rail
    }

    private fun openCard(card: MarqueeCardItem) {
        when (card.mode) {
            MODE_LIVE -> startActivity(
                PlayerActivity.intent(this, client.liveUrl(card.item.id), card.item.name)
            )
            MODE_SERIES -> openSeries(card.item)
            else -> playVod(card.item, card.positionMs)
        }
    }

    private fun openSeries(item: Catalog.Item) {
        startActivity(SeriesActivity.intent(this, item.id, item.name))
    }

    private fun playVod(item: Catalog.Item, startPositionMs: Long = 0L) {
        startActivity(
            PlayerActivity.intent(
                this,
                client.vodUrl(item.id, item.ext),
                item.name,
                item.id,
                MODE_VOD,
                item.icon,
                item.categoryId,
                startPositionMs = startPositionMs
            )
        )
    }

    private fun openDetails(item: Catalog.Item) {
        startActivity(DetailActivity.intent(this, item.id, item.name, item.icon, item.ext, MODE_VOD, item.categoryId))
    }

    private fun toggleFavorite(card: MarqueeCardItem) {
        val profileId = ProfileStore.activeId(this)
        val added = FavoriteStore.toggle(this, profileId, card.item, card.mode)
        Toast.makeText(
            this,
            if (added) "Added to My List" else "Removed from My List",
            Toast.LENGTH_SHORT
        ).show()
        hero?.setInList(FavoriteStore.all(this, profileId).any { it.id == card.item.id && it.mode == card.mode })
        if (myListRail != null) render()
    }

    private fun loadHeroDetails(item: Catalog.Item, view: MarqueeHeroView) {
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { client.movieDetails(item.id) }.getOrNull()
            }
            if (info != null && hero === view) view.bind(item, info, MODE_VOD)
        }
    }

    private fun onNav(tab: String) {
        when (tab) {
            "home" -> scroll.smoothScrollTo(0, 0)
            "list" -> scrollToMyList()
            else -> MarqueeNav.navigate(this, tab)
        }
    }

    private fun scrollToMyList() {
        val rail = myListRail ?: return
        scroll.post { scroll.smoothScrollTo(0, rail.top) }
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TAB = "marquee_tab"

        fun intent(context: android.content.Context, tab: String? = null): Intent =
            Intent(context, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (tab != null) putExtra(EXTRA_TAB, tab)
            }
    }
}
