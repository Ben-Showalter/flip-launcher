package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import com.flipos.launcher.data.WallpaperLibrary
import com.flipos.launcher.ui.HomePreview
import com.flipos.launcher.ui.WallpaperGridAdapter
import com.flipos.launcher.util.DailyWallpaper

/**
 * The daily wallpaper rotation: every built-in and gallery wallpaper
 * ([WallpaperLibrary]), each shown with Home over it. OK puts the
 * highlighted one in or out of the daily change ([DailyWallpaper]) - leave
 * just one in and it stays. Left soft key sets it now; Options adds a
 * picture from the gallery (cropped first) or removes a gallery one.
 */
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

    /** The crop screen added (and set) a wallpaper: show it in the grid. */
    private val cropScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) adapter.submit(WallpaperLibrary.all(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text = getString(R.string.title_wallpaper_picker)

        adapter = WallpaperGridAdapter(
            isOff = { !WallpaperLibrary.isInRotation(this, it) },
            onClick = { id -> toggleRotation(id) },
        )
        listView.layoutManager = GridLayoutManager(this, COLUMNS)
        listView.adapter = adapter
        // Each tile shows Home over it: the real clock, soft keys and D-pad pod.
        adapter.overlay = HomePreview.renderOverlay(this)
        adapter.submit(WallpaperLibrary.all(this))

        softKeys.setLabels(getString(R.string.softkey_set_now), getString(R.string.softkey_on_off), getString(R.string.softkey_options))
        focusFirst()
    }

    /** OK: puts [id] in or out of the daily change. */
    private fun toggleRotation(id: String) {
        val nowIn = !WallpaperLibrary.isInRotation(this, id)
        WallpaperLibrary.setInRotation(this, id, nowIn)
        adapter.refresh(id)
        val left = WallpaperLibrary.rotation(this).size
        val message = when {
            left == 0 -> R.string.wallpaper_rotation_none
            left == 1 -> R.string.wallpaper_rotation_one
            nowIn -> R.string.wallpaper_rotation_in
            else -> R.string.wallpaper_rotation_out
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        // Left out the one showing (or left just one in): change now.
        DailyWallpaper.onRotationChanged(this)
    }

    /** Left soft key: set the highlighted wallpaper now; the rotation carries on from it tomorrow. */
    override fun onPrimaryKey() {
        val id = focusedPosition().takeIf { it >= 0 }?.let { adapter.idAt(it) } ?: return
        DailyWallpaper.setNow(this, id) { ok ->
            if (!isDestroyed) {
                Toast.makeText(this, if (ok) R.string.wallpaper_set_toast else R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show()
            }
        }
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
        val focusedId = focusedPosition().takeIf { it >= 0 }?.let { adapter.idAt(it) }
        val removable = focusedId != null && WallpaperLibrary.isUser(focusedId)
        val items = listOfNotNull(
            getString(R.string.wallpaper_from_gallery),
            if (removable) getString(R.string.wallpaper_remove) else null,
        )
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> openGallery()
                    1 -> focusedId?.let { confirmRemove(it) }
                }
            }
            .show()
    }

    /** Deletes a gallery wallpaper from the list. */
    private fun confirmRemove(id: String) {
        AlertDialog.Builder(this)
            .setMessage(R.string.wallpaper_remove_confirm)
            .setPositiveButton(R.string.wallpaper_remove) { _, _ ->
                WallpaperLibrary.remove(this, id)
                adapter.submit(WallpaperLibrary.all(this))
                DailyWallpaper.onRotationChanged(this)
                focusFirst()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val COLUMNS = 2
    }
}
