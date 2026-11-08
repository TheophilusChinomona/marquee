package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

/**
 * Marquee's global top bar. Kept as a view (not an activity) so every screen
 * can use the same wordmark, tab rhythm, focus treatment and profile chip.
 */
class MarqueeNav(
    context: Context,
    private val activeTab: String?,
    private val onTab: ((String) -> Unit)? = null
) : LinearLayout(context) {

    private val profileName: TextView
    private val profileAvatar: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(48), dp(20), dp(48), 0)

        addView(wordmark())
        addView(tabs(), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(24)
        })
        addView(Space(context), LayoutParams(0, 1).apply { weight = 1f })

        val profile = profileChip()
        profileName = profile.second
        profileAvatar = profile.third
        addView(profile.first)
    }

    fun refreshProfile() {
        val profile = ProfileStore.active(context)
        profileName.text = profile.name
        profileAvatar.text = profile.name.take(1).uppercase()
        profileAvatar.background = MarqueeUi.fill(context, avatarColor(profile.name), 7)
    }

    private fun wordmark(): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = HORIZONTAL
        addView(MarqueeUi.text(context, "MARQUEE", 22f, MarqueeUi.INK, MarqueeUi.display()).apply {
            letterSpacing = 0.14f
        })
        addView(MarqueeUi.text(context, "•••", 16f, MarqueeUi.BULB, MarqueeUi.body()).apply {
            setPadding(dp(8), 0, 0, 0)
        })
    }

    private fun tabs(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addTab(this, "home", "Home")
        addTab(this, "live", "Live")
        addTab(this, "movies", "Movies")
        addTab(this, "series", "Series")
        addTab(this, "list", "My List")
        addTab(this, "search", "Search")
    }

    private fun addTab(parent: LinearLayout, key: String, label: String) {
        parent.addView(TabItem(context, key, label, activeTab == key) { selected ->
            val handler = onTab
            if (handler != null) handler(selected) else navigate(context, selected)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply {
            marginEnd = dp(4)
        })
    }

    private fun profileChip(): Triple<LinearLayout, TextView, TextView> {
        val profile = ProfileStore.active(context)
        val first = profile.name.take(1).uppercase()
        val avatar = TextView(context).apply {
            text = first
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(MarqueeUi.INK)
            setTypeface(MarqueeUi.display())
            background = MarqueeUi.fill(context, avatarColor(profile.name), 7)
        }
        val name = MarqueeUi.text(context, profile.name, 16f, MarqueeUi.INK, MarqueeUi.medium())
        val chip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(4), dp(4), dp(14), dp(4))
            background = MarqueeUi.focusSelector(context, 0xB31B2430.toInt(), MarqueeUi.BULB, 18)
            setOnFocusChangeListener { v, _ ->
                MarqueeUi.scaleOnFocus(v, 1.05f)
            }
            addView(avatar, LayoutParams(dp(24), dp(24)))
            addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            setOnClickListener {
                context.startActivity(Intent(context, ProfilesActivity::class.java))
            }
        }
        name.setTextColor(MarqueeUi.textColors(MarqueeUi.INK, MarqueeUi.GROUND))
        name.isDuplicateParentStateEnabled = true
        return Triple(chip, name, avatar)
    }

    private fun avatarColor(name: String): Int {
        val colors = intArrayOf(
            MarqueeUi.CURTAIN,
            MarqueeUi.BULB,
            MarqueeUi.OLIVE,
            MarqueeUi.AIR
        )
        val index = ((name.hashCode() % colors.size) + colors.size) % colors.size
        return colors[index]
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(context, value)

    private class TabItem(
        context: Context,
        private val key: String,
        label: String,
        selected: Boolean,
        private val onSelected: (String) -> Unit
    ) : FrameLayout(context) {

        private val dot: View
        private val labelView: TextView

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            background = MarqueeUi.focusSelector(context, Color.TRANSPARENT, MarqueeUi.BULB, 16)
            setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.05f) }

            labelView = MarqueeUi.text(context, label, 18f, MarqueeUi.SAND, MarqueeUi.body()).apply {
                gravity = Gravity.CENTER
                setPadding(MarqueeUi.dp(context, 13), 0, MarqueeUi.dp(context, 13), 0)
                setTextColor(MarqueeUi.textColors(MarqueeUi.SAND, MarqueeUi.GROUND, MarqueeUi.INK))
                isDuplicateParentStateEnabled = true
            }
            addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            dot = View(context).apply {
                background = MarqueeUi.fill(context, MarqueeUi.BULB, 3)
                visibility = if (selected) VISIBLE else GONE
            }
            addView(dot, LayoutParams(MarqueeUi.dp(context, 6), MarqueeUi.dp(context, 6)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = MarqueeUi.dp(context, 1)
            })

            isSelected = selected
            setOnClickListener { onSelected(key) }
        }

        override fun setSelected(selected: Boolean) {
            super.setSelected(selected)
            dot.visibility = if (selected) VISIBLE else GONE
        }
    }

    companion object {
        fun navigate(context: Context, tab: String) {
            when (tab) {
                "home" -> context.startActivity(homeIntent(context, null))
                "live" -> context.startActivity(BrowseActivity.intent(context, MODE_LIVE, ""))
                "movies" -> context.startActivity(BrowseActivity.intent(context, MODE_VOD, ""))
                "series" -> context.startActivity(BrowseActivity.intent(context, MODE_SERIES, ""))
                "list" -> context.startActivity(homeIntent(context, "list"))
                "search" -> context.startActivity(SearchActivity.intent(context))
            }
        }

        private fun homeIntent(context: Context, tab: String?): Intent =
            Intent(context, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (tab != null) putExtra("marquee_tab", tab)
            }
    }
}
