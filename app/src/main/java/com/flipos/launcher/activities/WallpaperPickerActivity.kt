package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.app.WallpaperManager
import android.content.Intent
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.flipos.launcher.data.BuiltInWallpapers
import com.flipos.launcher.ui.WallpaperGridAdapter

/** Lets the user set the device wallpaper from the launcher's bundled set, pulled
 * straight from [BuiltInWallpapers] — no need to leave the launcher for Photos. */
class WallpaperPickerActivity : BaseListActivity() {

    private lateinit var adapter: WallpaperGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text = getString(R.string.title_wallpaper_picker)

        adapter = WallpaperGridAdapter(onClick = { name -> applyWallpaper(name) })
        listView.layoutManager = GridLayoutManager(this, COLUMNS)
        listView.adapter = adapter
        adapter.submit(BuiltInWallpapers.names())

        softKeys.setLabels(getString(R.string.softkey_back), null, getString(R.string.wallpaper_picker_more))
        softKeys.setOnLeftClick { finish() }
        softKeys.setOnRightClick { openSystemChooser() }
        focusFirst()
    }

    private fun applyWallpaper(name: String) {
        val resId = BuiltInWallpapers.resId(this, name)
        if (resId == 0) return
        // Use the application context off the UI thread and hold no reference to
        // the Activity's resources beyond decode.
        val appContext = applicationContext
        Thread {
            try {
                val wm = WallpaperManager.getInstance(appContext)
                val bitmap = decodeSampled(
                    appContext.resources,
                    resId,
                    wm.desiredMinimumWidth.coerceAtLeast(1),
                    wm.desiredMinimumHeight.coerceAtLeast(1),
                )
                wm.setBitmap(bitmap)
                bitmap.recycle()
                runOnUiThread {
                    if (!isDestroyed) {
                        Toast.makeText(this, R.string.wallpaper_set_toast, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) Toast.makeText(this, R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Decodes [resId] downsampled to roughly [reqWidth]x[reqHeight], so setting a
     * large bundled wallpaper doesn't allocate a full-resolution bitmap (which can
     * OOM on low-end devices).
     */
    private fun decodeSampled(res: Resources, resId: Int, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, resId, bounds)
        var sample = 1
        var halfW = bounds.outWidth / 2
        var halfH = bounds.outHeight / 2
        while (halfW >= reqWidth && halfH >= reqHeight) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeResource(res, resId, opts)
    }

    private fun openSystemChooser() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.opt_set_wallpaper)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val COLUMNS = 2
    }
}
