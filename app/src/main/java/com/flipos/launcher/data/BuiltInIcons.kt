package com.flipos.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.flipos.launcher.util.CategoryApps

/**
 * Icons bundled with the launcher itself (res/drawable-nodpi), offered in the
 * per-app icon picker. The generated "ic_" set (tools/icons, see
 * [BuiltInIconMap]) comes first: full-bleed gradient tiles that the launcher
 * clips to its squircle like the stock Kyocera icons, and that the one-time
 * "Use Flip Launcher icons" action ([autoAssignments]) puts on matching apps. The
 * older hand-made icons follow.
 */
object BuiltInIcons {

    /** Sentinel "pack" id stored in [LauncherPrefs] icon overrides for these. */
    const val PACK_ID = "flip_launcher_built_in"

    private const val GENERATED_PREFIX = "ic_"

    private val LEGACY_NAMES = listOf(
        "calendar_112",
        "call_log_112",
        "camera_112",
        "clock_112",
        "contact_112",
        "email_112",
        "fm_112",
        "gallery_84",
        "music_112",
        "note_112",
        "search_112",
        "settings_112",
        "sms_112",
        "soundrecorder_112",
        "unitconverter_112",
        "video_112",
    )

    fun names(): List<String> = BuiltInIconMap.ENTRIES.map { it.name } + LEGACY_NAMES

    fun loadIcon(context: Context, name: String): Drawable? {
        val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (resId == 0) return null
        val drawable = ContextCompat.getDrawable(context, resId) ?: return null
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        return if (name.startsWith(GENERATED_PREFIX) && bitmap != null) {
            FullBleedIcon(context.resources, bitmap)
        } else {
            drawable
        }
    }

    /**
     * What the "Use Flip Launcher icons" action would set: drawer entry key -> icon
     * name, for every launchable entry a [BuiltInIconMap] rule matches that
     * doesn't already have an icon the user picked. Each entry gets the first
     * matching icon in picker order.
     */
    @Suppress("DEPRECATION") // int-flags overload kept for minSdk 21 compatibility
    fun autoAssignments(context: Context, prefs: LauncherPrefs): Map<String, String> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { ri -> ri.activityInfo?.let { ComponentName(it.packageName, it.name).flattenToString() } }
            .toSet()
        fun mainKey(packageName: String?): String? =
            packageName?.let { pm.getLaunchIntentForPackage(it)?.component?.flattenToString() }
        fun resolve(rule: String): String? {
            val (kind, value) = rule.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
            return when (kind) {
                "pkg" -> mainKey(value)
                "cmp" -> ComponentName.unflattenFromString(value)?.flattenToString()
                "cat" -> when (value) {
                    "dialer" -> CategoryApps.dialerKey(context)
                    "sms" -> CategoryApps.smsKey(context)
                    "contacts" -> CategoryApps.contactsKey(context)
                    "camera" -> CategoryApps.cameraKey(context)
                    "gallery" -> CategoryApps.galleryKey(context)
                    "calendar" -> CategoryApps.calendarKey(context)
                    "email" -> CategoryApps.emailKey(context)
                    "music" -> CategoryApps.musicKey(context)
                    "settings" -> mainKey(CategoryApps.systemSettingsPackage(context))
                    else -> null
                }
                else -> null
            }
        }
        val result = LinkedHashMap<String, String>()
        for (entry in BuiltInIconMap.ENTRIES) {
            for (rule in entry.rules) {
                val key = try {
                    resolve(rule)
                } catch (e: Exception) {
                    null
                } ?: continue
                if (key !in launchable || key in result || prefs.getIconOverride(key) != null) continue
                result[key] = entry.name
            }
        }
        return result
    }

    /** Writes [assignments] (from [autoAssignments]) as ordinary per-app icon choices. */
    fun applyAssignments(prefs: LauncherPrefs, assignments: Map<String, String>) {
        assignments.forEach { (key, name) -> prefs.setIconOverride(key, PACK_ID, name) }
        AppRepository.invalidateIconCaches()
    }
}

/**
 * A generated tile that fills the whole icon shape, like a stock adaptive
 * icon - [IconShapeRenderer] masks it at full size instead of insetting it
 * on the gray legacy tile.
 */
class FullBleedIcon(resources: Resources, bitmap: Bitmap) : BitmapDrawable(resources, bitmap)
