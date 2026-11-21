package com.orca.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MyListActivity : AppCompatActivity() {

    private lateinit var client: XtreamClient
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private lateinit var countLabel: TextView
    private val adapter = ListAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Prefs.load(this) ?: run {
            finish()
            return
        }
        client = XtreamClient(creds.server, creds.user, creds.pass)
        buildUi()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(MarqueeUi.GROUND) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        content.addView(
            MarqueeNav(this, "list") { tab ->
                if (tab != "list") MarqueeNav.navigate(this, tab)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80))
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), 0, dp(48), dp(10))
        }
        header.addView(MarqueeUi.text(this, "My List", 26f, MarqueeUi.INK, MarqueeUi.medium()))
        countLabel = MarqueeUi.text(this, "", 15f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            setPadding(dp(14), 0, 0, 0)
        }
        header.addView(countLabel)
        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ))

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MyListActivity, 5)
            adapter = this@MyListActivity.adapter
            setHasFixedSize(true)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(40), dp(4), dp(40), dp(48))
        }
        content.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        status = MarqueeUi.text(this, "", 18f, MarqueeUi.SAND, MarqueeUi.body()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload(requestFocus = true)
    }

    private fun reload(requestFocus: Boolean) {
        val favorites = FavoriteStore.all(this, ProfileStore.activeId(this))
        adapter.submit(favorites)
        countLabel.text = "${favorites.size} titles"
        if (favorites.isEmpty()) {
            status.text = "Your list is empty.\nLong-press OK on any title to add it."
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.GONE
            if (requestFocus) {
                grid.post {
                    grid.scrollToPosition(0)
                    grid.post { grid.getChildAt(0)?.requestFocus() }
                }
            }
        }
    }

    private fun open(favorite: Favorite) {
        when (favorite.mode) {
            MODE_LIVE -> startActivity(
                PlayerActivity.intent(
                    this,
                    client.liveUrl(favorite.id),
                    favorite.name
                )
            )
            MODE_SERIES -> startActivity(
                SeriesActivity.intent(this, favorite.id, favorite.name)
            )
            else -> startActivity(
                DetailActivity.intent(
                    this,
                    favorite.id,
                    favorite.name,
                    favorite.icon,
                    favorite.ext,
                    MODE_VOD,
                    ""
                )
            )
        }
    }

    private fun remove(favorite: Favorite) {
        FavoriteStore.remove(
            this,
            ProfileStore.activeId(this),
            favorite.id,
            favorite.mode
        )
        Toast.makeText(this, "Removed from My List", Toast.LENGTH_SHORT).show()
        reload(requestFocus = false)
    }

    private fun dp(value: Int): Int = MarqueeUi.dp(this, value)

    private inner class ListAdapter : RecyclerView.Adapter<Holder>() {

        private var items: List<Favorite> = emptyList()

        fun submit(list: List<Favorite>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_grid_poster, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.image)
        private val name: TextView = view.findViewById(R.id.name)

        fun bind(favorite: Favorite) {
            name.text = Catalog.displayName(favorite.name)
            image.scaleType = if (favorite.mode == MODE_LIVE) {
                ImageView.ScaleType.FIT_CENTER
            } else {
                ImageView.ScaleType.CENTER_CROP
            }
            ImageLoader.load(favorite.icon, image)
            itemView.setOnFocusChangeListener { v, hasFocus ->
                MarqueeUi.scaleOnFocus(v, 1.04f)
                if (hasFocus) v.bringToFront()
            }
            itemView.setOnClickListener { open(favorite) }
            itemView.setOnLongClickListener {
                remove(favorite)
                true
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, MyListActivity::class.java)
    }
}
