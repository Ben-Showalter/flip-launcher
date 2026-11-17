package com.flipos.launcher.util

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.flipos.launcher.data.BuiltInWallpapers
import com.flipos.launcher.data.LauncherPrefs
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Appearance → Change wallpaper daily: once a day, the phone's wallpaper
 * moves to the next of the launcher's built-in ones, in order, wrapping
 * around. Checked whenever Home resumes or the screen comes on (no
 * scheduler needed on a phone that's opened many times a day), so the new
 * one is already there when the flip opens.
 *
 * It switches itself off when the user picks a wallpaper themselves - in
 * the launcher's picker or crop screen ([turnOffForManualPick]), or anywhere
 * else (Android 7+: the system wallpaper id no longer matches the one this
 * set).
 */
object DailyWallpaper {

    private const val TAG = "FlipWallpaper"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var switching = false

    /** Turns the daily change on and sets today's wallpaper right away. */
    fun enable(context: Context, onSwitched: () -> Unit = {}) {
        val prefs = LauncherPrefs(context)
        prefs.setDailyWallpaperEnabled(true)
        prefs.setDailyWallpaperDay(0)
        maybeSwitch(context, onSwitched)
    }

    fun disable(context: Context) = LauncherPrefs(context).setDailyWallpaperEnabled(false)

    /** The user set a wallpaper by hand: stop changing it. Returns whether it was on. */
    fun turnOffForManualPick(context: Context): Boolean {
        val prefs = LauncherPrefs(context)
        if (!prefs.isDailyWallpaperEnabled()) return false
        prefs.setDailyWallpaperEnabled(false)
        Log.d(TAG, "turned off: wallpaper picked by hand")
        return true
    }

    /**
     * If it's on and today's wallpaper isn't set yet, sets the next one off
     * the main thread, then runs [onSwitched] on the main thread.
     */
    fun maybeSwitch(context: Context, onSwitched: () -> Unit = {}) {
        val appContext = context.applicationContext
        val prefs = LauncherPrefs(appContext)
        if (!prefs.isDailyWallpaperEnabled() || switching) return
        val today = today()
        if (prefs.getDailyWallpaperDay() == today) return
        // Set somewhere else since our last change (phone settings, Gallery): leave it.
        val ourId = prefs.getDailyWallpaperId()
        val currentId = currentWallpaperId(appContext)
        if (prefs.getDailyWallpaperDay() != 0 && ourId != -1 && currentId != -1 && currentId != ourId) {
            prefs.setDailyWallpaperEnabled(false)
            Log.d(TAG, "turned off: wallpaper changed elsewhere (id $currentId, ours $ourId)")
            return
        }
        val names = BuiltInWallpapers.names()
        if (names.isEmpty()) return
        val index = (prefs.getDailyWallpaperIndex() + 1).floorMod(names.size)
        switching = true
        executor.execute {
            val ok = try {
                BuiltInWallpapers.apply(appContext, names[index])
                true
            } catch (e: Exception) {
                Log.w(TAG, "couldn't set ${names[index]}", e)
                false
            }
            mainHandler.post {
                switching = false
                if (ok) {
                    prefs.setDailyWallpaperIndex(index)
                    prefs.setDailyWallpaperDay(today)
                    prefs.setDailyWallpaperId(currentWallpaperId(appContext))
                    Log.d(TAG, "today's wallpaper: ${names[index]}")
                    onSwitched()
                }
            }
        }
    }

    private fun Int.floorMod(n: Int): Int = ((this % n) + n) % n

    private fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun currentWallpaperId(context: Context): Int = try {
        if (Build.VERSION.SDK_INT >= 24) WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) else -1
    } catch (e: Exception) {
        -1
    }
}
