package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient
    private lateinit var root: FrameLayout
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var backdrop: ImageView
    private lateinit var playButton: TextView
    private lateinit var listButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var content: LinearLayout
    private var details: XtreamClient.MovieDetails? = null
    private var itemId = ""
    private var itemName = ""
    private var itemIcon = ""
    private var itemExt = ""
    private var itemMode = MODE_VOD
    private var categoryId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this) ?: run {
            finish()
            return
        }
        itemId = intent.getStringExtra(EXTRA_ID).orEmpty()
        itemName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        itemIcon = intent.getStringExtra(EXTRA_ICON).orEmpty()
        itemExt = intent.getStringExtra(EXTRA_EXT).orEmpty()
        itemMode = intent.getIntExtra(EXTRA_MODE, MODE_VOD)
        categoryId = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        if (itemId.isBlank()) {
            finish()
            return
        }
        client = XtreamClient(creds.server, creds.user, creds.pass)
        buildUi()
        load()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(MarqueeUi.GROUND) }
        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val hero = FrameLayout(this)
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.75f
        }
        hero.addView(backdrop, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        hero.addView(View(this).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(MarqueeUi.GROUND, 0xCC0A0E13.toInt(), 0x000A0E13),
                GradientDrawable.Orientation.LEFT_RIGHT
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        hero.addView(View(this).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(0x000A0E13, 0xAA0A0E13.toInt(), MarqueeUi.GROUND),
                GradientDrawable.Orientation.BOTTOM_TOP
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(150), dp(48), dp(28))
        }
        title = MarqueeUi.text(this, Catalog.displayName(itemName), 54f, MarqueeUi.INK, MarqueeUi.display()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(title, LinearLayout.LayoutParams(dp(780), LinearLayout.LayoutParams.WRAP_CONTENT))

        meta = MarqueeUi.text(this, "Movie", 16f, MarqueeUi.SAND, MarqueeUi.medium())
        column.addView(meta, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        plot = MarqueeUi.text(this, "", 18f, MarqueeUi.INK, MarqueeUi.body()).apply {
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        column.addView(plot, LinearLayout.LayoutParams(dp(760), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playButton = MarqueeUi.button(this, "▶  Play", primary = true).apply {
            setOnClickListener { play() }
        }
        listButton = MarqueeUi.button(this, "+  My List").apply {
            setOnClickListener { toggleList() }
        }
        buttons.addView(playButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ))
        buttons.addView(listButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ).apply { marginStart = dp(12) })
        column.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ).apply { topMargin = dp(26) })

        val credits = MarqueeUi.text(this, "", 15f, MarqueeUi.SAND, MarqueeUi.body())
        column.addView(credits, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        hero.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(560)
        ))
        content.addView(hero, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(560)
        ))

        val similar = Catalog.vodSections.firstOrNull { section ->
            section.items.any { it.id == itemId }
        }?.let { section ->
            section.items.filter { it.id != itemId }.take(12)
        }.orEmpty()
        if (similar.isNotEmpty()) {
            content.addView(
                MarqueeRailView(
                    this,
                    "More like this",
                    "Same shelf",
                    similar.map { MarqueeCardItem(it, MODE_VOD) },
                    MarqueeCardKind.LANDSCAPE,
                    onItem = { card ->
                        startActivity(
                            intent(
                                this,
                                card.item.id,
                                card.item.name,
                                card.item.icon,
                                card.item.ext,
                                MODE_VOD,
                                card.item.categoryId
                            )
                        )
                    },
                    onLongItem = { }
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val back = MarqueeUi.button(this, "‹  Back").apply {
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(40)
        ).apply {
            leftMargin = dp(48)
            topMargin = dp(24)
        })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        setContentView(root)
        listButton.text = if (FavoriteStore.isFavorite(this, ProfileStore.activeId(this), itemId, itemMode)) {
            "✓  In My List"
        } else {
            "+  My List"
        }
        val history = HistoryStore.all(this, ProfileStore.activeId(this))
            .firstOrNull { it.id == itemId && it.mode == itemMode }
        if (history != null && history.progress > 0.01f) {
            playButton.text = "▶  Resume"
        }
        playButton.requestFocus()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.movieDetails(itemId) }
            }
            progress.visibility = View.GONE
            result.onSuccess { data ->
                details = data
                bind(data)
            }.onFailure {
                bind(null)
            }
        }
    }

    private fun bind(data: XtreamClient.MovieDetails?) {
        details = data
        val name = data?.name?.takeIf { it.isNotBlank() } ?: itemName
        title.text = Catalog.displayName(name)
        val bits = ArrayList<String>(5)
        val year = data?.releaseDate?.take(4)
            ?: Regex("""(19|20)\d{2}""").find(itemName)?.value
        if (year != null) bits.add(year)
        data?.genre?.takeIf { it.isNotBlank() }?.let { bits.add(it) }
        data?.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { bits.add("★ $it") }
        data?.durationMinutes?.takeIf { it > 0 }?.let { bits.add("${it / 60}h ${it % 60}m") }
        if (bits.isEmpty()) bits.add("Movie")
        meta.text = bits.joinToString("  •  ")
        plot.text = data?.plot?.takeIf { it.isNotBlank() }
            ?: "A hand-picked title from your provider. Press Play to start."
        ImageLoader.load(data?.backdrop?.ifBlank { itemIcon } ?: itemIcon, backdrop)
    }

    private fun play() {
        val position = HistoryStore.all(this, ProfileStore.activeId(this))
            .firstOrNull { it.id == itemId && it.mode == itemMode }
            ?.positionMs ?: 0L
        startActivity(
            PlayerActivity.intent(
                this,
                client.vodUrl(itemId, itemExt),
                Catalog.displayName(itemName),
                itemId,
                itemMode,
                itemIcon,
                categoryId,
                startPositionMs = position
            )
        )
    }

    private fun toggleList() {
        val added = FavoriteStore.toggle(
            this,
            ProfileStore.activeId(this),
            Catalog.Item(itemId, itemName, categoryId, itemIcon, itemExt),
            itemMode
        )
        listButton.text = if (added) "✓  In My List" else "+  My List"
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ICON = "icon"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_CATEGORY = "category"

        fun intent(
            context: Context,
            id: String,
            name: String,
            icon: String,
            ext: String,
            mode: Int,
            categoryId: String
        ): Intent = Intent(context, DetailActivity::class.java).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_ICON, icon)
            putExtra(EXTRA_EXT, ext)
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_CATEGORY, categoryId)
        }
    }
}
