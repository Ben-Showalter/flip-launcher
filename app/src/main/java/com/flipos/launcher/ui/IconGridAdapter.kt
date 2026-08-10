package com.flipos.launcher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.R

/** Grid of individual icons inside one icon pack, for picking a replacement app icon. */
class IconGridAdapter(
    private val onClick: (String) -> Unit,
    private val iconLoader: (String) -> Drawable?,
) : RecyclerView.Adapter<IconGridAdapter.VH>() {

    private fun highlightColor(context: Context): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) tv.data else Color.WHITE
    }

    private val names = ArrayList<String>()

    fun submit(list: List<String>) {
        submitWithDiff(names, list) { a, b -> a == b }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_grid, parent, false)
        return VH(view)
    }

    override fun getItemCount() = names.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(names[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)

        // Same focus/press squircle the app drawer uses, so the picker is
        // navigable by D-pad with a visible highlight on the focused icon.
        init {
            itemView.findViewById<View>(R.id.icon_frame).background =
                SquircleDrawable(highlightColor(itemView.context))
        }

        fun bind(name: String) {
            icon.setImageDrawable(iconLoader(name))
            icon.contentDescription = name
            itemView.setOnClickListener { onClick(name) }
        }
    }
}
