package com.flipos.launcher.activities

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.flipos.launcher.R
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.CropView
import com.flipos.launcher.ui.HomePreview
import com.flipos.launcher.util.BackgroundLoader
import com.flipos.launcher.util.SoftKeyRouter
import com.flipos.launcher.util.WallpaperContrast
import com.flipos.launcher.util.applyFakeBold
import com.flipos.launcher.util.isStarKey
import com.flipos.launcher.util.toastIfUnknownKey
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Crops a gallery photo into the wallpaper by D-pad, the way the phone's own
 * wallpaper setter does, but with Home (clock, soft keys, D-pad pod and its
 * dimming) drawn on top - so what's set is exactly what Home will show.
 * Started by [WallpaperPickerActivity] with the photo's Uri as data;
 * finishes with RESULT_OK once the wallpaper is set.
 *
 * Keys: D-pad moves, `#`/`*` zoom in/out, 5 resets, 0 hides/shows Home,
 * OK or Left soft key sets, Right soft key/MENU opens Options, Back cancels.
 */
class WallpaperCropActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var photo: CropView
    private lateinit var scrim: View
    private lateinit var home: ImageView
    private lateinit var status: TextView

    private val loader = BackgroundLoader()
    private var busy = false

    /** Keys whose DOWN this window saw; their UP acts (see AGENTS.md §1). */
    private val pressed = mutableSetOf<Int>()

    private val softKeyRouter = SoftKeyRouter(onPrimary = { setWallpaper() }, onOptions = { showOptions() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home's accent (its OK-button tile) for the overlay.
        LauncherPrefs(this).getAccentColor().let { if (it.themeOverlayRes != 0) theme.applyStyle(it.themeOverlayRes, true) }
        theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)
        setContentView(R.layout.activity_wallpaper_crop)
        root = findViewById(R.id.crop_root)
        photo = findViewById(R.id.crop_photo)
        scrim = findViewById(R.id.crop_scrim)
        home = findViewById(R.id.crop_home)
        status = findViewById(R.id.crop_status)
        root.applyFakeBold()

        // Full screen, like Home's wallpaper: it runs under the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        // Sizes are known once laid out.
        root.post { load(uri) }
    }

    private fun load(uri: Uri) {
        val width = root.width
        val height = root.height
        if (width == 0 || height == 0) return
        home.setImageBitmap(HomePreview.renderOverlay(this, width, height))
        val appContext = applicationContext
        loader.load(
            produce = { decode(uri, width * DETAIL, height * DETAIL) },
            consume = { bitmap ->
                if (isDestroyed) return@load
                if (bitmap == null) {
                    Toast.makeText(this, R.string.crop_open_failed, Toast.LENGTH_SHORT).show()
                    finish()
                    return@load
                }
                photo.bitmap = bitmap
                val level = WallpaperContrast.averageLuminance(BitmapDrawable(appContext.resources, bitmap))
                scrim.background = WallpaperContrast.homeScrim(this, level)
                status.visibility = View.GONE
            },
        )
        // A failed decode throws inside the loader, which drops the result; catch that case too.
        root.postDelayed({
            if (!isDestroyed && photo.bitmap == null && status.visibility == View.VISIBLE && !busy) {
                Toast.makeText(this, R.string.crop_open_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, OPEN_TIMEOUT_MS)
    }

    /**
     * Decodes [uri] at no more than about [maxWidth] x [maxHeight] (enough
     * detail to zoom in), upright per its EXIF orientation. Null if it
     * can't be read.
     */
    private fun decode(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? {
        val resolver = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; the size lands in [bounds].
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = exifRotation(uri)
        // Compare in the photo's upright shape.
        val (w, h) = if (rotation == 90 || rotation == 270) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        var sample = 1
        while (w / (sample * 2) >= maxWidth && h / (sample * 2) >= maxHeight) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        if (rotation == 0) return decoded
        val upright = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true,
        )
        if (upright != decoded) decoded.recycle()
        return upright
    }

    /** The photo's EXIF rotation in degrees; 0 when unknown (or below Android 7, which can't read it from a stream). */
    private fun exifRotation(uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return try {
            contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun setWallpaper() {
        if (busy) return
        val source = photo.bitmap ?: return
        val rect = photo.visibleRect() ?: return
        if (rect.width() <= 0 || rect.height() <= 0) return
        busy = true
        status.setText(R.string.crop_setting)
        status.visibility = View.VISIBLE
        val outWidth = root.width * OUTPUT_DETAIL
        val outHeight = root.height * OUTPUT_DETAIL
        val appContext = applicationContext
        Thread {
            val ok = try {
                var cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
                // No more detail than the screen can use.
                val shrink = max(cropped.width.toFloat() / outWidth, cropped.height.toFloat() / outHeight)
                if (shrink > 1f) {
                    val scaled = Bitmap.createScaledBitmap(
                        cropped,
                        (cropped.width / shrink).roundToInt(),
                        (cropped.height / shrink).roundToInt(),
                        true,
                    )
                    if (scaled != cropped && cropped != source) cropped.recycle()
                    cropped = scaled
                }
                WallpaperManager.getInstance(appContext).setBitmap(cropped)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) {
                    Toast.makeText(this, R.string.wallpaper_set_toast, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    busy = false
                    status.visibility = View.GONE
                    Toast.makeText(this, R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun toggleHome() {
        home.visibility = if (home.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
    }

    private fun showOptions() {
        val homeShown = home.visibility == View.VISIBLE
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(
                    getString(R.string.crop_opt_set),
                    getString(if (homeShown) R.string.crop_opt_hide_home else R.string.crop_opt_show_home),
                    getString(R.string.crop_opt_reset),
                ),
            ) { _, which ->
                when (which) {
                    0 -> setWallpaper()
                    1 -> toggleHome()
                    2 -> photo.reset()
                }
            }
            .show()
    }

    // ----------------------------------------------------------- Keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (softKeyRouter.onKeyDown(keyCode, event)) return true
        val step = PAN_STEP * minOf(root.width, root.height)
        when (keyCode) {
            // Movement repeats while held.
            KeyEvent.KEYCODE_DPAD_UP -> photo.pan(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> photo.pan(0f, step)
            KeyEvent.KEYCODE_DPAD_LEFT -> photo.pan(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> photo.pan(step, 0f)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_0 -> if (event.repeatCount == 0) pressed.add(keyCode)
            else -> {
                toastIfUnknownKey(keyCode, event)
                return super.onKeyDown(keyCode, event)
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (softKeyRouter.onKeyUp(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        if (keyCode !in pressed) return super.onKeyUp(keyCode, event)
        pressed.remove(keyCode)
        if (event.isCanceled) return true
        when {
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> setWallpaper()
            keyCode == KeyEvent.KEYCODE_BACK -> if (!busy) finish()
            keyCode == KeyEvent.KEYCODE_POUND -> photo.zoomBy(ZOOM_STEP)
            isStarKey(keyCode) -> photo.zoomBy(1f / ZOOM_STEP)
            keyCode == KeyEvent.KEYCODE_5 -> photo.reset()
            keyCode == KeyEvent.KEYCODE_0 -> toggleHome()
        }
        return true
    }

    // ----------------------------------------------------------- Lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else releaseKeys()
    }

    override fun onPause() {
        super.onPause()
        releaseKeys()
    }

    override fun onDestroy() {
        loader.cancel()
        super.onDestroy()
    }

    private fun releaseKeys() {
        pressed.clear()
        softKeyRouter.reset()
    }

    /** Status and navigation bars both hidden: the photo fills the screen as it will on Home. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** Decode up to this many times the screen size, so zooming in stays sharp. */
        private const val DETAIL = 3

        /** The set wallpaper keeps up to this many times the screen's detail. */
        private const val OUTPUT_DETAIL = 2

        /** One D-pad press moves the photo this share of the screen. */
        private const val PAN_STEP = 0.08f

        /** One `#` or `*` press zooms by this much. */
        private const val ZOOM_STEP = 1.25f

        /** A photo that hasn't opened by now never will (e.g. a decode error). */
        private const val OPEN_TIMEOUT_MS = 15_000L
    }
}
