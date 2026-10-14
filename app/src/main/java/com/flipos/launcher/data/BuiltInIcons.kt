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
 * [BuiltInIconMap]) comes first: flat-colored glyphs on a 50% black tile,
 * which the one-time
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

    /**
     * Icons a since-removed automatic pass put on common apps (see
     * AppRepository.revertAutoIcons). That cleanup misses some phones - the
     * gallery's category entry isn't always its drawer entry - so one still
     * set is treated as not chosen by the user and gets replaced.
     */
    private val OLD_AUTO_NAMES = setOf(
        "call_log_112", "sms_112", "contact_112", "gallery_84",
        "calendar_112", "camera_112", "email_112", "music_112",
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
     * matching icon in picker order. A `label:` rule matches drawer labels
     * (`_` for a space), for phones whose package names aren't listed.
     */
    @Suppress("DEPRECATION") // int-flags overload kept for minSdk 21 compatibility
    fun autoAssignments(context: Context, prefs: LauncherPrefs): Map<String, String> {
        val pm = context.packageManager
        val labels = HashMap<String, String>() // drawer entry key -> lowercased label
        for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            val ai = ri.activityInfo ?: continue
            labels[ComponentName(ai.packageName, ai.name).flattenToString()] =
                ri.loadLabel(pm)?.toString()?.trim()?.lowercase().orEmpty()
        }
        val launchable = labels.keys
        // Only the user's own picks are kept: not an icon from a since-removed
        // icon pack (no longer shown), nor one the old automatic pass set.
        fun userPicked(key: String): Boolean {
            val (source, name) = prefs.getIconOverride(key) ?: return false
            return source == PACK_ID && name !in OLD_AUTO_NAMES
        }
        fun mainKey(packageName: String?): String? =
            packageName?.let { pm.getLaunchIntentForPackage(it)?.component?.flattenToString() }
        fun resolve(rule: String): List<String> {
            val (kind, value) = rule.split(":", limit = 2).takeIf { it.size == 2 } ?: return emptyList()
            if (kind == "label") {
                val wanted = value.replace('_', ' ').lowercase()
                return labels.filterValues { it == wanted }.keys.toList()
            }
            return listOfNotNull(when (kind) {
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
                }?.let { key ->
                    // A category can resolve to a screen that isn't in the
                    // app list (the gallery's often is); use its app's entry.
                    if (key in launchable) key else mainKey(ComponentName.unflattenFromString(key)?.packageName)
                }
                else -> null
            })
        }
        val result = LinkedHashMap<String, String>()
        for (entry in BuiltInIconMap.ENTRIES) {
            for (rule in entry.rules) {
                val keys = try {
                    resolve(rule)
                } catch (e: Exception) {
                    emptyList()
                }
                for (key in keys) {
                    if (key !in launchable || key in result || userPicked(key)) continue
                    result[key] = entry.name
                }
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
 * A generated icon, already sized and colored for the launcher -
 * [IconShapeRenderer] draws it at full size instead of insetting it on the
 * gray legacy tile.
 */
class FullBleedIcon(resources: Resources, bitmap: Bitmap) : BitmapDrawable(resources, bitmap)
