package com.orca.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CategoryAdapter(
    private val onClick: (XtreamClient.Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

    private var items: List<XtreamClient.Category> = emptyList()

    fun submit(list: List<XtreamClient.Category>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_text, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.text.text = item.name
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.text)
    }
}

class StreamAdapter(
    private val live: Boolean,
    private val isFavorite: (XtreamClient.Stream) -> Boolean = { false },
    private val onLongClick: ((XtreamClient.Stream) -> Unit)? = null,
    private val onClick: (XtreamClient.Stream) -> Unit
) : RecyclerView.Adapter<StreamAdapter.Holder>() {

    private var items: List<XtreamClient.Stream> = emptyList()

    fun submit(list: List<XtreamClient.Stream>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (live) R.layout.item_channel else R.layout.item_grid_poster
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item, live, isFavorite(item), onClick)
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

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        private val logo: ImageView? = view.findViewById(R.id.logo)
        private val image: ImageView? = view.findViewById(R.id.image)

        fun bind(
            item: XtreamClient.Stream,
            live: Boolean,
            favorite: Boolean,
            onClick: (XtreamClient.Stream) -> Unit
        ) {
            val title = if (live) item.name else Catalog.displayName(item.name)
            name.text = if (favorite) "★ $title" else title
            (logo ?: image)?.let { ImageLoader.load(item.icon, it) }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}

