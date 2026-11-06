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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        title = intent.getStringExtra(EXTRA_NAME)

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

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, url: String, name: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, name)
    }
}
