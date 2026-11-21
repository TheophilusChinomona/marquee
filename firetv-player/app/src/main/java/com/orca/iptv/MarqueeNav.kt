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

    private fun wordmark(): TextView =
        MarqueeUi.text(context, "MARQUEE", 26f, MarqueeUi.BULB, MarqueeUi.display()).apply {
            letterSpacing = -0.01f
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
            background = MarqueeUi.focusSelector(
                context,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                6,
                2,
                MarqueeUi.INK
            )
            setOnFocusChangeListener { v, _ ->
                MarqueeUi.scaleOnFocus(v, 1.04f)
            }
            addView(avatar, LayoutParams(dp(24), dp(24)))
            addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            setOnClickListener {
                context.startActivity(Intent(context, ProfilesActivity::class.java))
            }
        }
        name.setTextColor(MarqueeUi.textColors(MarqueeUi.INK, MarqueeUi.INK))
        name.isDuplicateParentStateEnabled = true
        return Triple(chip, name, avatar)
    }

    private fun avatarColor(name: String): Int {
        val colors = intArrayOf(
            0xFFE50914.toInt(),
            0xFF0071EB.toInt(),
            0xFF46D369.toInt(),
            0xFFB7410E.toInt(),
            0xFF8E44AD.toInt()
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

        private val labelView: TextView

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            background = MarqueeUi.focusSelector(context, Color.TRANSPARENT, Color.TRANSPARENT, 0)
            setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.04f) }

            labelView = MarqueeUi.text(context, label, 18f, MarqueeUi.SAND, MarqueeUi.body()).apply {
                gravity = Gravity.CENTER
                setPadding(MarqueeUi.dp(context, 12), 0, MarqueeUi.dp(context, 12), 0)
                setTextColor(MarqueeUi.textColors(MarqueeUi.SAND, MarqueeUi.INK, MarqueeUi.INK))
                isDuplicateParentStateEnabled = true
            }
            addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            isSelected = selected
            setOnClickListener { onSelected(key) }
        }

        override fun setSelected(selected: Boolean) {
            super.setSelected(selected)
            labelView.setTypeface(
                if (selected) MarqueeUi.medium() else MarqueeUi.body()
            )
        }
    }

    companion object {
        fun navigate(context: Context, tab: String) {
            when (tab) {
                "home" -> context.startActivity(homeIntent(context, null))
                "live" -> context.startActivity(LiveGuideActivity.intent(context))
                "movies" -> context.startActivity(BrowseActivity.intent(context, MODE_VOD, ""))
                "series" -> context.startActivity(BrowseActivity.intent(context, MODE_SERIES, ""))
                "list" -> context.startActivity(MyListActivity.intent(context))
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
