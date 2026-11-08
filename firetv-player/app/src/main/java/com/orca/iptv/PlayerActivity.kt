package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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

        val view = findViewById<PlayerView>(R.id.player_view)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        view.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })
        play(url)
    }

    private fun play(url: String) {
        currentUrl = url
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            if (startPositionMs > 0L) seekTo(startPositionMs)
            prepare()
            playWhenReady = true
        }
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

    override fun onPause() {
        saveProgress()
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
        saveProgress()
        player?.release()
        player = null
        super.onDestroy()
    }

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
