package com.flipos.launcher.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.R
import com.flipos.launcher.data.BuiltInWallpapers
import com.flipos.launcher.util.WallpaperContrast

/**
 * Grid of bundled wallpaper thumbnails for the Wallpaper Picker, each shown
 * as Home would look with it: Home's dimming for that wallpaper's brightness,
 * plus [overlay] (see [HomePreview]) - the clock and soft keys.
 */
class WallpaperGridAdapter(
    private val onClick: (name: String) -> Unit,
) : RecyclerView.Adapter<WallpaperGridAdapter.VH>() {

    private val names = ArrayList<String>()

    /** Wallpaper name -> brightness, measured once each for Home's scrim. */
    private val brightness = HashMap<String, Float>()

    var overlay: Bitmap? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, names.size)
        }

    fun submit(list: List<String>) {
        submitWithDiff(names, list) { a, b -> a == b }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wallpaper_grid, parent, false)
        return VH(view)
    }

    override fun getItemCount() = names.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(names[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: PortraitThumbView = itemView.findViewById(R.id.thumb)

        fun bind(name: String) {
            val context = itemView.context
            thumb.setImageDrawable(BuiltInWallpapers.loadThumbnail(context, name))
            thumb.overlay = overlay
            thumb.scrim = overlay?.let {
                val level = brightness.getOrPut(name) {
                    // A separate instance: measuring resizes it.
                    BuiltInWallpapers.loadThumbnail(context, name)
                        ?.let { WallpaperContrast.averageLuminance(it) }
                        ?: WallpaperContrast.DEFAULT_BRIGHTNESS
                }
                WallpaperContrast.homeScrim(context, level)
            }
            itemView.setOnClickListener { onClick(name) }
        }
    }
}
