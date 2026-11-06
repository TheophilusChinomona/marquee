package com.orca.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CardAdapter(
    private val items: List<Catalog.Item>,
    private val live: Boolean,
    private val largePoster: Boolean = false,
    private val isFavorite: (Catalog.Item) -> Boolean = { false },
    private val onLongClick: ((Catalog.Item) -> Unit)? = null,
    private val onClick: (Catalog.Item) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onSeeAll: (() -> Unit)? = null

    override fun getItemCount() = items.size + if (onSeeAll != null) 1 else 0

    override fun getItemViewType(position: Int) = when {
        position >= items.size -> VIEW_SEE_ALL
        live -> VIEW_LIVE
        largePoster -> VIEW_POSTER_LARGE
        else -> VIEW_POSTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_LIVE -> LiveHolder(inflater.inflate(R.layout.item_live, parent, false))
            VIEW_POSTER -> PosterHolder(inflater.inflate(R.layout.item_poster, parent, false))
            VIEW_POSTER_LARGE -> PosterHolder(inflater.inflate(R.layout.item_poster_large, parent, false))
            else -> SeeAllHolder(
                inflater.inflate(
                    if (live) R.layout.item_see_all_live else R.layout.item_see_all,
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is FocusHolder) holder.resetScale()
        if (holder is SeeAllHolder) {
            holder.bind { onSeeAll?.invoke() }
            return
        }
        val item = items[position]
        val favorite = isFavorite(item)
        when (holder) {
            is LiveHolder -> holder.bind(item, onClick, favorite)
            is PosterHolder -> holder.bind(item, onClick, favorite)
        }
        if (onLongClick != null) {
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                onLongClick.invoke(item)
                notifyItemChanged(pos)
                true
            }
        }
    }

    private abstract class FocusHolder(view: View) : RecyclerView.ViewHolder(view) {
        init {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setOnFocusChangeListener { focusedView, hasFocus ->
                focusedView.animate()
                    .scaleX(if (hasFocus) 1.03f else 1f)
                    .scaleY(if (hasFocus) 1.03f else 1f)
                    .setDuration(120)
                    .start()
                focusedView.translationZ = if (hasFocus) 12f else 0f
            }
        }

        fun resetScale() {
            itemView.animate().cancel()
            val focused = itemView.isFocused
            itemView.scaleX = if (focused) 1.03f else 1f
            itemView.scaleY = if (focused) 1.03f else 1f
            itemView.translationZ = if (focused) 12f else 0f
        }
    }

    private class LiveHolder(view: View) : FocusHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.logo)
        private val name: TextView = view.findViewById(R.id.name)

        fun bind(
            item: Catalog.Item,
            onClick: (Catalog.Item) -> Unit,
            favorite: Boolean
        ) {
            name.text = if (favorite) "★ ${item.name}" else item.name
            ImageLoader.load(item.icon, logo)
            itemView.setOnClickListener { onClick(item) }
        }
    }

    private class PosterHolder(view: View) : FocusHolder(view) {
        private val image: ImageView = view.findViewById(R.id.image)
        private val name: TextView = view.findViewById(R.id.name)

        fun bind(
            item: Catalog.Item,
            onClick: (Catalog.Item) -> Unit,
            favorite: Boolean
        ) {
            val title = Catalog.displayName(item.name)
            name.text = if (favorite) "★ $title" else title
            ImageLoader.load(item.icon, image)
            itemView.setOnClickListener { onClick(item) }
        }
    }

    private class SeeAllHolder(view: View) : FocusHolder(view) {
        fun bind(onClick: () -> Unit) {
            itemView.setOnClickListener { onClick() }
        }
    }

    companion object {
        private const val VIEW_LIVE = 0
        private const val VIEW_POSTER = 1
        private const val VIEW_SEE_ALL = 2
        private const val VIEW_POSTER_LARGE = 3
    }
}

data class SearchResult(val item: Catalog.Item, val mode: Int)

class SearchResultAdapter(
    private val isFavorite: (SearchResult) -> Boolean = { false },
    private val onLongClick: ((SearchResult) -> Unit)? = null,
    private val onClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.Holder>() {

    private var items: List<SearchResult> = emptyList()

    fun submit(list: List<SearchResult>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_result, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val result = items[position]
        val title = if (result.mode == MODE_LIVE) {
            result.item.name
        } else {
            Catalog.displayName(result.item.name)
        }
        holder.name.text = if (isFavorite(result)) "★ $title" else title
        holder.subtitle.text = when (result.mode) {
            MODE_LIVE -> "LIVE TV"
            MODE_VOD -> "MOVIE"
            else -> "SERIES"
        }
        ImageLoader.load(result.item.icon, holder.image)
        holder.itemView.setOnClickListener { onClick(result) }
        if (onLongClick != null) {
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                onLongClick.invoke(result)
                notifyItemChanged(pos)
                true
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val name: TextView = view.findViewById(R.id.name)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }
}
