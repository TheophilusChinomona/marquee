package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var plotView: TextView
    private lateinit var backdrop: ImageView
    private lateinit var play: Button
    private lateinit var seasonLabel: TextView
    private lateinit var seasonsRow: LinearLayout
    private lateinit var episodeList: LinearLayout
    private lateinit var progress: View
    private lateinit var status: TextView
    private var details: XtreamClient.SeriesDetails? = null
    private var selectedSeason = 0
    private var fallbackName = ""
    private var seriesId = ""

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

        setContentView(R.layout.activity_series)
        findViewById<android.widget.FrameLayout>(R.id.navHost).addView(
            MarqueeNav(this, "series") { tab ->
                if (tab != "series") MarqueeNav.navigate(this, tab)
            },
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                (80 * resources.displayMetrics.density).toInt()
            )
        )
        titleView = findViewById(R.id.title)
        metaView = findViewById(R.id.meta)
        plotView = findViewById(R.id.plot)
        backdrop = findViewById(R.id.backdrop)
        play = findViewById(R.id.play)
        seasonLabel = findViewById(R.id.season_label)
        seasonsRow = findViewById(R.id.seasons)
        episodeList = findViewById(R.id.episodes)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        play.setOnClickListener { playFirstEpisode() }
        load(seriesId)
    }

    private fun load(seriesId: String) {
        progress.visibility = View.VISIBLE
        status.text = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.seriesDetails(seriesId) }
            }
            progress.visibility = View.GONE
            result.onFailure {
                status.text = "Could not load series: ${it.message}"
                return@launch
            }
            val data = result.getOrThrow()
            details = data
            bindHero(data)
            renderSeasons(data)
            play.requestFocus()
        }
    }

    private fun bindHero(data: XtreamClient.SeriesDetails) {
        titleView.text = Catalog.displayName(
            data.name.ifBlank { fallbackName.ifBlank { "Series" } }
        )
        val year = data.releaseDate.take(4)
        val meta = listOfNotNull(
            year.takeIf { it.length == 4 },
            data.genre.takeIf { it.isNotBlank() },
            data.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }
        )
        metaView.text = meta.joinToString(" • ")
        plotView.text = data.plot
        plotView.visibility = if (data.plot.isBlank()) View.GONE else View.VISIBLE
        ImageLoader.load(data.backdrop.ifBlank { data.poster }, backdrop)
    }

    private fun renderSeasons(data: XtreamClient.SeriesDetails) {
        seasonsRow.removeAllViews()
        if (data.seasons.isEmpty()) {
            status.text = "No episodes found."
            return
        }
        data.seasons.forEachIndexed { index, season ->
            val button = Button(this).apply {
                text = "Season ${season.number}"
                textSize = 15f
                isAllCaps = false
                isFocusable = true
                minWidth = 0
                minimumWidth = 0
                minimumHeight = 0
                stateListAnimator = null
                setPadding(dp(18), 0, dp(18), 0)
                background = ContextCompat.getDrawable(this@SeriesActivity, R.drawable.nav_bg)
                setTextColor(ContextCompat.getColor(this@SeriesActivity, R.color.text_dim))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply { marginEnd = dp(8) }
            }
            button.setOnClickListener { selectSeason(index, focusEpisodes = true) }
            seasonsRow.addView(button)
        }
        selectSeason(0, focusEpisodes = false)
    }

    private fun selectSeason(index: Int, focusEpisodes: Boolean) {
        val data = details ?: return
        if (index !in data.seasons.indices) return
        selectedSeason = index

        for (i in 0 until seasonsRow.childCount) {
            val button = seasonsRow.getChildAt(i) as? Button ?: continue
            val active = i == selectedSeason
            button.isSelected = active
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.accent else R.color.text_dim
                )
            )
        }

        val season = data.seasons[index]
        seasonLabel.text = "Season ${season.number} • ${season.episodes.size} episodes"
        renderEpisodes(season, focusEpisodes)
    }

    private fun renderEpisodes(season: XtreamClient.SeasonDetails, focusEpisodes: Boolean) {
        episodeList.removeAllViews()
        val poster = details?.poster.orEmpty()
        season.episodes.forEach { episode ->
            val row = layoutInflater.inflate(R.layout.item_episode, episodeList, false)
            row.findViewById<TextView>(R.id.number).text = "E${episode.number}"
            row.findViewById<TextView>(R.id.title).text = cleanTitle(episode)
            row.findViewById<TextView>(R.id.meta).text = episodeMeta(episode)
            val plot = row.findViewById<TextView>(R.id.plot)
            plot.text = episode.plot
            plot.visibility = if (episode.plot.isBlank()) View.GONE else View.VISIBLE
            ImageLoader.load(episode.image.ifBlank { poster }, row.findViewById(R.id.image))
            row.setOnClickListener { playEpisode(episode) }
            episodeList.addView(row)
        }
        if (focusEpisodes) {
            episodeList.post { episodeList.getChildAt(0)?.requestFocus() }
        }
    }

    private fun playFirstEpisode() {
        val data = details ?: return
        val season = data.seasons.getOrNull(selectedSeason) ?: return
        season.episodes.firstOrNull()?.let { playEpisode(it) }
    }

    private fun playEpisode(episode: XtreamClient.EpisodeDetails) {
        val data = details
        val category = Catalog.find(MODE_SERIES, seriesId)?.categoryId.orEmpty()
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
                episode = cleanTitle(episode)
            )
        )
    }

    private fun cleanTitle(episode: XtreamClient.EpisodeDetails): String {
        val value = if (episode.title.contains(" - ")) {
            episode.title.substringAfterLast(" - ")
        } else {
            episode.title
        }
        return value.ifBlank { "Episode ${episode.number}" }
    }

    private fun episodeMeta(episode: XtreamClient.EpisodeDetails): String {
        val parts = ArrayList<String>(2)
        durationText(episode)?.let(parts::add)
        val rating = episode.rating.ifBlank { details?.rating.orEmpty() }
        if (rating.isNotBlank() && rating != "0") parts.add("★ $rating")
        return parts.joinToString(" • ")
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
