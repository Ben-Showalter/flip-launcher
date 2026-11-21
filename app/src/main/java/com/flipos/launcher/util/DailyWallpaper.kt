package com.flipos.launcher.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.data.WallpaperLibrary
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The daily wallpaper change: once a day the phone's wallpaper moves to the
 * next wallpaper in the rotation ([WallpaperLibrary.rotation] - every
 * built-in and gallery wallpaper the user hasn't left out in the picker),
 * in picker order, wrapping around. With only one in the rotation it stays
 * put; with none, the wallpaper is left alone.
 *
 * Checked whenever Home resumes or the screen comes on (no scheduler needed
 * on a phone that's opened many times a day), so the new one is already
 * there when the flip opens.
 */
object DailyWallpaper {

    private const val TAG = "FlipWallpaper"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var switching = false

    /** A new day: the next wallpaper in the rotation. Runs [onSwitched] on the main thread if it changed. */
    fun maybeSwitch(context: Context, onSwitched: () -> Unit = {}) {
        val prefs = LauncherPrefs(context)
        if (prefs.getDailyWallpaperDay() == today()) return
        switchToNext(context, onSwitched)
    }

    /**
     * The rotation was edited: if the wallpaper now showing was left out,
     * move to the next one straight away (so leaving one in shows it now).
     */
    fun onRotationChanged(context: Context, onSwitched: () -> Unit = {}) {
        val current = LauncherPrefs(context).getCurrentWallpaper()
        if (current == null || !WallpaperLibrary.isInRotation(context, current) || current !in WallpaperLibrary.all(context)) {
            switchToNext(context, onSwitched)
        }
    }

    /** Sets [id] now (by hand); the rotation carries on from it tomorrow. Results on the main thread. */
    fun setNow(context: Context, id: String, onDone: (ok: Boolean) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val ok = apply(appContext, id)
            mainHandler.post { onDone(ok) }
        }
    }

    /** Records [id] as set by hand today (the crop screen sets it itself). */
    fun markSet(context: Context, id: String) {
        val prefs = LauncherPrefs(context)
        prefs.setCurrentWallpaper(id)
        prefs.setDailyWallpaperDay(today())
    }

    private fun switchToNext(context: Context, onSwitched: () -> Unit) {
        if (switching) return
        val appContext = context.applicationContext
        val prefs = LauncherPrefs(appContext)
        val rotation = WallpaperLibrary.rotation(appContext)
        if (rotation.isEmpty()) return // none in the rotation: leave the wallpaper alone
        val current = prefs.getCurrentWallpaper()
        val all = WallpaperLibrary.all(appContext)
        val position = all.indexOf(current)
        val next = rotation.firstOrNull { all.indexOf(it) > position } ?: rotation.first()
        if (next == current) {
            // Only one in the rotation, and it's already showing.
            prefs.setDailyWallpaperDay(today())
            return
        }
        switching = true
        executor.execute {
            val ok = apply(appContext, next)
            mainHandler.post {
                switching = false
                if (ok) {
                    Log.d(TAG, "today's wallpaper: $next")
                    onSwitched()
                }
            }
        }
    }

    /** Sets [id] and records it. Worker thread. */
    private fun apply(context: Context, id: String): Boolean = try {
        WallpaperLibrary.apply(context, id)
        markSet(context, id)
        true
    } catch (e: Exception) {
        Log.w(TAG, "couldn't set $id", e)
        false
    }

    private fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }
}
