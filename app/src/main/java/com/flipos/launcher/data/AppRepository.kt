package com.flipos.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Reads launchable apps and individual activities from [PackageManager] and
 * resolves the launch intents the launcher fires. Stateless: callers re-query so
 * the list is always fresh after installs/uninstalls.
 */
object AppRepository {

    private const val SETTINGS_ACTIVITY = "com.flipos.launcher.activities.SettingsActivity"

    /**
     * Memoizes the shaped/wrapped icon bitmap per (component + override + pack +
     * shape + background + density), so a re-query doesn't re-run the whole
     * Palette + multi-bitmap render pipeline for every app. Stores bitmaps (not
     * Drawables) and re-wraps a fresh [BitmapDrawable] per request so callers get
     * independent bounds. Cleared via [invalidateIconCaches] on icon/pack/package
     * changes.
     */
    private const val ICON_CACHE_SIZE = 400
    private val renderedIconCache = LruCache<String, android.graphics.Bitmap>(ICON_CACHE_SIZE)

    /**
     * Clears every icon-related cache. Call after any change that alters how
     * icons look (icon shape/pack/override/background prefs) or which apps exist
     * (package add/remove/replace).
     */
    fun invalidateIconCaches() {
        renderedIconCache.evictAll()
        NotificationDotColor.clear()
        IconPackRepository.clearCaches()
    }

    /** Every launchable app except this launcher itself, sorted by label. */
    @Suppress("DEPRECATION") // int-flags overload kept for minSdk 21 compatibility
    fun getAllApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val self = context.packageName
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                // Exclude our own activities except Settings, which is
                // deliberately exported with a LAUNCHER category so it shows up
                // here like a regular app.
                if (ai.packageName == self && ai.name != SETTINGS_ACTIVITY) return@mapNotNull null
                val key = ComponentName(ai.packageName, ai.name).flattenToString()
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    activityName = ai.name,
                    icon = resolveIcon(context, prefs, key, ri.loadIcon(pm)),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Apps shown to the user (hidden ones removed). */
    fun getVisibleApps(context: Context, prefs: LauncherPrefs): List<AppInfo> {
        val hidden = prefs.getHiddenKeys()
        return getAllApps(context).filter { it.key !in hidden }
    }

    /**
     * Every launchable, exported activity declared by [packageName] — the source
     * for the activity picker, so a shortcut can target a deep screen rather than
     * only an app's main entry point.
     */
    fun getActivities(context: Context, packageName: String): List<AppInfo> {
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        return try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            (info.activities ?: emptyArray()).asSequence()
                .filter { it.exported && it.enabled }
                .map { ai ->
                    val key = ComponentName(packageName, ai.name).flattenToString()
                    AppInfo(
                        label = ai.loadLabel(pm).toString(),
                        packageName = packageName,
                        activityName = ai.name,
                        icon = resolveIcon(context, prefs, key, ai.loadIcon(pm)),
                    )
                }
                .sortedBy { it.activityName }
                .toList()
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        }
    }

    /**
     * Resolve a stored shortcut [key] (any activity component) to a displayable
     * [AppInfo], or null if it no longer exists.
     */
    fun resolveComponent(context: Context, key: String): AppInfo? {
        val component = ComponentName.unflattenFromString(key) ?: return null
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        return try {
            @Suppress("DEPRECATION")
            val ai = pm.getActivityInfo(component, 0)
            AppInfo(
                label = ai.loadLabel(pm).toString(),
                packageName = component.packageName,
                activityName = component.className,
                icon = resolveIcon(context, prefs, key, ai.loadIcon(pm)),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Swaps in a per-app icon override if one is set, else the active icon
     * pack's mapping for [componentKey] if it has one, else [fallback] — then
     * masks the result into the user's chosen icon shape. The shaped result is
     * memoized in [renderedIconCache]; the raw/unshaped path isn't cached since
     * those drawables come straight from PM per query anyway.
     */
    private fun resolveIcon(context: Context, prefs: LauncherPrefs, componentKey: String, fallback: Drawable): Drawable {
        val shape = prefs.getIconShape()
        val wrapEnabled = prefs.isIconWrapEnabled(componentKey)
        if (!wrapEnabled || shape == LauncherPrefs.IconShape.NONE) {
            return rawIcon(context, prefs, componentKey, fallback)
        }
        val legacyBg = prefs.isLegacyIconBackgroundEnabled()
        val cacheKey = iconCacheKey(context, prefs, componentKey, shape, legacyBg)
        renderedIconCache.get(cacheKey)?.let { return BitmapDrawable(context.resources, it) }

        val raw = rawIcon(context, prefs, componentKey, fallback)
        val rendered = IconShapeRenderer.render(
            context = context,
            source = raw,
            shape = shape,
            wrapEnabled = true,
            legacyBackgroundEnabled = legacyBg,
        )
        (rendered as? BitmapDrawable)?.bitmap?.let { renderedIconCache.put(cacheKey, it) }
        return rendered
    }

    private fun iconCacheKey(
        context: Context,
        prefs: LauncherPrefs,
        componentKey: String,
        shape: LauncherPrefs.IconShape,
        legacyBg: Boolean,
    ): String {
        val override = prefs.getIconOverride(componentKey)
        val pack = prefs.getActiveIconPack()
        val dpi = context.resources.displayMetrics.densityDpi
        return "$componentKey|ovr=${override?.first}:${override?.second}|pack=$pack|shape=$shape|bg=$legacyBg|dpi=$dpi"
    }

    private fun rawIcon(context: Context, prefs: LauncherPrefs, componentKey: String, fallback: Drawable): Drawable {
        val override = prefs.getIconOverride(componentKey) ?: packageIconOverride(context, prefs, componentKey)
        override?.let { (pack, name) ->
            val drawable = if (pack == BuiltInIcons.PACK_ID) {
                BuiltInIcons.loadIcon(context, name)
            } else {
                IconPackRepository.loadIcon(context, pack, name)
            }
            drawable?.let { return it }
        }
        val activePack = prefs.getActiveIconPack() ?: return fallback
        val name = IconPackRepository.iconNameFor(context, activePack, componentKey) ?: return fallback
        return IconPackRepository.loadIcon(context, activePack, name) ?: fallback
    }

    /**
     * Falls back to the icon override set on the app's main entry point, so an
     * activity picked via [ActivityPickerActivity] (a deep screen, not the app's
     * main icon) still picks up a custom icon when used as a Home shortcut.
     */
    private fun packageIconOverride(context: Context, prefs: LauncherPrefs, componentKey: String): Pair<String, String>? {
        val packageName = ComponentName.unflattenFromString(componentKey)?.packageName ?: return null
        val mainKey = context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
            ?: return null
        if (mainKey == componentKey) return null
        return prefs.getIconOverride(mainKey)
    }

    /**
     * Intent that launches the activity identified by [key]. Uses a bare explicit
     * component (no MAIN/LAUNCHER category) so it works for any exported activity,
     * not just an app's home-screen entry.
     */
    fun launchIntentFor(key: String): Intent? {
        val component = ComponentName.unflattenFromString(key) ?: return null
        // RESET_TASK_IF_NEEDED used to ride along with NEW_TASK here, but combined
        // with our own same-affinity activities (e.g. Launcher Settings, which is
        // exported so it can appear in the drawer) it makes the system swallow the
        // launch instead of pushing the activity onto the current task.
        return Intent()
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
