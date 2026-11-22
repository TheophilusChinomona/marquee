package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var triedAlternate = false
    private var currentUrl: String? = null
    private var itemId = ""
    private var itemMode = -1
    private var itemIcon = ""
    private var itemCategory = ""
    private var itemExt = ""
    private var seriesId = ""
    private var episodeLabel = ""
    private var episodeId = ""
    private val episodes = ArrayList<QueuedEpisode>()
    private var episodeIndex = -1
    private var advancing = false
    private var startPositionMs = 0L
    private var isLive = false

    private lateinit var overlay: FrameLayout
    private lateinit var playPause: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var liveBadge: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private var pickerButton: TextView? = null
    private var controlIndex = 0
    private val controlActions = ArrayList<Pair<View, () -> Unit>>()

    private var episodePanel: FrameLayout? = null
    private var controlsVisible = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (controlsVisible) handler.postDelayed(this, 500L)
        }
    }
    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        // Strip provider decorations ("[MULTI-SUB]", "- 2025") so the overlay
        // and the watch history show the title the rails already show.
        val rawName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        title = Catalog.displayName(rawName).ifBlank { rawName }
        itemId = intent.getStringExtra(EXTRA_ID).orEmpty()
        itemMode = intent.getIntExtra(EXTRA_MODE, -1)
        itemIcon = intent.getStringExtra(EXTRA_ICON).orEmpty()
        itemCategory = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        itemExt = intent.getStringExtra(EXTRA_EXT).orEmpty()
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        episodeLabel = intent.getStringExtra(EXTRA_EPISODE).orEmpty()
        episodeId = intent.getStringExtra(EXTRA_EPISODE_ID).orEmpty()
        episodes.addAll(QueuedEpisode.parse(intent.getStringExtra(EXTRA_EPISODES)))
        if (episodes.isEmpty()) {
            // Older callers only sent what was left to play.
            episodes.addAll(QueuedEpisode.parse(intent.getStringExtra(EXTRA_QUEUE)))
        } else {
            episodeIndex = intent.getIntExtra(EXTRA_INDEX, -1)
        }
        startPositionMs = intent.getLongExtra(EXTRA_START, 0L)
        isLive = itemMode == MODE_LIVE || url.contains("/live/")

        buildOverlay()

        val view = findViewById<PlayerView>(R.id.player_view)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        view.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                updatePlayPause()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateProgress()
                if (playbackState == Player.STATE_ENDED) advanceToNext()
            }
        })
        play(url)
        showControls()
        handler.postDelayed(progressSaver, 10_000L)
    }

    private fun buildOverlay() {
        val root = findViewById<FrameLayout>(R.id.player_root)
        overlay = FrameLayout(this).apply {
            visibility = View.GONE
            clipChildren = false
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = FrameLayout.FOCUS_AFTER_DESCENDANTS
        }
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        overlay.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE6000000.toInt(), 0x00000000)
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(190)
        ).apply { gravity = Gravity.TOP })

        overlay.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF2000000.toInt(), 0x00000000)
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(380)
        ).apply { gravity = Gravity.BOTTOM })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(32), dp(48), 0)
        }
        titleLabel = MarqueeUi.text(
            this,
            title?.toString().orEmpty(),
            22f,
            MarqueeUi.INK,
            MarqueeUi.medium()
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        top.addView(titleLabel, LinearLayout.LayoutParams(0, dp(40), 1f))

        liveBadge = MarqueeUi.badge(this, "LIVE", live = true).apply {
            visibility = if (isLive) View.VISIBLE else View.GONE
        }
        top.addView(liveBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(26)
        ).apply { marginEnd = dp(16) })

        top.addView(Space(this), LinearLayout.LayoutParams(0, 1, 0.01f))

        val back = MarqueeUi.button(this, "‹  Back").apply {
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(120), dp(40)))
        overlay.addView(top, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(90)
        ).apply { gravity = Gravity.TOP })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), 0, dp(48), dp(34))
        }

        seek = SeekBar(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            max = 1000
            progressTintList = ColorStateList.valueOf(MarqueeUi.BULB)
            progressBackgroundTintList = ColorStateList.valueOf(MarqueeUi.BULB_DIM)
            thumbTintList = ColorStateList.valueOf(MarqueeUi.BULB)
            visibility = if (isLive) View.GONE else View.VISIBLE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val duration = player?.duration ?: 0L
                    if (duration > 0L) {
                        player?.seekTo(duration * progress / 1000L)
                        updateProgress()
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    handler.removeCallbacks(hideRunnable)
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    showControls()
                }
            })
        }
        val upcoming = nextEpisode()
        nowPlaying = MarqueeUi.text(
            this,
            currentEpisode?.let { episodeLabelOf(it) }.orEmpty(),
            15f,
            MarqueeUi.INK,
            MarqueeUi.medium()
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (currentEpisode == null) View.GONE else View.VISIBLE
            setPadding(0, dp(6), 0, 0)
        }
        bottom.addView(nowPlaying, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val upNext = MarqueeUi.text(
            this,
            upcoming?.let { "Up next · ${episodeLabelOf(it)}" }.orEmpty(),
            14f,
            MarqueeUi.SAND,
            MarqueeUi.body()
        ).apply {
            visibility = if (upcoming == null) View.GONE else View.VISIBLE
            setPadding(0, dp(2), 0, dp(4))
        }
        bottom.addView(upNext, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        bottom.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
        ))

        timeLabel = MarqueeUi.text(this, "0:00", 15f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            visibility = if (isLive) View.GONE else View.VISIBLE
        }
        bottom.addView(timeLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(22)
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playPause = MarqueeUi.button(this, "▶  Play", primary = true).apply {
            setOnClickListener { togglePlay() }
        }
        controls.addView(playPause, LinearLayout.LayoutParams(dp(170), dp(44)))

        if (episodes.isNotEmpty()) {
            prevButton = MarqueeUi.button(this, "‹  Prev").apply {
                visibility = if (hasPrevious) View.VISIBLE else View.GONE
                setOnClickListener { playEpisodeAt(episodeIndex - 1) }
            }
            controls.addView(prevButton, LinearLayout.LayoutParams(dp(140), dp(44)).apply {
                marginStart = dp(12)
            })

            nextButton = MarqueeUi.button(this, "Next  ›").apply {
                visibility = if (hasNext) View.VISIBLE else View.GONE
                setOnClickListener { playEpisodeAt(episodeIndex + 1) }
            }
            controls.addView(nextButton, LinearLayout.LayoutParams(dp(140), dp(44)).apply {
                marginStart = dp(12)
            })

            if (episodes.size > 1) {
                pickerButton = MarqueeUi.button(this, "Episodes").apply {
                    setOnClickListener { toggleEpisodePanel() }
                }
                controls.addView(pickerButton, LinearLayout.LayoutParams(dp(170), dp(44)).apply {
                    marginStart = dp(12)
                })
            }

        }

        // The row is walked by index rather than by focus: Fire OS drops focus after
        // a seek, and then Prev/Next/Episodes could not be reached at all.
        controlActions.clear()
        controlActions.add(playPause to { togglePlay() })
        if (episodes.isNotEmpty()) {
            // Only the buttons actually on screen: a hidden one used to eat a press.
            if (hasPrevious) controlActions.add(prevButton to { playEpisodeAt(episodeIndex - 1) })
            if (hasNext) controlActions.add(nextButton to { playEpisodeAt(episodeIndex + 1) })
            if (episodes.size > 1) {
                pickerButton?.let { controlActions.add(it to { toggleEpisodePanel() }) }
            }
        }
        bottom.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ).apply { topMargin = dp(8) })

        overlay.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        if (episodes.size > 1) {
            episodePanel = buildEpisodePanel()
            episodePanel?.let { panel ->
                overlay.addView(panel, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        }
    }

    /**
     * Jump straight to another episode, whether the viewer pressed Next, Prev or
     * picked one from the list. The player handles a single episode, so a fresh
     * activity takes over, carrying the whole list so the buttons stay available.
     */
    private fun playEpisodeAt(index: Int) {
        if (advancing) return
        val target = episodes.getOrNull(index) ?: return
        advancing = true
        saveProgress()
        startActivity(
            intent(
                this,
                target.url,
                title?.toString().orEmpty(),
                id = itemId,
                mode = itemMode,
                icon = itemIcon,
                categoryId = itemCategory,
                ext = itemExt,
                seriesId = seriesId,
                episode = target.title,
                episodeId = target.id,
                episodesJson = QueuedEpisode.toJson(episodes),
                episodeIndex = index
            )
        )
        finish()
    }

    private fun toggleEpisodePanel() {
        val panel = episodePanel ?: return
        if (panel.visibility == View.VISIBLE) {
            hideEpisodePanel()
            return
        }
        panel.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        val current = panel.findViewWithTag<View>("current")
        (current ?: panel).requestFocus()
    }

    private fun hideEpisodePanel() {
        val panel = episodePanel ?: return
        panel.visibility = View.GONE
        playPause.requestFocus()
        showControls()
    }

    private fun buildEpisodePanel(): FrameLayout {
        val panel = FrameLayout(this).apply {
            visibility = View.GONE
            isFocusable = true
        }
        panel.addView(View(this).apply { setBackgroundColor(0xCC000000.toInt()) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(20), dp(20))
            background = MarqueeUi.fill(this@PlayerActivity, MarqueeUi.VELVET, 14)
        }
        column.addView(MarqueeUi.text(this, "Episodes", 24f, MarqueeUi.INK, MarqueeUi.display()))
        column.addView(MarqueeUi.text(
            this,
            "${episodes.size} in this series",
            14f,
            MarqueeUi.SAND,
            MarqueeUi.body()
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        episodes.forEachIndexed { index, episode ->
            val row = TextView(this).apply {
                text = episodeLabelOf(episode)
                textSize = 16f
                typeface = MarqueeUi.medium()
                setTextColor(MarqueeUi.INK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), dp(11), dp(12), dp(11))
                isFocusable = true
                isFocusableInTouchMode = true
                background = MarqueeUi.focusSelector(
                    this@PlayerActivity,
                    MarqueeUi.VELVET2,
                    MarqueeUi.BULB,
                    8
                )
                if (index == episodeIndex) {
                    tag = "current"
                    text = "▍ ${episodeLabelOf(episode)}"
                }
                setOnClickListener { playEpisodeAt(index) }
            }
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) })
        }

        val scroller = android.widget.ScrollView(this).apply {
            isFocusable = false
            addView(list, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        column.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        panel.addView(column, FrameLayout.LayoutParams(
            dp(560),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END
        ).apply {
            topMargin = dp(24)
            bottomMargin = dp(24)
            marginEnd = dp(24)
        })
        return panel
    }

    private val hasPrevious: Boolean get() = episodeIndex > 0
    private val hasNext: Boolean get() = episodeIndex >= 0 && episodeIndex + 1 < episodes.size
    private val currentEpisode: QueuedEpisode? get() = episodes.getOrNull(episodeIndex)

    private fun nextEpisode(): QueuedEpisode? = episodes.getOrNull(episodeIndex + 1)

    private fun moveControl(delta: Int) {
        if (controlActions.isEmpty()) return
        controlIndex = (controlIndex + delta).coerceIn(0, controlActions.size - 1)
        controlActions[controlIndex].first.requestFocus()
    }

    private fun activateControl() {
        controlActions.getOrNull(controlIndex)?.second?.invoke()
    }

    private fun episodeLabelOf(episode: QueuedEpisode): String {
        val prefix = when {
            episode.season > 0 && episode.number > 0 -> "S${episode.season} E${episode.number}"
            episode.number > 0 -> "E${episode.number}"
            else -> ""
        }
        // Some providers name the episode with its own code ("S01E05");
        // echoing that next to the season/episode number reads as noise.
        val title = episode.title
        val redundant = title.isBlank() ||
            (episode.number > 0 && EPISODE_CODE.matches(title.trim()))
        return when {
            prefix.isBlank() -> title
            redundant -> prefix
            else -> "$prefix · $title"
        }
    }

    private fun play(url: String) {
        currentUrl = url
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            if (startPositionMs > 0L) seekTo(startPositionMs)
            prepare()
            playWhenReady = true
        }
        updatePlayPause()
    }

    /** Roll on when an episode ends. */
    private fun advanceToNext() {
        if (advancing || !hasNext) return
        playEpisodeAt(episodeIndex + 1)
    }

    private fun togglePlay() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        updatePlayPause()
        showControls()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration
        if (duration <= 0L) return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
        updateProgress()
    }

    private fun updatePlayPause() {
        if (!::playPause.isInitialized) return
        playPause.text = if (player?.isPlaying == true) "❚❚  Pause" else "▶  Play"
    }

    private fun updateProgress() {
        if (isLive || !::seek.isInitialized) return
        val exo = player ?: return
        val duration = exo.duration
        if (duration <= 0L) return
        val position = exo.currentPosition.coerceIn(0L, duration)
        seek.progress = (position * 1000L / duration).toInt()
        timeLabel.text = "${formatTime(position)}  /  ${formatTime(duration)}"
    }

    private fun showControls() {
        if (!::overlay.isInitialized) return
        overlay.visibility = View.VISIBLE
        controlsVisible = true
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 4500L)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        if (::playPause.isInitialized) {
            controlIndex = 0
            playPause.post {
                if (controlsVisible) controlActions.firstOrNull()?.first?.requestFocus()
            }
        }
        updatePlayPause()
        updateProgress()
    }

    private fun hideControls() {
        if (!::overlay.isInitialized) return
        overlay.visibility = View.GONE
        controlsVisible = false
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(tickRunnable)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (episodePanel?.visibility == View.VISIBLE) {
                        hideEpisodePanel()
                        return true
                    }
                    if (controlsVisible) {
                        hideControls()
                        return true
                    }
                }

                // The remote's play/pause key is unambiguous.
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlay()
                    return true
                }

                // Select belongs to whatever has focus once the controls are up;
                // otherwise Prev/Next/Episodes could never be pressed.
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (!controlsVisible) {
                        showControls()
                        return true
                    }
                    if (episodePanel?.visibility != View.VISIBLE) {
                        activateControl()
                        return true
                    }
                    // The episode list handles its own selection.
                }

                // Scrubbing while the controls are hidden; once they are up the D-pad
                // walks the buttons instead.
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controlsVisible) {
                        showControls()
                        if (!isLive) seekBy(-10_000L)
                        return true
                    }
                    if (episodePanel?.visibility != View.VISIBLE) {
                        moveControl(-1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controlsVisible) {
                        showControls()
                        if (!isLive) seekBy(10_000L)
                        return true
                    }
                    if (episodePanel?.visibility != View.VISIBLE) {
                        moveControl(1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!controlsVisible) {
                        showControls()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleError(error: PlaybackException) {
        android.util.Log.e("FireIPTV", "playback error ${error.errorCodeName}", error)
        val url = currentUrl ?: return
        if (!triedAlternate) {
            val alternate = when {
                url.endsWith(".ts") -> url.removeSuffix(".ts") + ".m3u8"
                url.endsWith(".m3u8") -> url.removeSuffix(".m3u8") + ".ts"
                else -> null
            }
            if (alternate != null) {
                triedAlternate = true
                play(alternate)
                return
            }
        }
        Toast.makeText(this, "Playback failed: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
    }

    /** One upcoming episode: the player only needs a ready URL and a label. */
    data class QueuedEpisode(
        val id: String,
        val url: String,
        val title: String,
        val season: Int,
        val number: Int
    ) {
        companion object {
            fun parse(raw: String?): List<QueuedEpisode> {
                if (raw.isNullOrBlank()) return emptyList()
                val out = ArrayList<QueuedEpisode>()
                runCatching {
                    val array = org.json.JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val o = array.optJSONObject(i) ?: continue
                        val url = o.optString("url")
                        if (url.isBlank()) continue
                        out.add(
                            QueuedEpisode(
                                id = o.optString("id"),
                                url = url,
                                title = o.optString("title"),
                                season = o.optInt("season"),
                                number = o.optInt("number")
                            )
                        )
                    }
                }
                return out
            }

            fun toJson(items: List<QueuedEpisode>): String {
                val array = org.json.JSONArray()
                items.forEach { item ->
                    array.put(
                        org.json.JSONObject()
                            .put("id", item.id)
                            .put("url", item.url)
                            .put("title", item.title)
                            .put("season", item.season)
                            .put("number", item.number)
                    )
                }
                return array.toString()
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    override fun onPause() {
        saveProgress()
        hideControls()
        super.onPause()
    }

    private fun saveProgress() {
        val exo = player ?: return
        if (itemId.isBlank() || itemMode < 0) return
        val duration = exo.duration
        val position = exo.currentPosition
        if (duration <= 0L || position <= 0L) return
        HistoryStore.record(
            context = this,
            profileId = ProfileStore.activeId(this),
            itemId = itemId,
            name = title?.toString().orEmpty(),
            icon = itemIcon,
            ext = itemExt,
            categoryId = itemCategory,
            mode = itemMode,
            seriesId = seriesId,
            episode = episodeLabel,
            episodeId = episodeId,
            positionMs = position,
            durationMs = duration
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveProgress()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)

    companion object {
        private val EPISODE_CODE = Regex("^[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}$")
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ID = "id"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ICON = "icon"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_SERIES_ID = "series_id"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_EPISODE_ID = "episode_id"
        private const val EXTRA_QUEUE = "next_queue"
        private const val EXTRA_EPISODES = "episodes"
        private const val EXTRA_INDEX = "episode_index"
        private const val EXTRA_START = "start_position"

        fun intent(
            context: Context,
            url: String,
            name: String,
            id: String = "",
            mode: Int = -1,
            icon: String = "",
            categoryId: String = "",
            ext: String = "",
            seriesId: String = "",
            episode: String = "",
            episodeId: String = "",
            queueJson: String = "",
            episodesJson: String = "",
            episodeIndex: Int = -1,
            startPositionMs: Long = 0L
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_ICON, icon)
                .putExtra(EXTRA_CATEGORY, categoryId)
                .putExtra(EXTRA_EXT, ext)
                .putExtra(EXTRA_SERIES_ID, seriesId)
                .putExtra(EXTRA_EPISODE, episode)
                .putExtra(EXTRA_EPISODE_ID, episodeId)
                .putExtra(EXTRA_QUEUE, queueJson)
                .putExtra(EXTRA_EPISODES, episodesJson)
                .putExtra(EXTRA_INDEX, episodeIndex)
                .putExtra(EXTRA_START, startPositionMs)
    }
}
