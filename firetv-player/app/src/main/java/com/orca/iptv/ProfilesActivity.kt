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
    private lateinit var onlineButton: TextView
    private lateinit var serverButton: TextView
    private lateinit var hintView: TextView
    private lateinit var activeId: String

    /**
     * Says plainly which of the two online modes is in force, so nobody has to guess
     * why results appear for one title and not another.
     */
    private fun hintText(): String = when {
        !Prefs.onlineEnabled(this) ->
            "Online sources are off. Search covers your library only."
        Prefs.resolverBase(this).isBlank() ->
            "No stream server set. Online results use the built-in public fallback; add a resolver for a private, reliable one."
        else ->
            "Online sources fill in titles your library does not carry."
    }

    private fun onlineLabel(): String =
        if (Prefs.onlineEnabled(this)) "Online sources: On" else "Online sources: Off"

    private fun serverLabel(): String {
        val base = Prefs.resolverBase(this)
        if (base.isBlank()) return "Server: not set"
        val host = base.removePrefix("http://").removePrefix("https://")
        return if (Prefs.resolverKey(this).isNotBlank()) "Server: $host · key" else "Server: $host"
    }

    private fun editServer() {
        fun field(hint: String, value: String, uri: Boolean): EditText =
            EditText(this).apply {
                this.hint = hint
                inputType = if (uri) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setSingleLine(true)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = ContextCompat.getDrawable(this@ProfilesActivity, R.drawable.input_bg)
                setTextColor(MarqueeUi.INK)
                setHintTextColor(MarqueeUi.DUST)
                textSize = 18f
                setText(value)
                setSelection(text.length)
            }

        val serverField = field("192.168.1.50:8099", Prefs.resolverBase(this), uri = true)
        val keyField = field("Access key (optional)", Prefs.resolverKey(this), uri = false)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
            addView(serverField, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(keyField, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Stream server")
            .setMessage(
                "Where the resolver runs. Leave the address blank to use only the " +
                    "public fallback. Key: a Cloudflare Access service token as " +
                    "clientId:clientSecret, or a bearer token. Only needed when the " +
                    "host is not open on your LAN."
            )
            .setView(column)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                var value = serverField.text.toString().trim().trimEnd('/')
                if (value.isNotEmpty() && !value.startsWith("http")) value = "http://" + value
                Prefs.setResolverBase(this, value)
                Prefs.setResolverKey(this, keyField.text.toString())
                serverButton.text = serverLabel()
                hintView.text = hintText()
            }
            .create()
        dialog.setOnShowListener {
            serverField.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(serverField, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

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

        // The online fallback is the one global switch in the app; it lives here
        // because this screen is what the profile chip in the top bar opens.
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        onlineButton = MarqueeUi.button(this, onlineLabel()).apply {
            setOnClickListener {
                val enabled = !Prefs.onlineEnabled(this@ProfilesActivity)
                Prefs.setOnlineEnabled(this@ProfilesActivity, enabled)
                text = onlineLabel()
                hintView.text = hintText()
            }
        }
        footer.addView(onlineButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ).apply { marginEnd = dp(12) })

        // The resolver's address is the one thing that cannot be discovered at
        // runtime: it is wherever the viewer runs /resolver.
        serverButton = MarqueeUi.button(this, serverLabel()).apply {
            setOnClickListener { editServer() }
        }
        footer.addView(serverButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ).apply { marginEnd = dp(12) })
        footer.addView(add, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        ))

        val footerColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(footer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            hintView = MarqueeUi.text(
                this@ProfilesActivity,
                hintText(),
                14f,
                MarqueeUi.DUST,
                MarqueeUi.body()
            ).apply { gravity = Gravity.CENTER }
            addView(hintView, LinearLayout.LayoutParams(
                dp(620),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }
        root.addView(footerColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(56) })

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
            val now = MarqueeUi.text(this, "● Watching now", 14f, MarqueeUi.BULB, MarqueeUi.body()).apply {
                // includeFontPadding = false in MarqueeUi.text clips the descender of "g".
                includeFontPadding = true
                gravity = Gravity.CENTER
            }
            card.addView(now, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(22)
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
