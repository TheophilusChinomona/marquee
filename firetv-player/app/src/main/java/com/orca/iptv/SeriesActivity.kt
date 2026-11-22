package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Netflix-style series page: a full-bleed backdrop, a single type column with a
 * white Play button, then an "Episodes" rail of landscape episode tiles with a
 * Season dropdown like the TV app. Focus order is wired for the D-pad.
 */
class SeriesActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient

    private lateinit var root: FrameLayout
    private lateinit var backdrop: ImageView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var plotView: TextView
    private lateinit var playButton: TextView
    private lateinit var listButton: TextView
    private lateinit var seasonButton: TextView
    private lateinit var episodeRail: RecyclerView
    private lateinit var episodeTitle: TextView
    private lateinit var episodeMeta: TextView
    private lateinit var episodePlot: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var details: XtreamClient.SeriesDetails? = null
    private var resumeEntry: HistoryEntry? = null
    private var selectedSeason = 0
    private var fallbackName = ""
    private var seriesId = ""
    private var seasonOverlay: FrameLayout? = null
    private val episodeAdapter = EpisodeAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this) ?: run {
            finish()
            return
        }
        val incomingSeriesId = intent.getStringExtra(EXTRA_ID) ?: run {
            finish()
            return
        }
        seriesId = incomingSeriesId
        fallbackName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        client = XtreamClient(creds.server, creds.user, creds.pass)

        buildUi()
        load(seriesId)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(MarqueeUi.GROUND) }

        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFocusable = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(40))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        content.addView(buildHero(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(380)
        ))
        content.addView(buildEpisodesSection(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(View(this).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(MarqueeUi.GROUND, 0xCC000000.toInt(), 0x00000000),
                GradientDrawable.Orientation.TOP_BOTTOM
            )
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(120)))

        root.addView(
            MarqueeNav(this, "series") { tab ->
                if (tab != "series") MarqueeNav.navigate(this, tab)
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(80)
            )
        )

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        status = MarqueeUi.text(this, "", 16f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        setContentView(root)
    }

    private fun buildHero(): FrameLayout {
        val hero = FrameLayout(this)
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.8f
        }
        hero.addView(backdrop, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        hero.addView(View(this).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(MarqueeUi.GROUND, 0xCC000000.toInt(), 0x00000000),
                GradientDrawable.Orientation.LEFT_RIGHT
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        hero.addView(View(this).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(0x00000000, 0xAA000000.toInt(), MarqueeUi.GROUND),
                GradientDrawable.Orientation.BOTTOM_TOP
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), 0, dp(48), dp(26))
        }

        titleView = MarqueeUi.text(this, "", 54f, MarqueeUi.INK, MarqueeUi.display()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(titleView, LinearLayout.LayoutParams(dp(900), LinearLayout.LayoutParams.WRAP_CONTENT))

        metaView = MarqueeUi.text(this, "", 16f, MarqueeUi.SAND, MarqueeUi.medium())
        column.addView(metaView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        plotView = MarqueeUi.text(this, "", 18f, MarqueeUi.INK, MarqueeUi.body()).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        column.addView(plotView, LinearLayout.LayoutParams(dp(820), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playButton = MarqueeUi.button(this, "▶  Play", primary = true).apply {
            id = View.generateViewId()
            setOnClickListener { playFirstEpisode() }
        }
        listButton = MarqueeUi.button(this, "+  My List").apply {
            id = View.generateViewId()
            setOnClickListener { toggleList() }
        }
        buttons.addView(playButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(46)
        ))
        buttons.addView(listButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(46)
        ).apply { marginStart = dp(12) })
        column.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(46)
        ).apply { topMargin = dp(26) })

        hero.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        return hero
    }

    private fun buildEpisodesSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), 0, dp(48), 0)
        }
        header.addView(MarqueeUi.text(this, "Episodes", 26f, MarqueeUi.INK, MarqueeUi.medium()))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        seasonButton = MarqueeUi.text(this, "Season 1  ▾", 16f, MarqueeUi.INK, MarqueeUi.medium()).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(18), 0, dp(18), 0)
            background = MarqueeUi.focusSelector(this@SeriesActivity, 0x00000000, MarqueeUi.INK, 4)
            setTextColor(MarqueeUi.textColors(MarqueeUi.INK, MarqueeUi.GROUND))
            setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.04f) }
            setOnClickListener { showSeasonMenu() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    focusFirstEpisode()
                    true
                } else {
                    false
                }
            }
        }
        header.addView(seasonButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(40)
        ))
        section.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(8), dp(48), dp(6))
        }
        episodeTitle = MarqueeUi.text(this, "", 18f, MarqueeUi.INK, MarqueeUi.medium())
        episodeMeta = MarqueeUi.text(this, "", 13f, MarqueeUi.SAND, MarqueeUi.body())
        episodePlot = MarqueeUi.text(this, "", 14f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        detail.addView(episodeTitle)
        detail.addView(episodeMeta, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) })
        detail.addView(episodePlot, LinearLayout.LayoutParams(dp(1400), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        section.addView(detail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        episodeRail = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(this@SeriesActivity, RecyclerView.HORIZONTAL, false)
            adapter = episodeAdapter
            isFocusable = false
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            setPadding(dp(40), dp(8), dp(40), dp(6))
        }
        section.addView(episodeRail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(190)
        ))
        return section
    }

    private fun load(seriesId: String) {
        progress.visibility = View.VISIBLE
        status.text = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (Online.isOnline(seriesId)) Online.seriesDetails(this@SeriesActivity, seriesId)
                    else client.seriesDetails(seriesId)
                }
            }
            progress.visibility = View.GONE
            val data = result.getOrNull()
            if (data == null) {
                val error = result.exceptionOrNull()
                status.text = if (error != null) {
                    "Could not load series: ${error.message}"
                } else {
                    "Nothing playable for this series right now."
                }
                return@launch
            }
            details = data
            refreshResumeEntry()
            bindHero(data)
            renderSeasons(data)
            updateListButton()
        }
    }

    private fun bindHero(data: XtreamClient.SeriesDetails) {
        titleView.text = Catalog.displayName(
            data.name.ifBlank { fallbackName.ifBlank { "Series" } }
        )
        val year = data.releaseDate.take(4)
        val seasons = when (data.seasons.size) {
            0 -> null
            1 -> "1 season"
            else -> "${data.seasons.size} seasons"
        }
        val meta = listOfNotNull(
            year.takeIf { it.length == 4 },
            seasons,
            data.genre.takeIf { it.isNotBlank() },
            data.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }
        )
        metaView.text = meta.joinToString("  •  ")
        plotView.text = data.plot
        plotView.visibility = if (data.plot.isBlank()) View.GONE else View.VISIBLE
        ImageLoader.load(data.backdrop.ifBlank { data.poster }, backdrop)
    }

    private fun renderSeasons(data: XtreamClient.SeriesDetails) {
        if (data.seasons.isEmpty()) {
            status.text = "No episodes found."
            seasonButton.visibility = View.GONE
            return
        }
        playButton.nextFocusDownId = seasonButton.id
        listButton.nextFocusDownId = seasonButton.id
        seasonButton.nextFocusUpId = playButton.id

        val resume = resumeEntry
        var seasonIndex = 0
        if (resume != null) {
            val index = data.seasons.indexOfFirst { season ->
                season.episodes.any { cleanTitle(it) == resume.episode }
            }
            if (index >= 0) seasonIndex = index
        }
        playButton.text = if (resume != null) "▶  Resume" else "▶  Play"
        selectSeason(seasonIndex, focusEpisodes = false, focusLabel = resume?.episode)
        playButton.requestFocus()
    }

    private fun selectSeason(index: Int, focusEpisodes: Boolean, focusLabel: String? = null) {
        val data = details ?: return
        if (index !in data.seasons.indices) return
        selectedSeason = index
        val season = data.seasons[index]
        seasonButton.text = "Season ${season.number}  ▾"
        if (season.episodes.isEmpty() && Online.isOnline(seriesId)) {
            loadSeason(season.number, focusEpisodes, focusLabel)
            return
        }
        renderEpisodes(season, focusEpisodes, focusLabel)
    }

    /**
     * The online source lists seasons with their episode counts but no episodes, so
     * a show is one request to open and one more per season actually visited.
     */
    private fun loadSeason(number: Int, focusEpisodes: Boolean, focusLabel: String?) {
        status.text = "Loading episodes…"
        episodeAdapter.submit(emptyList(), details?.poster.orEmpty())
        scope.launch {
            val episodes = withContext(Dispatchers.IO) {
                runCatching { Online.seasonEpisodes(this@SeriesActivity, seriesId, number) }.getOrDefault(emptyList())
            }
            if (isFinishing) return@launch
            val data = details ?: return@launch
            if (selectedSeason !in data.seasons.indices) return@launch
            val seasons = data.seasons.map { season ->
                if (season.number == number) season.copy(episodes = episodes) else season
            }
            details = data.copy(seasons = seasons)
            status.text = if (episodes.isEmpty()) "No episodes for this season." else ""
            renderEpisodes(seasons[selectedSeason], focusEpisodes, focusLabel)
        }
    }

    private fun renderEpisodes(
        season: XtreamClient.SeasonDetails,
        focusEpisodes: Boolean,
        focusLabel: String? = null
    ) {
        val poster = details?.poster.orEmpty()
        episodeAdapter.submit(season.episodes, poster)
        val targetIndex = focusLabel?.let { label ->
            season.episodes.indexOfFirst { cleanTitle(it) == label }
        } ?: -1
        updateEpisodeDetail(season.episodes.getOrNull(targetIndex) ?: season.episodes.firstOrNull())
        if (targetIndex >= 0) {
            episodeRail.post { episodeRail.scrollToPosition(targetIndex) }
        }
        if (focusEpisodes && season.episodes.isNotEmpty()) {
            episodeRail.post {
                episodeRail.scrollToPosition(0)
                episodeRail.post { focusFirstEpisode() }
            }
        }
    }

    private fun focusFirstEpisode() {
        val first = episodeRail.layoutManager?.findViewByPosition(0)
        if (first != null) first.requestFocus()
        else {
            episodeRail.scrollToPosition(0)
            episodeRail.post {
                episodeRail.layoutManager?.findViewByPosition(0)?.requestFocus()
            }
        }
    }

    private fun updateEpisodeDetail(episode: XtreamClient.EpisodeDetails?) {
        if (episode == null) {
            episodeTitle.text = ""
            episodeMeta.text = ""
            episodePlot.text = ""
            return
        }
        episodeTitle.text = "${episode.number}. ${cleanTitle(episode)}"
        episodeMeta.text = episodeMeta(episode)
        episodePlot.text = episode.plot
        episodePlot.visibility = if (episode.plot.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showSeasonMenu() {
        val data = details ?: return
        if (data.seasons.isEmpty() || seasonOverlay != null) return

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { closeSeasonMenu() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarqueeUi.fill(this@SeriesActivity, 0xFF141414.toInt(), 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        panel.addView(
            MarqueeUi.text(this, "Season", 13f, MarqueeUi.SAND, MarqueeUi.medium()).apply {
                setPadding(dp(16), dp(6), dp(16), dp(6))
                letterSpacing = 0.12f
            }
        )
        val items = ArrayList<TextView>(data.seasons.size)
        data.seasons.forEachIndexed { index, season ->
            val active = index == selectedSeason
            val item = MarqueeUi.text(
                this,
                "Season ${season.number}",
                18f,
                if (active) MarqueeUi.INK else MarqueeUi.SAND,
                if (active) MarqueeUi.medium() else MarqueeUi.body()
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16), 0, dp(16), 0)
                background = MarqueeUi.focusSelector(this@SeriesActivity, 0x00000000, MarqueeUi.INK, 4)
                setTextColor(
                    MarqueeUi.textColors(
                        if (active) MarqueeUi.INK else MarqueeUi.SAND,
                        MarqueeUi.GROUND
                    )
                )
                setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.02f) }
                setOnClickListener {
                    closeSeasonMenu(restoreFocus = false)
                    selectSeason(index, focusEpisodes = true)
                }
            }
            items.add(item)
            panel.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ))
        }

        scrim.addView(panel, FrameLayout.LayoutParams(
            dp(340),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        seasonOverlay = scrim
        items.getOrNull(selectedSeason)?.requestFocus()
    }

    private fun closeSeasonMenu(restoreFocus: Boolean = true) {
        val overlay = seasonOverlay ?: return
        root.removeView(overlay)
        seasonOverlay = null
        if (restoreFocus) seasonButton.requestFocus()
    }

    private fun toggleList() {
        val data = details ?: return
        val item = Catalog.Item(seriesId, data.name.ifBlank { fallbackName }, "", data.poster, "")
        FavoriteStore.toggle(this, ProfileStore.activeId(this), item, MODE_SERIES)
        updateListButton()
    }

    private fun updateListButton() {
        val inList = FavoriteStore.isFavorite(
            this,
            ProfileStore.activeId(this),
            seriesId,
            MODE_SERIES
        )
        listButton.text = if (inList) "✓  In My List" else "+  My List"
    }

    private fun playFirstEpisode() {
        val data = details ?: return
        val resume = resumeEntry
        if (resume != null) {
            val episode = data.seasons.asSequence()
                .flatMap { it.episodes.asSequence() }
                .firstOrNull { cleanTitle(it) == resume.episode }
            if (episode != null) {
                playEpisode(episode, resume.positionMs)
                return
            }
        }
        val season = data.seasons.getOrNull(selectedSeason) ?: return
        season.episodes.firstOrNull()?.let { playEpisode(it) }
    }

    private fun playEpisode(episode: XtreamClient.EpisodeDetails, startPositionMs: Long = 0L) {
        val data = details
        val category = Catalog.find(MODE_SERIES, seriesId)?.categoryId.orEmpty()
        val position = if (startPositionMs > 0L) {
            startPositionMs
        } else {
            HistoryStore.all(this, ProfileStore.activeId(this))
                .firstOrNull { entry ->
                    entry.mode == MODE_SERIES && entry.id == seriesId &&
                        entry.matchesEpisode(episode.id, cleanTitle(episode))
                }?.positionMs ?: 0L
        }
        if (Online.isOnline(seriesId)) {
            playOnline(episode, position)
            return
        }
        startActivity(
            PlayerActivity.intent(
                this,
                client.seriesUrl(episode.id, episode.ext),
                data?.name?.takeIf { it.isNotBlank() } ?: fallbackName.ifBlank { "Series" },
                id = seriesId,
                mode = MODE_SERIES,
                icon = data?.poster.orEmpty(),
                categoryId = category,
                ext = episode.ext,
                seriesId = seriesId,
                episode = cleanTitle(episode),
                episodeId = episode.id,
                episodesJson = episodeListJson(),
                episodeIndex = episodeIndex(episode),
                startPositionMs = position
            )
        )
    }

    /**
     * An online stream is resolved per play, because the servers hand out signed
     * URLs that expire; resolving a whole series up front would take minutes.
     */
    private fun playOnline(episode: XtreamClient.EpisodeDetails, startPositionMs: Long) {
        val data = details
        status.text = "Finding a stream…"
        progress.visibility = View.VISIBLE
        playButton.isEnabled = false
        scope.launch {
            val playback = withContext(Dispatchers.IO) {
                runCatching { Online.resolve(this@SeriesActivity, seriesId, episode.season, episode.number) }.getOrNull()
            }
            progress.visibility = View.GONE
            playButton.isEnabled = true
            if (isFinishing) return@launch
            if (playback == null) {
                status.text = "No working stream for this episode right now."
                return@launch
            }
            status.text = ""
            startActivity(
                PlayerActivity.intent(
                    this@SeriesActivity,
                    playback.url,
                    data?.name?.takeIf { it.isNotBlank() } ?: fallbackName.ifBlank { "Series" },
                    id = seriesId,
                    mode = MODE_SERIES,
                    icon = data?.poster.orEmpty(),
                    categoryId = "",
                    ext = playback.container,
                    seriesId = seriesId,
                    episode = cleanTitle(episode),
                    episodeId = episode.id,
                    episodesJson = episodeListJson(),
                    episodeIndex = episodeIndex(episode),
                    startPositionMs = startPositionMs
                )
            )
        }
    }

    private fun cleanTitle(episode: XtreamClient.EpisodeDetails): String {
        val value = if (episode.title.contains(" - ")) {
            episode.title.substringAfterLast(" - ")
        } else {
            episode.title
        }
        return value.ifBlank { "Episode ${episode.number}" }
    }


    private fun flatEpisodes(): List<XtreamClient.EpisodeDetails> =
        details?.seasons?.flatMap { it.episodes }.orEmpty()

    private fun episodeIndex(episode: XtreamClient.EpisodeDetails): Int =
        flatEpisodes().indexOfFirst { it.id == episode.id }

    /**
     * The whole series in season order, so the player can offer Previous, Next and
     * an episode list without going back to this page. URLs are resolved here
     * because the player has no provider client.
     */
    private fun episodeListJson(): String {
        val flat = flatEpisodes()
        if (flat.isEmpty()) return ""

        // Provider ids play through a fixed URL; online ones carry the coordinates
        // instead and the player resolves them the moment they are needed.
        val online = Online.isOnline(seriesId)
        val array = org.json.JSONArray()
        flat.forEach { item ->
            array.put(
                org.json.JSONObject()
                    .put("id", item.id)
                    .put("url", if (online) "" else client.seriesUrl(item.id, item.ext))
                    .put("fx", Online.episodeSpec(seriesId, item.season, item.number))
                    .put("title", cleanTitle(item))
                    .put("season", item.season)
                    .put("number", item.number)
            )
        }
        return array.toString()
    }

    private fun episodeMeta(episode: XtreamClient.EpisodeDetails): String {
        val parts = ArrayList<String>(2)
        durationText(episode)?.let(parts::add)
        val rating = episode.rating.ifBlank { details?.rating.orEmpty() }
        if (rating.isNotBlank() && rating != "0") parts.add("★ $rating")
        return parts.joinToString("  •  ")
    }

    private fun durationText(episode: XtreamClient.EpisodeDetails): String? {
        if (episode.durationSeconds > 0) return "${episode.durationSeconds / 60}m"
        val parts = episode.duration.split(":")
        if (parts.size == 3) {
            val hours = parts[0].toIntOrNull() ?: 0
            val minutes = parts[1].toIntOrNull() ?: 0
            val total = hours * 60 + minutes
            if (total > 0) return "${total}m"
        }
        return episode.duration.takeIf { it.isNotBlank() }
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)

    override fun onBackPressed() {
        if (seasonOverlay != null) {
            closeSeasonMenu()
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (details == null) return
        refreshResumeEntry()
        updateListButton()
        updatePlayButtonLabel()
    }

    /**
     * Playback writes history while this screen is in the background, so the resume
     * state has to be re-read on return — otherwise "Play" keeps starting at zero.
     */
    private fun refreshResumeEntry() {
        resumeEntry = HistoryStore.all(this@SeriesActivity, ProfileStore.activeId(this@SeriesActivity))
            .firstOrNull { it.mode == MODE_SERIES && it.id == seriesId && it.episode.isNotBlank() }
    }

    private fun updatePlayButtonLabel() {
        if (!::playButton.isInitialized) return
        val resume = resumeEntry
        playButton.text = if (resume != null) "▶  Resume" else "▶  Play"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private class EpisodeHolder(val tile: EpisodeTileView) : RecyclerView.ViewHolder(tile)

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeHolder>() {

        private var items: List<XtreamClient.EpisodeDetails> = emptyList()
        private var poster: String = ""

        fun submit(list: List<XtreamClient.EpisodeDetails>, poster: String) {
            items = list
            this.poster = poster
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder =
            EpisodeHolder(EpisodeTileView(parent.context))

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            holder.tile.bind(items[position], poster)
        }
    }

    private inner class EpisodeTileView(context: Context) : FrameLayout(context) {

        private val image: ImageView
        private val numberBadge: TextView
        private val titleText: TextView
        private val metaText: TextView
        private val ring: View
        private val progressBar: View

        init {
            layoutParams = RecyclerView.LayoutParams(dp(300), dp(169))
            clipChildren = false

            val frame = FrameLayout(context).apply {
                background = MarqueeUi.fill(context, MarqueeUi.VELVET, 4)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })

            image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            frame.addView(View(context).apply {
                background = MarqueeUi.verticalGradient(
                    intArrayOf(0x00000000, 0x99000000.toInt(), 0xE6000000.toInt()),
                    GradientDrawable.Orientation.BOTTOM_TOP
                )
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            numberBadge = MarqueeUi.badge(context, "E1").apply {
                visibility = View.VISIBLE
                background = MarqueeUi.fill(context, 0xCC000000.toInt(), 3)
                setTextColor(MarqueeUi.INK)
            }
            addView(numberBadge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(24)).apply {
                leftMargin = dp(12)
                topMargin = dp(12)
            })

            val textBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), dp(12))
            }
            titleText = MarqueeUi.text(context, "", 15f, MarqueeUi.INK, MarqueeUi.medium()).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            metaText = MarqueeUi.text(context, "", 12f, MarqueeUi.SAND, MarqueeUi.body()).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textBlock.addView(titleText)
            textBlock.addView(metaText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
            addView(textBlock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            })

            progressBar = View(context).apply {
                background = MarqueeUi.fill(context, MarqueeUi.BULB, 2)
                visibility = View.GONE
            }
            addView(progressBar, LayoutParams(0, dp(3)).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(3)
            })

            ring = View(context).apply {
                background = MarqueeUi.fill(context, Color.TRANSPARENT, 4, 3, MarqueeUi.INK)
                visibility = View.INVISIBLE
                isFocusable = false
            }
            addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun bind(episode: XtreamClient.EpisodeDetails, poster: String) {
            numberBadge.text = "E${episode.number}"
            titleText.text = cleanTitle(episode)
            metaText.text = durationText(episode).orEmpty()
            ImageLoader.load(episode.image.ifBlank { poster }, image)

            val history = HistoryStore.all(this@SeriesActivity, ProfileStore.activeId(this@SeriesActivity))
                .firstOrNull {
                    it.mode == MODE_SERIES && it.id == seriesId && it.episode == cleanTitle(episode)
                }
            val progressValue = history?.progress ?: 0f
            progressBar.visibility = if (progressValue > 0f) View.VISIBLE else View.GONE
            if (progressValue > 0f) {
                progressBar.post {
                    val available = (width - dp(24)).coerceAtLeast(1)
                    val params = progressBar.layoutParams
                    params.width = (available * progressValue.coerceIn(0f, 1f)).toInt()
                    progressBar.layoutParams = params
                }
            }

            nextFocusUpId = seasonButton.id
            setOnClickListener { playEpisode(episode) }
            setOnFocusChangeListener { v, hasFocus ->
                MarqueeUi.scaleOnFocus(v, 1.06f)
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) {
                    updateEpisodeDetail(episode)
                    v.bringToFront()
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "series_id"
        private const val EXTRA_NAME = "series_name"

        fun intent(context: Context, seriesId: String, name: String): Intent =
            Intent(context, SeriesActivity::class.java)
                .putExtra(EXTRA_ID, seriesId)
                .putExtra(EXTRA_NAME, name)
    }
}
