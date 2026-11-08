package com.orca.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class MarqueeCardItem(
    val item: Catalog.Item,
    val mode: Int,
    val progress: Float? = null,
    val badge: String? = null,
    val meta: String? = null,
    val number: Int? = null,
    val positionMs: Long = 0L
)

enum class MarqueeCardKind { POSTER, LANDSCAPE, LIVE, TOP }

/**
 * Cinematic billboard used by Home. It keeps the art full-bleed and pulls the
 * type into a single left column, the way the reference prototype does.
 */
class MarqueeHeroView(
    context: Context,
    private val onPlay: (Catalog.Item) -> Unit,
    private val onDetails: (Catalog.Item) -> Unit,
    private val onList: (Catalog.Item) -> Unit
) : FrameLayout(context) {

    private val image: ImageView
    private val title: TextView
    private val meta: TextView
    private val plot: TextView
    private val reason: TextView
    private val listButton: TextView
    private var item: Catalog.Item? = null
    private var itemMode = MODE_VOD

    init {
        clipChildren = false
        background = GradientDrawable().apply {
            colors = intArrayOf(MarqueeUi.GROUND, MarqueeUi.BOOTH)
            orientation = GradientDrawable.Orientation.TL_BR
        }

        image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.82f
        }
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(View(context).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(MarqueeUi.GROUND, 0xCC0A0E13.toInt(), 0x000A0E13),
                GradientDrawable.Orientation.LEFT_RIGHT
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(View(context).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(0x000A0E13, 0x880A0E13.toInt(), MarqueeUi.GROUND),
                GradientDrawable.Orientation.BOTTOM_TOP
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        reason = MarqueeUi.text(context, "", 16f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(reason, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(48)
            topMargin = dp(104)
        })

        title = MarqueeUi.text(context, "", 44f, MarqueeUi.INK, MarqueeUi.display()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(title, LayoutParams(dp(760), LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(48)
            topMargin = dp(124)
        })

        meta = MarqueeUi.text(context, "", 16f, MarqueeUi.SAND, MarqueeUi.medium()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(meta, LayoutParams(dp(720), LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(48)
            topMargin = dp(236)
        })

        plot = MarqueeUi.text(context, "", 18f, MarqueeUi.INK, MarqueeUi.body()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        addView(plot, LayoutParams(dp(660), LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(48)
            topMargin = dp(266)
        })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val play = MarqueeUi.button(context, "▶  Play", primary = true).apply {
            setOnClickListener { item?.let(onPlay) }
        }
        val details = MarqueeUi.button(context, "Details").apply {
            setOnClickListener { item?.let(onDetails) }
        }
        listButton = MarqueeUi.button(context, "+  My List").apply {
            setOnClickListener { item?.let(onList) }
        }
        buttons.addView(play, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        buttons.addView(details, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(12)
        })
        buttons.addView(listButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(12)
        })

        val dots = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        repeat(3) { index ->
            dots.addView(View(context).apply {
                background = MarqueeUi.fill(
                    context,
                    if (index == 0) MarqueeUi.BULB else MarqueeUi.BULB_DIM,
                    4
                )
            }, LinearLayout.LayoutParams(if (index == 0) dp(24) else dp(8), dp(8)).apply {
                marginEnd = dp(7)
            })
        }
        buttons.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(8)).apply {
            marginStart = dp(24)
        })

        addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)).apply {
            leftMargin = dp(48)
            topMargin = dp(338)
        })
    }

    fun bind(item: Catalog.Item, info: XtreamClient.MovieDetails? = null, mode: Int = MODE_VOD) {
        this.item = item
        this.itemMode = mode
        val displayName = Catalog.displayName(item.name)
        title.text = displayName
        reason.text = "Tonight's feature"
        val pieces = ArrayList<String>(4)
        val year = Regex("""(19|20)\d{2}""").find(displayName)?.value
        if (year != null) pieces.add(year)
        val genre = info?.genre?.takeIf { it.isNotBlank() }
        if (genre != null) pieces.add(genre.split(",").take(2).joinToString(" / ").trim())
        val rating = info?.rating?.takeIf { it.isNotBlank() && it != "0" }
        if (rating != null) pieces.add("★ $rating")
        val duration = info?.durationMinutes?.takeIf { it > 0 }
        if (duration != null) pieces.add("${duration / 60}h ${duration % 60}m")
        if (pieces.isEmpty()) pieces.add("Movie")
        meta.text = pieces.joinToString("  •  ")

        plot.text = info?.plot?.takeIf { it.isNotBlank() }
            ?: "A hand-picked title from your provider. Press Play to start."
        ImageLoader.load(info?.backdrop?.ifBlank { item.icon } ?: item.icon, image)
        listButton.text = if (FavoriteStore.isFavorite(context, ProfileStore.activeId(context), item.id, itemMode)) {
            "✓  In My List"
        } else {
            "+  My List"
        }
    }

    fun setInList(inList: Boolean) {
        listButton.text = if (inList) "✓  In My List" else "+  My List"
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(context, value)
}

class MarqueeRailView(
    context: Context,
    title: String,
    subtitle: String?,
    private val cards: List<MarqueeCardItem>,
    private val kind: MarqueeCardKind,
    private val onItem: (MarqueeCardItem) -> Unit,
    private val onLongItem: (MarqueeCardItem) -> Unit
) : LinearLayout(context) {

    val recycler: RecyclerView

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(8), 0, 0)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(48), 0, dp(48), dp(8))
        }
        header.addView(MarqueeUi.text(context, title, 22f, MarqueeUi.INK, MarqueeUi.medium()))
        if (!subtitle.isNullOrBlank()) {
            header.addView(
                MarqueeUi.text(context, subtitle, 16f, MarqueeUi.SAND, MarqueeUi.body()),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(16)
                }
            )
        }
        addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))

        recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            isFocusable = false
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            setPadding(dp(40), dp(4), dp(40), dp(10))
            adapter = MarqueeCardAdapter(cards, kind, onItem, onLongItem)
        }
        addView(
            recycler,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(
                    when (kind) {
                        MarqueeCardKind.POSTER -> 232
                        MarqueeCardKind.TOP -> 268
                        else -> 142
                    }
                )
            )
        )
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(context, value)
}

private class MarqueeCardAdapter(
    private val cards: List<MarqueeCardItem>,
    private val kind: MarqueeCardKind,
    private val onItem: (MarqueeCardItem) -> Unit,
    private val onLongItem: (MarqueeCardItem) -> Unit
) : RecyclerView.Adapter<MarqueeCardAdapter.Holder>() {

    override fun getItemCount() = cards.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(MarqueeCardView(parent.context, kind, onItem, onLongItem))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.view.bind(cards[position])
    }

    class Holder(val view: MarqueeCardView) : RecyclerView.ViewHolder(view)
}

private class MarqueeCardView(
    context: Context,
    private val kind: MarqueeCardKind,
    private val onItem: (MarqueeCardItem) -> Unit,
    private val onLongItem: (MarqueeCardItem) -> Unit
) : FrameLayout(context) {

    private val image: ImageView
    private val title: TextView
    private val meta: TextView
    private val badge: TextView
    private val ring: View
    private val progress: View
    private var card: MarqueeCardItem? = null

    init {
        val width: Int
        val height: Int
        when (kind) {
            MarqueeCardKind.POSTER -> {
                width = dp(148); height = dp(218)
            }
            MarqueeCardKind.TOP -> {
                width = dp(164); height = dp(250)
            }
            else -> {
                width = dp(208); height = dp(126)
            }
        }
        layoutParams = RecyclerView.LayoutParams(width, height)
        clipChildren = false

        val frame = FrameLayout(context).apply {
            background = MarqueeUi.fill(context, MarqueeUi.VELVET, 8)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })

        image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        frame.addView(View(context).apply {
            background = MarqueeUi.verticalGradient(
                intArrayOf(0x000A0E13, 0x990A0E13.toInt(), 0xE60A0E13.toInt()),
                GradientDrawable.Orientation.BOTTOM_TOP
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        badge = MarqueeUi.badge(context, "NEW").apply {
            visibility = View.GONE
        }
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(22)).apply {
            leftMargin = dp(10)
            topMargin = dp(10)
        })

        if (kind == MarqueeCardKind.TOP) {
            val number = MarqueeUi.text(context, "", 54f, MarqueeUi.BULB, MarqueeUi.display())
            addView(number, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
                topMargin = dp(2)
            })
            number.tag = "number"
        }

        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(9))
        }
        title = MarqueeUi.text(context, "", if (kind == MarqueeCardKind.POSTER) 15f else 16f, MarqueeUi.INK, MarqueeUi.medium()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        meta = MarqueeUi.text(context, "", 13f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        textBlock.addView(title)
        textBlock.addView(meta, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        addView(textBlock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        progress = View(context).apply {
            background = MarqueeUi.fill(context, MarqueeUi.BULB, 2)
            visibility = View.GONE
        }
        addView(progress, LayoutParams(0, dp(3)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(10)
            rightMargin = dp(10)
            bottomMargin = dp(8)
        })

        ring = View(context).apply {
            background = MarqueeUi.fill(context, Color.TRANSPARENT, 10, 2, MarqueeUi.BULB)
            visibility = View.INVISIBLE
            isFocusable = false
        }
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { v, hasFocus ->
            MarqueeUi.scaleOnFocus(v, 1.08f)
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            meta.visibility = if (hasFocus) View.VISIBLE else View.GONE
            if (hasFocus) v.bringToFront()
        }
        setOnClickListener { card?.let(onItem) }
        setOnLongClickListener {
            card?.let(onLongItem)
            true
        }
    }

    fun bind(card: MarqueeCardItem) {
        this.card = card
        title.text = Catalog.displayName(card.item.name)
        meta.text = card.meta ?: when (card.mode) {
            MODE_LIVE -> "Live TV"
            MODE_SERIES -> "Series"
            else -> "Movie"
        }
        ImageLoader.load(card.item.icon, image)
        badge.visibility = if (card.badge != null) View.VISIBLE else View.GONE
        badge.text = card.badge.orEmpty()
        if (card.badge == "LIVE") {
            badge.background = MarqueeUi.fill(context, MarqueeUi.AIR, 4)
            badge.setTextColor(MarqueeUi.GROUND)
        } else {
            badge.background = MarqueeUi.fill(context, MarqueeUi.VELVET2, 4)
            badge.setTextColor(MarqueeUi.INK)
        }
        val number = findViewWithTag<TextView>("number")
        if (number != null) {
            number.text = card.number?.toString().orEmpty()
            number.visibility = if (card.number != null) View.VISIBLE else View.INVISIBLE
        }
        val p = card.progress
        progress.visibility = if (p != null) View.VISIBLE else View.GONE
        if (p != null) {
            progress.post {
                val parentWidth = (parent as? View)?.width ?: width
                val available = (parentWidth - dp(20)).coerceAtLeast(1)
                val params = progress.layoutParams
                params.width = (available * p.coerceIn(0f, 1f)).toInt()
                progress.layoutParams = params
            }
        }
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(context, value)
}
