package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Live TV guide: every channel from the catalog (virtualized rows), a category
 * picker, channel logos, and a synced timeline. EPG is fetched lazily per row so
 * thousands of channels stay cheap.
 */
class LiveGuideActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient

    private lateinit var root: FrameLayout
    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var categoryButton: TextView
    private lateinit var countLabel: TextView

    private val adapter = RowAdapter()
    private val syncedScrollers = LinkedHashSet<HorizontalScrollView>()
    private val epgCache = HashMap<String, List<XtreamClient.EpgProgram>>()
    private val epgInFlight = HashSet<String>()
    private val epgSemaphore = Semaphore(6)

    private var sections: List<Catalog.Section> = emptyList()
    private var allChannels: List<Catalog.Item> = emptyList()
    private var currentCategory: Catalog.Section? = null
    private var categoryOverlay: FrameLayout? = null
    private var sharedScrollX = 0
    private var syncing = false

    private var windowStart = 0L
    private var windowEnd = 0L
    private var now = 0L
    private val minutes = 180
    private val dpPerMinute = 10
    private val channelCellWidth = 280

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this) ?: run {
            finish()
            return
        }
        client = XtreamClient(creds.server, creds.user, creds.pass)
        now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val minute = get(Calendar.MINUTE)
            add(Calendar.MINUTE, -(minute % 30) - 30)
        }
        windowStart = cal.timeInMillis
        windowEnd = windowStart + minutes * 60_000L
        buildUi()
        load()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(MarqueeUi.GROUND) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        content.addView(
            MarqueeNav(this, "live") { tab ->
                if (tab != "live") MarqueeNav.navigate(this, tab)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80))
        )

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), 0, dp(48), 0)
        }
        toolbar.addView(MarqueeUi.text(this, "Live TV", 26f, MarqueeUi.INK, MarqueeUi.medium()))
        countLabel = MarqueeUi.text(this, "", 15f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            setPadding(dp(14), 0, 0, 0)
        }
        toolbar.addView(countLabel)
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        categoryButton = MarqueeUi.text(this, "All channels  ▾", 16f, MarqueeUi.INK, MarqueeUi.medium()).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(18), 0, dp(18), 0)
            background = MarqueeUi.focusSelector(this@LiveGuideActivity, 0x00000000, MarqueeUi.INK, 4)
            setTextColor(MarqueeUi.textColors(MarqueeUi.INK, MarqueeUi.GROUND))
            setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.04f) }
            setOnClickListener { showCategoryMenu() }
        }
        toolbar.addView(categoryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(40)
        ))
        content.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(58)
        ))

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 0, 0, 0)
        }
        headerRow.addView(
            MarqueeUi.text(this, "CHANNEL", 12f, MarqueeUi.DUST, MarqueeUi.medium()).apply {
                letterSpacing = 0.12f
                setPadding(dp(48), 0, 0, dp(8))
            },
            LinearLayout.LayoutParams(dp(channelCellWidth), dp(40), 0f)
        )
        val headerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
        }
        val headerCanvas = FrameLayout(this)
        addTimeLabels(headerCanvas)
        headerScroll.addView(headerCanvas, FrameLayout.LayoutParams(
            dp(minutes * dpPerMinute),
            dp(40)
        ))
        headerRow.addView(headerScroll, LinearLayout.LayoutParams(0, dp(40), 1f))
        registerScroller(headerScroll)

        content.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(40)
        ))

        content.addView(View(this).apply {
            setBackgroundColor(0xFF1F1F1F.toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveGuideActivity)
            adapter = this@LiveGuideActivity.adapter
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, dp(48))
        }
        content.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        categoryButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                val holder = list.findViewHolderForAdapterPosition(0) as? RowHolder
                if (holder != null) holder.channelCell.requestFocus()
                else list.post { focusFirstChannel() }
                true
            } else {
                false
            }
        }

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        status = MarqueeUi.text(this, "", 18f, MarqueeUi.SAND, MarqueeUi.body()).apply {
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

    private fun addTimeLabels(canvas: FrameLayout) {
        var i = 0
        while (i * 30 <= minutes) {
            val label = MarqueeUi.text(
                this,
                formatTime(windowStart + i * 30 * 60_000L),
                13f,
                MarqueeUi.SAND,
                MarqueeUi.medium()
            )
            canvas.addView(label, FrameLayout.LayoutParams(dp(90), dp(40)).apply {
                leftMargin = dp(i * 30 * dpPerMinute)
                gravity = Gravity.CENTER_VERTICAL
            })
            i++
        }
        val nowX = ((now - windowStart) / 60_000L).toInt() * dpPerMinute
        canvas.addView(View(this).apply {
            setBackgroundColor(MarqueeUi.BULB)
        }, FrameLayout.LayoutParams(dp(2), dp(40)).apply { leftMargin = dp(nowX) })
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Catalog.ensureHome(applicationContext) }
                sections = Catalog.liveSections
                allChannels = sections.asSequence()
                    .flatMap { it.items.asSequence() }
                    .distinctBy { it.id }
                    .toList()
                adapter.submit(allChannels)
                updateCategoryLabel()
                progress.visibility = View.GONE
                if (allChannels.isEmpty()) {
                    status.text = "No live channels found."
                    status.visibility = View.VISIBLE
                } else {
                    list.post { focusFirstChannel() }
                }
            } catch (error: Exception) {
                status.text = "Could not load guide: ${error.message}"
                status.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun focusFirstChannel() {
        list.scrollToPosition(0)
        list.post {
            (list.findViewHolderForAdapterPosition(0) as? RowHolder)?.channelCell?.requestFocus()
        }
    }

    private fun applyCategory(section: Catalog.Section?) {
        currentCategory = section
        val channels = section?.items?.distinctBy { it.id } ?: allChannels
        adapter.submit(channels)
        updateCategoryLabel()
        list.scrollToPosition(0)
        list.post { focusFirstChannel() }
    }

    private fun updateCategoryLabel() {
        val name = currentCategory?.name ?: "All channels"
        categoryButton.text = "${CategoryNames.parse(name).line}  ▾"
        val shown = adapter.itemCount
        countLabel.text = if (currentCategory == null) {
            "$shown channels"
        } else {
            "$shown channels"
        }
    }

    private fun showCategoryMenu() {
        if (categoryOverlay != null) return
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { closeCategoryMenu() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarqueeUi.fill(this@LiveGuideActivity, 0xFF141414.toInt(), 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        panel.addView(
            MarqueeUi.text(this, "Category", 13f, MarqueeUi.SAND, MarqueeUi.medium()).apply {
                setPadding(dp(16), dp(6), dp(16), dp(6))
                letterSpacing = 0.12f
            }
        )
        val scroll = ScrollView(this).apply { isFocusable = false }
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(items, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(420)
        ))

        val options = ArrayList<Pair<String, Catalog.Section?>>()
        options.add("All channels" to null)
        sections
            .map { it to CategoryNames.parse(it.name) }
            .sortedWith(compareBy({ CategoryNames.sortKey(it.second.chip) }, { it.second.shelf }))
            .forEach { (section, label) -> options.add(label.line to section) }

        val views = ArrayList<TextView>(options.size)
        var lastChip: String? = null
        options.forEach { (label, section) ->
            // Language headers keep 140 raw categories scannable.
            val chip = section?.let { CategoryNames.parse(it.name).chip } ?: CategoryLabel.ALL
            if (chip != lastChip) {
                lastChip = chip
                items.addView(
                    MarqueeUi.text(this, chip.uppercase(), 11f, MarqueeUi.DUST, MarqueeUi.medium()).apply {
                        letterSpacing = 0.14f
                        setPadding(dp(16), dp(10), dp(16), dp(4))
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            val active = if (section == null) currentCategory == null else currentCategory?.id == section.id
            val item = MarqueeUi.text(
                this,
                label,
                17f,
                if (active) MarqueeUi.INK else MarqueeUi.SAND,
                if (active) MarqueeUi.medium() else MarqueeUi.body()
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(16), 0, dp(16), 0)
                background = MarqueeUi.focusSelector(this@LiveGuideActivity, 0x00000000, MarqueeUi.INK, 4)
                setTextColor(
                    MarqueeUi.textColors(
                        if (active) MarqueeUi.INK else MarqueeUi.SAND,
                        MarqueeUi.GROUND
                    )
                )
                setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.02f) }
                setOnClickListener {
                    closeCategoryMenu(restoreFocus = false)
                    applyCategory(section)
                }
            }
            views.add(item)
            items.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ))
        }

        scrim.addView(panel, FrameLayout.LayoutParams(
            dp(420),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        categoryOverlay = scrim
        val index = options.indexOfFirst { (_, section) ->
            if (section == null) currentCategory == null else currentCategory?.id == section.id
        }
        views.getOrNull(index.coerceAtLeast(0))?.requestFocus()
    }

    private fun closeCategoryMenu(restoreFocus: Boolean = true) {
        val overlay = categoryOverlay ?: return
        root.removeView(overlay)
        categoryOverlay = null
        if (restoreFocus) categoryButton.requestFocus()
    }

    private fun registerScroller(scroller: HorizontalScrollView) {
        syncedScrollers.add(scroller)
        scroller.scrollTo(sharedScrollX, 0)
        scroller.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (syncing) return@setOnScrollChangeListener
            syncing = true
            sharedScrollX = scrollX
            syncedScrollers.forEach { other ->
                if (other !== scroller) other.scrollTo(scrollX, 0)
            }
            syncing = false
        }
    }

    private fun unregisterScroller(scroller: HorizontalScrollView) {
        syncedScrollers.remove(scroller)
    }

    private fun ensureEpg(channel: Catalog.Item) {
        if (epgCache.containsKey(channel.id) || !epgInFlight.add(channel.id)) return
        scope.launch {
            val listings = withContext(Dispatchers.IO) {
                epgSemaphore.withPermit {
                    runCatching { client.shortEpg(channel.id, 12) }.getOrDefault(emptyList())
                }
            }
            epgCache[channel.id] = listings
            epgInFlight.remove(channel.id)
            val index = adapter.indexOf(channel.id)
            if (index >= 0) adapter.notifyItemChanged(index)
        }
    }

    private fun renderCanvas(canvas: FrameLayout, channel: Catalog.Item) {
        canvas.removeAllViews()
        val cached = epgCache[channel.id]
        if (cached == null) {
            addPlaceholder(canvas, channel, "Loading guide…")
            ensureEpg(channel)
        } else if (cached.isEmpty()) {
            addPlaceholder(canvas, channel, "Live now · no guide data")
        } else {
            addProgramCells(canvas, channel, cached)
        }
        val nowX = ((now - windowStart) / 60_000L).toInt() * dpPerMinute
        canvas.addView(View(this).apply { setBackgroundColor(MarqueeUi.BULB) }, FrameLayout.LayoutParams(
            dp(2),
            dp(76)
        ).apply { leftMargin = dp(nowX) })
    }

    private fun addPlaceholder(canvas: FrameLayout, channel: Catalog.Item, label: String) {
        val cell = MarqueeUi.text(this, label, 15f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = MarqueeUi.focusSelector(this@LiveGuideActivity, 0xFF141414.toInt(), MarqueeUi.INK, 6)
            setTextColor(MarqueeUi.textColors(MarqueeUi.SAND, MarqueeUi.GROUND))
            setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.02f) }
            setOnClickListener { play(channel) }
        }
        canvas.addView(cell, FrameLayout.LayoutParams(
            dp(minutes * dpPerMinute - 8),
            dp(56)
        ).apply {
            leftMargin = dp(4)
            topMargin = dp(10)
        })
    }

    private fun addProgramCells(
        canvas: FrameLayout,
        channel: Catalog.Item,
        programmes: List<XtreamClient.EpgProgram>
    ) {
        val visible = programmes.filter { it.endMs > windowStart && it.startMs < windowEnd }
        visible.forEach { program ->
            val start = maxOf(program.startMs, windowStart)
            val end = minOf(program.endMs, windowEnd)
            val leftMinutes = ((start - windowStart) / 60_000L).toInt()
            val widthMinutes = ((end - start) / 60_000L).toInt().coerceAtLeast(4)
            val onNow = now in program.startMs until program.endMs
            val cell = MarqueeUi.text(
                this,
                program.title,
                15f,
                MarqueeUi.INK,
                if (onNow) MarqueeUi.medium() else MarqueeUi.body()
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true
                isFocusableInTouchMode = true
                background = MarqueeUi.focusSelector(
                    this@LiveGuideActivity,
                    if (onNow) 0xFF262626.toInt() else 0xFF141414.toInt(),
                    MarqueeUi.INK,
                    6
                )
                setTextColor(MarqueeUi.textColors(MarqueeUi.INK, MarqueeUi.GROUND))
                setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.02f) }
                setOnClickListener { play(channel) }
            }
            canvas.addView(cell, FrameLayout.LayoutParams(
                dp(widthMinutes * dpPerMinute - 4),
                dp(56)
            ).apply {
                leftMargin = dp(leftMinutes * dpPerMinute + 2)
                topMargin = dp(10)
            })
        }
    }

    private fun play(channel: Catalog.Item) {
        startActivity(
            PlayerActivity.intent(this, client.liveUrl(channel.id), channel.name)
        )
    }

    private fun formatTime(value: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(value))

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)

    override fun onBackPressed() {
        if (categoryOverlay != null) {
            closeCategoryMenu()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {

        private var items: List<Catalog.Item> = emptyList()

        fun submit(list: List<Catalog.Item>) {
            items = list
            notifyDataSetChanged()
         }

        fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            buildRow()

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun onViewAttachedToWindow(holder: RowHolder) {
            registerScroller(holder.scroller)
        }

        override fun onViewDetachedFromWindow(holder: RowHolder) {
            unregisterScroller(holder.scroller)
        }
    }

    private inner class RowHolder(
        val row: LinearLayout,
        val channelCell: LinearLayout,
        val logo: ImageView,
        val nameView: TextView,
        val subView: TextView,
        val scroller: HorizontalScrollView,
        val canvas: FrameLayout
    ) : RecyclerView.ViewHolder(row) {

        private var channel: Catalog.Item? = null

        fun bind(item: Catalog.Item, position: Int) {
            channel = item
            nameView.text = Catalog.displayName(item.name)
            subView.text = "LIVE"
            ImageLoader.load(item.icon, logo)
            row.setBackgroundColor(if (position % 2 == 0) 0x00000000 else 0x08FFFFFF)
            channelCell.setOnClickListener { play(item) }
            channelCell.setOnLongClickListener {
                val added = FavoriteStore.toggle(
                    this@LiveGuideActivity,
                    ProfileStore.activeId(this@LiveGuideActivity),
                    item,
                    MODE_LIVE
                )
                Toast.makeText(
                    this@LiveGuideActivity,
                    if (added) "Added to My List" else "Removed from My List",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
            if (position == 0) {
                channelCell.nextFocusUpId = categoryButton.id
                categoryButton.nextFocusDownId = channelCell.id
                channelCell.setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                    ) {
                        categoryButton.requestFocus()
                        true
                    } else {
                        false
                    }
                }
            }
            renderCanvas(canvas, item)
            scroller.scrollTo(sharedScrollX, 0)
        }
    }

    private fun buildRow(): RowHolder {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val channelCell = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(48), 0, dp(12), 0)
            background = MarqueeUi.focusSelector(
                this@LiveGuideActivity,
                0x00000000,
                0xFF1A1A1A.toInt(),
                6,
                2,
                MarqueeUi.INK,
                strokeOnNormal = false
            )
            setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.02f) }
        }
        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        channelCell.addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginEnd = dp(12)
        })
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameView = MarqueeUi.text(this, "", 16f, MarqueeUi.INK, MarqueeUi.medium()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subView = MarqueeUi.text(this, "LIVE", 12f, MarqueeUi.SAND, MarqueeUi.body())
        textColumn.addView(nameView)
        textColumn.addView(subView)
        channelCell.addView(textColumn, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))
        row.addView(channelCell, LinearLayout.LayoutParams(dp(channelCellWidth), dp(76)))

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            clipToPadding = false
        }
        val canvas = FrameLayout(this)
        scroller.addView(canvas, FrameLayout.LayoutParams(
            dp(minutes * dpPerMinute),
            dp(76)
        ))
        row.addView(scroller, LinearLayout.LayoutParams(0, dp(76), 1f))

        return RowHolder(row, channelCell, logo, nameView, subView, scroller, canvas)
    }

    companion object {
        fun intent(context: Context) = Intent(context, LiveGuideActivity::class.java)
    }
}
