package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class LiveGuideActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: XtreamClient
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var timelineHeader: HorizontalScrollView
    private val scrollers = ArrayList<HorizontalScrollView>()
    private var syncing = false
    private var windowStart = 0L
    private var windowEnd = 0L
    private var now = 0L
    private val minutes = 120
    private val dpPerMinute = 12

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
        val root = FrameLayout(this).apply { setBackgroundColor(MarqueeUi.GROUND) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        content.addView(MarqueeNav(this, "live") { tab ->
            if (tab != "live") MarqueeNav.navigate(this, tab)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))

        header.addView(
            MarqueeUi.text(this, "LIVE", 18f, MarqueeUi.SAND, MarqueeUi.medium()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(48), 0, 0, 0)
            },
            LinearLayout.LayoutParams(dp(220), dp(44))
        )

        timelineHeader = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
        }
        val headerCanvas = FrameLayout(this).apply {
            setBackgroundColor(0x221B2430)
        }
        addTimeLabels(headerCanvas)
        timelineHeader.addView(headerCanvas, FrameLayout.LayoutParams(
            dp(minutes * dpPerMinute),
            dp(44)
        ))
        header.addView(timelineHeader, LinearLayout.LayoutParams(
            0,
            dp(44),
            1f
        ))

        scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(48))
        }
        scroll.addView(rows, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        link(timelineHeader)

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
        for (i in 0..3) {
            val label = MarqueeUi.text(
                this,
                formatTime(windowStart + i * 30 * 60_000L),
                15f,
                if (i == 0) MarqueeUi.DUST else MarqueeUi.SAND,
                MarqueeUi.medium()
            )
            canvas.addView(label, FrameLayout.LayoutParams(dp(100), dp(44)).apply {
                leftMargin = dp(i * 30 * dpPerMinute)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        val nowX = ((now - windowStart) / 60_000L).toInt() * dpPerMinute
        canvas.addView(View(this).apply {
            setBackgroundColor(MarqueeUi.BULB)
        }, FrameLayout.LayoutParams(dp(2), dp(44)).apply { leftMargin = dp(nowX) })
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Catalog.ensureHome(applicationContext) }
                val allow = ScanAllowlist.streamIds(this@LiveGuideActivity)
                val allChannels = Catalog.liveSections.asSequence()
                    .flatMap { it.items.asSequence() }
                    .distinctBy { it.id }
                    .toList()
                val filtered = allChannels.filter { allow.isEmpty() || it.id in allow }
                val channels = (filtered.ifEmpty { allChannels }).take(40)
                android.util.Log.i(
                    "FireIPTV",
                    "liveGuide start all=${allChannels.size} allowed=${filtered.size} shown=${channels.size}"
                )

                if (channels.isEmpty()) {
                    status.text = "No playable live channels found."
                    status.visibility = View.VISIBLE
                    return@launch
                }

                rows.removeAllViews()
                val canvases = mutableMapOf<String, FrameLayout>()
                channels.forEach { channel ->
                    canvases[channel.id] = addChannelRow(channel)
                }
                progress.visibility = View.GONE
                android.util.Log.i("FireIPTV", "liveGuide rows=${channels.size}")
                scroll.post { scrollToNow() }

                val semaphore = Semaphore(6)
                var loaded = 0
                channels.forEach { channel ->
                    scope.launch channelJob@ {
                        val listings = withContext(Dispatchers.IO) {
                            semaphore.withPermit {
                                runCatching {
                                    client.shortEpg(channel.id, 10)
                                }.getOrDefault(emptyList())
                            }
                        }
                        val canvas = canvases[channel.id] ?: return@channelJob
                        canvas.removeAllViews()
                        addProgramCells(canvas, channel, listings)
                        loaded++
                        if (loaded == channels.size) {
                            android.util.Log.i(
                                "FireIPTV",
                                "liveGuide epgLoaded=${channels.size}"
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                status.text = "Could not load guide: ${error.message}"
                status.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun addChannelRow(channel: Catalog.Item): FrameLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x0F1B2430)
        }
        rows.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(72)
        ))

        val channelCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(48), 0, dp(12), 0)
            background = MarqueeUi.focusSelector(this@LiveGuideActivity, 0x00000000, MarqueeUi.BULB, 6)
            setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.03f) }
            setOnClickListener { play(channel) }
            setOnLongClickListener {
                val added = FavoriteStore.toggle(
                    this@LiveGuideActivity,
                    ProfileStore.activeId(this@LiveGuideActivity),
                    channel,
                    MODE_LIVE
                )
                Toast.makeText(
                    this@LiveGuideActivity,
                    if (added) "Added to My List" else "Removed from My List",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
        channelCell.addView(
            MarqueeUi.text(
                this,
                Catalog.displayName(channel.name),
                16f,
                MarqueeUi.INK,
                MarqueeUi.medium()
            ).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        channelCell.addView(
            MarqueeUi.text(this, "LIVE", 12f, MarqueeUi.SAND, MarqueeUi.body())
        )
        row.addView(channelCell, LinearLayout.LayoutParams(dp(220), dp(72)))

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
        }
        val canvas = FrameLayout(this)
        addProgramCells(canvas, channel, emptyList())
        scroller.addView(canvas, FrameLayout.LayoutParams(
            dp(minutes * dpPerMinute),
            dp(72)
        ))
        row.addView(scroller, LinearLayout.LayoutParams(
            0,
            dp(72),
            1f
        ))
        link(scroller)
        return canvas
    }

    private fun addProgramCells(
        canvas: FrameLayout,
        channel: Catalog.Item,
        programmes: List<XtreamClient.EpgProgram>
    ) {
        val visible = programmes.filter { it.endMs > windowStart && it.startMs < windowEnd }
        if (visible.isEmpty()) {
            canvas.addView(
                MarqueeUi.text(this, "Live now · no guide data", 15f, MarqueeUi.DUST, MarqueeUi.body()).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                },
                FrameLayout.LayoutParams(dp(minutes * dpPerMinute - 8), dp(56)).apply {
                    leftMargin = dp(4)
                    topMargin = dp(8)
                }
            )
            return
        }

        visible.forEach { program ->
            val start = maxOf(program.startMs, windowStart)
            val end = minOf(program.endMs, windowEnd)
            val leftMinutes = ((start - windowStart) / 60_000L).toInt()
            val widthMinutes = ((end - start) / 60_000L).toInt().coerceAtLeast(4)
            val cell = MarqueeUi.text(
                this,
                program.title,
                15f,
                MarqueeUi.INK,
                MarqueeUi.body()
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true
                isFocusableInTouchMode = true
                background = MarqueeUi.focusSelector(
                    this@LiveGuideActivity,
                    MarqueeUi.VELVET,
                    MarqueeUi.BULB,
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
                topMargin = dp(8)
            })
        }

        val nowX = ((now - windowStart) / 60_000L).toInt() * dpPerMinute
        canvas.addView(View(this).apply { setBackgroundColor(MarqueeUi.BULB) }, FrameLayout.LayoutParams(
            dp(2),
            dp(72)
        ).apply { leftMargin = dp(nowX) })
    }

    private fun link(scroller: HorizontalScrollView) {
        scrollers.add(scroller)
        scroller.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (syncing) return@setOnScrollChangeListener
            syncing = true
            scrollers.forEach { other ->
                if (other !== scroller) other.scrollTo(scrollX, 0)
            }
            syncing = false
        }
    }

    private fun scrollToNow() {
        val minutesNow = ((now - windowStart) / 60_000L).toInt()
        val x = dp((minutesNow * dpPerMinute - 160).coerceAtLeast(0))
        syncing = true
        scrollers.forEach { it.scrollTo(x, 0) }
        syncing = false
    }

    private fun play(channel: Catalog.Item) {
        startActivity(
            PlayerActivity.intent(
                this,
                client.liveUrl(channel.id),
                channel.name
            )
        )
    }

    private fun formatTime(value: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(value))

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context) = Intent(context, LiveGuideActivity::class.java)
    }
}
