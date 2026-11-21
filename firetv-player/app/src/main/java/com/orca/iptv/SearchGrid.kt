package com.orca.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** A row in the search results grid: either a section header or a poster. */
sealed class SearchRow {
    data class Header(val title: String, val count: Int) : SearchRow()
    data class Item(val result: SearchResult, val favorite: Boolean) : SearchRow()
}

/**
 * Poster grid with section headers. Headers span the full width through the
 * GridLayoutManager's span size lookup, so movies, series and channels stay
 * visually separated instead of interleaving.
 */
class SearchGridAdapter(
    private val onClick: (SearchResult) -> Unit,
    private val onLongClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<SearchRow> = emptyList()

    fun submit(list: List<SearchRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        if (rows[position] is SearchRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return if (viewType == TYPE_HEADER) {
            val view = MarqueeUi.text(context, "", 20f, MarqueeUi.INK, MarqueeUi.medium()).apply {
                setPadding(0, MarqueeUi.dp(context, 18), 0, MarqueeUi.dp(context, 8))
            }
            HeaderHolder(view)
        } else {
            val view = LayoutInflater.from(context).inflate(R.layout.item_grid_poster, parent, false)
            ItemHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SearchRow.Header -> (holder as HeaderHolder).text.text = "${row.title}  ·  ${row.count}"
            is SearchRow.Item -> bindItem(holder as ItemHolder, row)
        }
    }

    private fun bindItem(holder: ItemHolder, row: SearchRow.Item) {
        val result = row.result
        val label = if (result.mode == MODE_LIVE) {
            result.item.name
        } else {
            Catalog.displayName(result.item.name)
        }
        holder.name.text = if (row.favorite) "★ $label" else label
        ImageLoader.load(result.item.icon, holder.image)

        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(150L)
                .start()
            view.translationZ = if (hasFocus) 10f else 0f
        }
        holder.itemView.setOnClickListener { onClick(result) }
        holder.itemView.setOnLongClickListener {
            onLongClick(result)
            true
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val name: TextView = view.findViewById(R.id.name)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
