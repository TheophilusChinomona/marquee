package com.orca.iptv

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ProfilesActivity : AppCompatActivity() {

    private lateinit var row: LinearLayout
    private lateinit var activeId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeId = ProfileStore.activeId(this)
        buildUi()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(MarqueeUi.GROUND) }

        val title = MarqueeUi.text(this, "Who's watching?", 64f, MarqueeUi.INK, MarqueeUi.display())
        root.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(76) })

        val subtitle = MarqueeUi.text(
            this,
            "Profiles share the provider login and keep their own My List, history and recommendations.",
            18f,
            MarqueeUi.SAND,
            MarqueeUi.body()
        ).apply { gravity = Gravity.CENTER }
        root.addView(subtitle, FrameLayout.LayoutParams(
            dp(760),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(164) })

        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        root.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply { topMargin = dp(24) })

        val add = MarqueeUi.button(this, "+  Add profile").apply {
            setOnClickListener { addProfile() }
        }
        root.addView(add, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(44),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(64) })

        setContentView(root)
        render()
    }

    private fun render() {
        row.removeAllViews()
        val profiles = ProfileStore.all(this)
        profiles.forEach { profile ->
            row.addView(
                profileCard(profile),
                LinearLayout.LayoutParams(dp(156), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                }
            )
        }
        row.addView(
            addCard(),
            LinearLayout.LayoutParams(dp(132), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            }
        )
        row.post {
            val index = profiles.indexOfFirst { it.id == activeId }
            if (index >= 0) row.getChildAt(index)?.requestFocus()
        }
    }

    private fun profileCard(profile: Profile): LinearLayout {
        val color = avatarColor(profile.name)
        val active = profile.id == activeId
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                ProfileStore.setActive(this@ProfilesActivity, profile.id)
                finish()
            }
            setOnLongClickListener {
                confirmDelete(profile)
                true
            }
        }

        val avatarFrame = FrameLayout(this)
        val avatar = TextView(this).apply {
            text = profile.name.take(1).uppercase()
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 64f)
            setTextColor(MarqueeUi.INK)
            setTypeface(MarqueeUi.display())
            background = MarqueeUi.fill(this@ProfilesActivity, color, 22)
        }
        avatarFrame.addView(avatar, FrameLayout.LayoutParams(dp(120), dp(120)))

        val ring = View(this).apply {
            background = MarqueeUi.fill(this@ProfilesActivity, 0x00000000, 26, 3, MarqueeUi.BULB)
            visibility = View.INVISIBLE
            isFocusable = false
        }
        avatarFrame.addView(ring, FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER))
        card.addView(avatarFrame, LinearLayout.LayoutParams(dp(128), dp(128)))

        val name = MarqueeUi.text(this, profile.name, 20f, MarqueeUi.INK, MarqueeUi.medium())
        name.gravity = Gravity.CENTER
        card.addView(name, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        if (active) {
            val now = MarqueeUi.text(this, "● Watching now", 14f, MarqueeUi.BULB, MarqueeUi.body())
            now.gravity = Gravity.CENTER
            card.addView(now, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
        }

        card.setOnFocusChangeListener { v, hasFocus ->
            MarqueeUi.scaleOnFocus(v, 1.07f)
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            name.setTextColor(if (hasFocus) MarqueeUi.BULB else MarqueeUi.INK)
        }
        return card
    }

    private fun addCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { addProfile() }
        }
        val frame = FrameLayout(this)
        val plus = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 54f)
            setTextColor(MarqueeUi.SAND)
            background = MarqueeUi.fill(this@ProfilesActivity, 0x00000000, 22, 2, MarqueeUi.LINE2)
        }
        frame.addView(plus, FrameLayout.LayoutParams(dp(120), dp(120)))
        card.addView(frame, LinearLayout.LayoutParams(dp(120), dp(120)))
        val label = MarqueeUi.text(this, "Add profile", 18f, MarqueeUi.SAND, MarqueeUi.body())
        label.gravity = Gravity.CENTER
        card.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        card.setOnFocusChangeListener { v, _ -> MarqueeUi.scaleOnFocus(v, 1.07f) }
        return card
    }

    private fun addProfile() {
        val input = EditText(this).apply {
            hint = "Profile name"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ContextCompat.getDrawable(this@ProfilesActivity, R.drawable.input_bg)
            setTextColor(MarqueeUi.INK)
            setHintTextColor(MarqueeUi.DUST)
            textSize = 18f
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("New profile")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Profile" }
                val profile = ProfileStore.add(this, name)
                ProfileStore.setActive(this, profile.id)
                activeId = profile.id
                render()
            }
            .create()

        dialog.setOnShowListener {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun confirmDelete(profile: Profile) {
        if (ProfileStore.all(this).size <= 1) {
            Toast.makeText(this, "Keep at least one profile", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete profile?")
            .setMessage("Delete ${profile.name}? Its My List and history will be removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                ProfileStore.remove(this, profile.id)
                activeId = ProfileStore.activeId(this)
                render()
            }
            .show()
    }

    private fun avatarColor(name: String): Int {
        val colors = intArrayOf(MarqueeUi.CURTAIN, MarqueeUi.BULB, MarqueeUi.OLIVE, MarqueeUi.AIR)
        return colors[(name.hashCode() % colors.size + colors.size) % colors.size]
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)
}
