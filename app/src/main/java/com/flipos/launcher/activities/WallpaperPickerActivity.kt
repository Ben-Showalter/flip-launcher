package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import com.flipos.launcher.data.BuiltInWallpapers
import com.flipos.launcher.ui.HomePreview
import com.flipos.launcher.ui.WallpaperGridAdapter
import com.flipos.launcher.util.DailyWallpaper

/** Lets the user set the device wallpaper from the launcher's bundled set, pulled
 * straight from [BuiltInWallpapers] — no need to leave the launcher for Photos. */
class WallpaperPickerActivity : BaseListActivity() {

    private lateinit var adapter: WallpaperGridAdapter

    /** A photo picked from the gallery goes to the crop screen. */
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            cropScreen.launch(
                Intent(this, WallpaperCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }

    /** The crop screen set the wallpaper: done here too. */
    private val cropScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text = getString(R.string.title_wallpaper_picker)

        adapter = WallpaperGridAdapter(onClick = { name -> applyWallpaper(name) })
        listView.layoutManager = GridLayoutManager(this, COLUMNS)
        listView.adapter = adapter
        // Each tile shows Home over it: the real clock, soft keys and D-pad pod.
        adapter.overlay = HomePreview.renderOverlay(this)
        adapter.submit(BuiltInWallpapers.names())

        softKeys.setLabels(null, null, getString(R.string.softkey_options))
        focusFirst()
    }

    private fun applyWallpaper(name: String) {
        if (BuiltInWallpapers.resId(this, name) == 0) return
        // Picking one by hand ends the daily change (Appearance → Change wallpaper daily).
        val dailyWasOn = DailyWallpaper.turnOffForManualPick(this)
        val appContext = applicationContext
        Thread {
            val ok = try {
                BuiltInWallpapers.apply(appContext, name)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) {
                    Toast.makeText(
                        this,
                        if (dailyWasOn) R.string.wallpaper_set_daily_off_toast else R.string.wallpaper_set_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                } else {
                    Toast.makeText(this, R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * The Gallery's photo picker, or the general image picker where there's
     * none. The picked photo comes with read access, so no storage
     * permission is needed.
     */
    private fun openGallery() {
        val attempts = listOf(
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),
        )
        for (intent in attempts) {
            try {
                galleryPicker.launch(intent)
                return
            } catch (e: Exception) {
                // Nothing handles this one; try the next.
            }
        }
        Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsKey() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.wallpaper_from_gallery))) { _, which ->
                if (which == 0) openGallery()
            }
            .show()
    }

    companion object {
        private const val COLUMNS = 2
    }
}
