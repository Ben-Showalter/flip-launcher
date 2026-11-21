package com.flipos.launcher.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.R
import com.flipos.launcher.data.WallpaperLibrary
import com.flipos.launcher.util.WallpaperContrast

/**
 * Grid of wallpaper thumbnails for the Wallpaper Picker (built-in and
 * gallery ones - see [WallpaperLibrary]), each shown as Home would look with
 * it: Home's dimming for that wallpaper's brightness, plus [overlay] (see
 * [HomePreview]) - the clock and soft keys. One left out of the daily change
 * ([isOff]) is dimmed with an OFF badge.
 */
class WallpaperGridAdapter(
    private val isOff: (id: String) -> Boolean,
    private val onClick: (id: String) -> Unit,
) : RecyclerView.Adapter<WallpaperGridAdapter.VH>() {

    private val ids = ArrayList<String>()

    /** Wallpaper id -> brightness, measured once each for Home's scrim. */
    private val brightness = HashMap<String, Float>()

    var overlay: Bitmap? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, ids.size)
        }

    fun submit(list: List<String>) {
        submitWithDiff(ids, list) { a, b -> a == b }
    }

    fun idAt(position: Int): String? = ids.getOrNull(position)

    /** Redraws [id]'s tile (its OFF badge changed). */
    fun refresh(id: String) {
        val position = ids.indexOf(id)
        if (position >= 0) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wallpaper_grid, parent, false)
        return VH(view)
    }

    override fun getItemCount() = ids.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(ids[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: PortraitThumbView = itemView.findViewById(R.id.thumb)
        private val offBadge: TextView = itemView.findViewById(R.id.off_badge)

        fun bind(id: String) {
            val context = itemView.context
            val drawable: Drawable? = WallpaperLibrary.loadThumbnail(context, id)
            thumb.setImageDrawable(drawable)
            thumb.overlay = overlay
            thumb.scrim = overlay?.let {
                val level = brightness.getOrPut(id) {
                    // A separate instance: measuring resizes it.
                    WallpaperLibrary.loadThumbnail(context, id)
                        ?.let { WallpaperContrast.averageLuminance(it) }
                        ?: WallpaperContrast.DEFAULT_BRIGHTNESS
                }
                WallpaperContrast.homeScrim(context, level)
            }
            val off = isOff(id)
            thumb.alpha = if (off) 0.35f else 1f
            offBadge.visibility = if (off) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClick(id) }
        }
    }
}
