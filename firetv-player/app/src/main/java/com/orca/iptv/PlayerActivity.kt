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
    private var startPositionMs = 0L
    private var isLive = false

    private lateinit var overlay: FrameLayout
    private lateinit var playPause: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var liveBadge: TextView
    private var controlsVisible = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (controlsVisible) handler.postDelayed(this, 500L)
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
        title = intent.getStringExtra(EXTRA_NAME)
        itemId = intent.getStringExtra(EXTRA_ID).orEmpty()
        itemMode = intent.getIntExtra(EXTRA_MODE, -1)
        itemIcon = intent.getStringExtra(EXTRA_ICON).orEmpty()
        itemCategory = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        itemExt = intent.getStringExtra(EXTRA_EXT).orEmpty()
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        episodeLabel = intent.getStringExtra(EXTRA_EPISODE).orEmpty()
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
            }
        })
        play(url)
        showControls()
    }

    private fun buildOverlay() {
        val root = findViewById<FrameLayout>(R.id.player_root)
        overlay = FrameLayout(this).apply {
            visibility = View.GONE
            clipChildren = false
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
            isFocusable = true
            isFocusableInTouchMode = true
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
        bottom.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ).apply { topMargin = dp(8) })

        overlay.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
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
        if (::playPause.isInitialized) playPause.requestFocus()
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
                    if (controlsVisible) {
                        hideControls()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (!controlsVisible) {
                        showControls()
                    } else if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        currentFocus === playPause
                    ) {
                        togglePlay()
                    } else {
                        return super.dispatchKeyEvent(event)
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controlsVisible) {
                        showControls()
                    } else if (!isLive) {
                        seekBy(-10_000L)
                        showControls()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controlsVisible) {
                        showControls()
                    } else if (!isLive) {
                        seekBy(10_000L)
                        showControls()
                    }
                    return true
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
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ID = "id"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ICON = "icon"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_SERIES_ID = "series_id"
        private const val EXTRA_EPISODE = "episode"
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
                .putExtra(EXTRA_START, startPositionMs)
    }
}
