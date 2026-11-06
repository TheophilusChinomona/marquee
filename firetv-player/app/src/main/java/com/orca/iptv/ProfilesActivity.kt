package com.orca.iptv

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProfilesActivity : AppCompatActivity() {

    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        val list = findViewById<RecyclerView>(R.id.profiles)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ProfileAdapter(
            onSelect = { profile -> select(profile) },
            onLongClick = { profile -> confirmDelete(profile) }
        )
        list.adapter = adapter

        findViewById<Button>(R.id.addProfile).setOnClickListener { addProfile() }
        refresh()
    }

    private fun refresh() {
        adapter.submit(ProfileStore.all(this), ProfileStore.activeId(this))
    }

    private fun select(profile: Profile) {
        ProfileStore.setActive(this, profile.id)
        finish()
    }

    private fun addProfile() {
        val input = EditText(this).apply {
            hint = "Profile name"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ContextCompat.getDrawable(this@ProfilesActivity, R.drawable.input_bg)
            setTextColor(ContextCompat.getColor(this@ProfilesActivity, R.color.text))
            setHintTextColor(ContextCompat.getColor(this@ProfilesActivity, R.color.text_dim))
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
                finish()
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
            .setMessage("Delete ${profile.name}? Its favorites will be removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                ProfileStore.remove(this, profile.id)
                refresh()
            }
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class ProfileAdapter(
        private val onSelect: (Profile) -> Unit,
        private val onLongClick: (Profile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

        private var items: List<Profile> = emptyList()
        private var activeId = ""

        fun submit(list: List<Profile>, active: String) {
            items = list
            activeId = active
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val profile = items[position]
            val active = profile.id == activeId
            holder.avatar.text = profile.name.take(1).uppercase()
            holder.name.text = profile.name
            holder.subtitle.text = if (active) "Current profile" else "Select profile"
            holder.subtitle.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (active) R.color.accent else R.color.text_dim
                )
            )
            holder.itemView.setOnClickListener { onSelect(profile) }
            holder.itemView.setOnLongClickListener {
                onLongClick(profile)
                true
            }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.avatar)
            val name: TextView = view.findViewById(R.id.name)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }
    }
}
