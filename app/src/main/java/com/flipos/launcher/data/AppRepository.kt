package com.flipos.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.LruCache
import com.flipos.launcher.util.CategoryApps
import com.flipos.launcher.util.KyoceraShortcuts

/**
 * Reads launchable apps and individual activities from [PackageManager] and
 * resolves the launch intents the launcher fires. Stateless: callers re-query so
 * the list is always fresh after installs/uninstalls.
 */
object AppRepository {

    private const val SETTINGS_ACTIVITY = "com.flipos.launcher.activities.SettingsActivity"
    private const val NOTICES_ACTIVITY = "com.flipos.launcher.activities.NoticesActivity"

    private const val CALL_LOG_LABEL = "Call Log"

    /** Kyocera's Notepad app (confirmed via logcat on the E4610). */
    private const val NOTEPAD_PACKAGE = "jp.kyocera.memo"

    /** Kyocera's Gallery launcher entry (on the default-visible whitelist below). */
    private const val KYOCERA_GALLERY_PACKAGE = "jp.kyocera.gallery.launcher"

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

    /**
     * Every launchable app except this launcher itself, alphabetical order
     * broken by the user's custom app-grid position (see
     * [LauncherPrefs.getAppOrder]) where one is set - positioned apps sort by
     * that position, everything else falls alphabetically after them.
     */
    @Suppress("DEPRECATION") // int-flags overload kept for minSdk 21 compatibility
    fun getAllApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val self = context.packageName
        // Exclude our own activities except Notices and the Kyocera menu
        // shortcuts, which are deliberately exported with a LAUNCHER category
        // so they show up here like a regular app. (Our Settings hub is
        // reached through the phone's Settings entry's chooser instead.)
        val resolveInfos = pm.queryIntentActivities(intent, 0).filter { ri ->
            val ai = ri.activityInfo ?: return@filter false
            ai.packageName != self || ai.name == NOTICES_ACTIVITY ||
                ai.name in KyoceraShortcuts.ALIASES
        }
        // Must run before icons are resolved below, so the very first
        // render after the cleanup already shows the stock icons.
        if (!prefs.isAutoIconsReverted()) {
            if (prefs.isBuiltInIconsApplied()) revertAutoIcons(context, prefs, resolveInfos)
            prefs.setAutoIconsReverted()
        }
        if (!prefs.isUnlistedAppsHidden()) {
            applyDefaultHiddenApps(context, prefs, resolveInfos)
            prefs.setUnlistedAppsHidden()
        }
        val apps = resolveInfos.asSequence()
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                val key = ComponentName(ai.packageName, ai.name).flattenToString()
                AppInfo(
                    label = labelFor(pm, ai, ri.loadLabel(pm)),
                    packageName = ai.packageName,
                    activityName = ai.name,
                    icon = resolveIcon(context, prefs, key, ri.loadIcon(pm)),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
        return applyAppOrder(context, prefs, apps)
    }

    /**
     * A never-blank display label: the activity's own [loaded] label, else
     * "Call Log" for a call-log activity (the E4610 dialer's call-log drawer
     * entry ships with an empty label), else its app's label, else the
     * package name.
     */
    private fun labelFor(pm: PackageManager, ai: ActivityInfo, loaded: CharSequence?): String {
        loaded?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (ai.name.contains("calllog", ignoreCase = true)) return CALL_LOG_LABEL
        ai.applicationInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return ai.packageName
    }

    /**
     * One-time cleanup (see [LauncherPrefs.isAutoIconsReverted]) for installs
     * where a since-removed pass automatically put our own [BuiltInIcons] on
     * a handful of common apps. Clears each of those overrides only if it is
     * still exactly what that pass wrote, so an icon the user picked
     * themselves is kept.
     */
    private fun revertAutoIcons(context: Context, prefs: LauncherPrefs, resolveInfos: List<ResolveInfo>) {
        fun revert(componentKey: String?, iconName: String) {
            val pkg = componentKey?.let { ComponentName.unflattenFromString(it)?.packageName } ?: return
            val ai = resolveInfos.firstOrNull { it.activityInfo?.packageName == pkg }?.activityInfo ?: return
            val key = ComponentName(ai.packageName, ai.name).flattenToString()
            if (prefs.getIconOverride(key) == (BuiltInIcons.PACK_ID to iconName)) prefs.clearIconOverride(key)
        }

        revert(CategoryApps.dialerKey(context), "call_log_112")
        revert(CategoryApps.smsKey(context), "sms_112")
        revert(CategoryApps.contactsKey(context), "contact_112")
        revert(CategoryApps.galleryKey(context), "gallery_84")
        revert(CategoryApps.calendarKey(context), "calendar_112")
        revert(CategoryApps.cameraKey(context), "camera_112")
        revert(CategoryApps.emailKey(context), "email_112")
        revert(CategoryApps.musicKey(context), "music_112")
        invalidateIconCaches()
    }

    /**
     * Fixed whitelist of built-in/OEM app packages that stay visible by
     * default (see [applyDefaultHiddenApps]) - ordered exactly as the user
     * confirmed them, one at a time, from a logcat of themselves opening
     * every app on the device.
     */
    private val DEFAULT_VISIBLE_PACKAGES = setOf(
        "jp.kyocera.settings.nfp", "com.android.calendar", "jp.kyocera.filemanager.launcher",
        "jp.kyocera.gallery.launcher", "com.kyocera.calculator2", "com.android.dialer",
        "com.android.contacts", "com.flipweather.app", "jp.kyocera.camera", "com.kyocera.alarm",
        "com.kyocera.musicplayer", "jp.kyocera.memo", "com.kyocera.stopwatch", "com.kyocera.timer",
        "jp.kyocera.kc_soundrecorder", "com.kyocera.flashlight", "com.turbotranslate.app",
        "com.turbotext.app", "org.matchat.client", "com.kyocera.worldclock",
    )

    /**
     * One-time (see [LauncherPrefs.isUnlistedAppsHidden]) default that hides
     * every app not on [DEFAULT_VISIBLE_PACKAGES], not the device's own
     * dialer/SMS/contacts apps, and not user-installed (i.e. not a system
     * app) - a direct request to declutter the drawer down to a known-good
     * set on an already-set-up device. Purely a one-time write into the same
     * [LauncherPrefs.setHidden] store the manual Hide Apps screen already
     * uses, so it never re-runs and never overrides anything the user
     * un-hides afterward.
     */
    private fun applyDefaultHiddenApps(context: Context, prefs: LauncherPrefs, resolveInfos: List<ResolveInfo>) {
        val alwaysVisible = DEFAULT_VISIBLE_PACKAGES + context.packageName +
            listOfNotNull(CategoryApps.dialerKey(context), CategoryApps.smsKey(context), CategoryApps.contactsKey(context))
                .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
        for (ri in resolveInfos) {
            val ai = ri.activityInfo ?: continue
            val packageName = ai.packageName
            if (packageName in alwaysVisible) continue
            val appFlags = ai.applicationInfo.flags
            val isSystemApp = appFlags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                appFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (!isSystemApp) continue // user-installed - always stays visible
            prefs.setHidden(ComponentName(packageName, ai.name).flattenToString(), true)
        }
    }

    /**
     * Applies (and, on the very first call ever, seeds) the user's custom
     * app-grid ordering on top of [apps]' alphabetical order. A stable sort
     * keyed by stored position keeps unpositioned apps in their existing
     * (alphabetical) relative order, appended after every positioned one.
     */
    private fun applyAppOrder(context: Context, prefs: LauncherPrefs, apps: List<AppInfo>): List<AppInfo> {
        if (!prefs.isAppOrderSeeded() || !prefs.isAppOrderV4Seeded()) {
            // A first install, or one seeded with the older default order:
            // (re)apply the current default once. Manual moves made before
            // this update are replaced this one time only.
            prefs.setAppOrder(seedAppOrder(context, prefs, apps))
            prefs.setAppOrderSeeded()
            prefs.setAppOrderV4Seeded()
            prefs.setSettingsSeedFixed()
        } else if (!prefs.isSettingsSeedFixed()) {
            fixSettingsSeed(context, prefs, apps)
        }
        val order = prefs.getAppOrder()
        if (order.isEmpty()) return apps
        val position = HashMap<String, Int>(order.size)
        order.forEachIndexed { index, key -> position[key] = index }
        return apps.sortedBy { position[it.key] ?: Int.MAX_VALUE }
    }

    /**
     * One-time starting order (see [LauncherPrefs.isAppOrderSeeded] /
     * [LauncherPrefs.isAppOrderV4Seeded]) - exactly one 3x3 grid page:
     * Contacts, Notices, Messaging, Gallery, Media Center, Notepad, Quick
     * Settings, the real Settings app, Tools, so Media Center lands in the
     * center cell the drawer focuses first. Everything else stays
     * alphabetical after them. Anything this phone doesn't have (the three
     * Kyocera menu shortcuts and Notepad exist only on Kyocera) is simply
     * skipped, not left as a gap. Afterward the order is fully freeform.
     */
    private fun seedAppOrder(context: Context, prefs: LauncherPrefs, apps: List<AppInfo>): List<String> {
        // Search every app, hidden or not - the old one-time "hide unlisted
        // apps" default hid Gallery/Settings on phones whose packages weren't
        // on its whitelist - and unhide whatever gets picked below.
        val visible = apps
        fun activityKey(activityName: String): String? = visible.firstOrNull { it.activityName == activityName }?.key
        fun keyPackage(componentKey: String?): String? = componentKey?.let { ComponentName.unflattenFromString(it)?.packageName }

        return listOfNotNull(
            pickApp(
                context, visible,
                packages = listOfNotNull(keyPackage(CategoryApps.contactsKey(context)), "com.android.contacts"),
                intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
                label = "Contacts",
            ),
            activityKey(NOTICES_ACTIVITY),
            pickApp(
                context, visible,
                packages = listOfNotNull(
                    keyPackage(CategoryApps.smsKey(context)), "com.android.mms", "com.android.messaging",
                ),
                intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                label = "Messaging",
            ),
            pickApp(
                context, visible,
                packages = listOf(KYOCERA_GALLERY_PACKAGE),
                intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
                label = "Gallery",
            ),
            activityKey(KyoceraShortcuts.MEDIA_CENTER_ALIAS),
            pickApp(context, visible, packages = listOf(NOTEPAD_PACKAGE), intent = null, label = "Notepad"),
            activityKey(KyoceraShortcuts.QUICK_SETTINGS_ALIAS),
            pickApp(
                context, visible,
                packages = listOfNotNull(
                    "jp.kyocera.settings.nfp", "com.android.settings", CategoryApps.systemSettingsPackage(context),
                ),
                intent = Intent(Settings.ACTION_SETTINGS),
                label = "Settings",
            ),
            activityKey(KyoceraShortcuts.TOOLS_ALIAS),
        ).distinct().also { picked ->
            val hidden = prefs.getHiddenKeys()
            picked.filter { it in hidden }.forEach { prefs.setHidden(it, false) }
        }
    }

    /**
     * The first visible app matching, in order: one of [packages]; any
     * handler of [intent] (skipping Android's own chooser, which
     * `resolveActivity` returns whenever several apps match with no
     * default); or an app labeled exactly [label]. Our own activities are
     * excluded from the label match so "Settings" can't pick ours.
     */
    @Suppress("DEPRECATION") // int-flags overload kept for minSdk 21 compatibility
    private fun pickApp(
        context: Context,
        visible: List<AppInfo>,
        packages: List<String>,
        intent: Intent?,
        label: String,
    ): String? {
        for (pkg in packages) {
            visible.firstOrNull { it.packageName == pkg }?.let { return it.key }
        }
        if (intent != null) {
            val handlers = try {
                context.packageManager.queryIntentActivities(intent, 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in handlers) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == CategoryApps.CHOOSER_PACKAGE) continue
                visible.firstOrNull { it.packageName == pkg }?.let { return it.key }
            }
        }
        return visible.firstOrNull { it.packageName != context.packageName && it.label.equals(label, ignoreCase = true) }?.key
    }

    /** Matches [packageName] against [apps] by package, resolving a category resolver's result to a real entry's component key. */
    private fun packageNameKey(apps: List<AppInfo>, packageName: String?): String? =
        packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.key }

    /**
     * One-time correction for a device that already seeded (see
     * [LauncherPrefs.isSettingsSeedFixed]) before the app grid's seeded slot
     * switched from our own Settings hub to the real system Settings app:
     * swaps that one stored entry in place (same position), preserving any
     * manual reordering the user has done since, rather than re-seeding
     * everything from scratch.
     *
     * [LauncherPrefs.setSettingsSeedFixed] is only persisted once a
     * correction genuinely happens, or once it's confirmed there's
     * genuinely nothing to fix (our own Settings key isn't in the stored
     * order at all) - every other early return (our own Settings activity
     * isn't in the current app list; [CategoryApps.systemSettingsPackage]
     * can't resolve on this device yet) leaves the flag unset so a future
     * call retries automatically, instead of a resolution failure
     * permanently giving up after a single attempt.
     */
    private fun fixSettingsSeed(context: Context, prefs: LauncherPrefs, apps: List<AppInfo>) {
        val ownSettingsKey = apps.firstOrNull { it.activityName == SETTINGS_ACTIVITY }?.key ?: return
        val order = prefs.getAppOrder()
        val position = order.indexOf(ownSettingsKey)
        if (position < 0) {
            prefs.setSettingsSeedFixed()
            return
        }
        val systemSettingsKey = packageNameKey(apps, CategoryApps.systemSettingsPackage(context)) ?: return
        if (systemSettingsKey in order) return
        prefs.setAppOrder(order.toMutableList().apply { set(position, systemSettingsKey) })
        prefs.setSettingsSeedFixed()
    }

    /** Apps shown to the user (hidden ones removed). */
    fun getVisibleApps(context: Context, prefs: LauncherPrefs): List<AppInfo> {
        val all = getAllApps(context)
        // Read after getAllApps(): its one-time order seeding may unhide apps.
        val hidden = prefs.getHiddenKeys()
        return all.filter { it.key !in hidden }
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
                        label = labelFor(pm, ai, ai.loadLabel(pm)),
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
                label = labelFor(pm, ai, ai.loadLabel(pm)),
                packageName = component.packageName,
                activityName = component.className,
                icon = resolveIcon(context, prefs, key, ai.loadIcon(pm)),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Resolves [key] to its icon exactly like [resolveComponent] (per-app
     * override / active icon pack, falling back to the app's own icon), but
     * skips the final shape-masking step — for callers that need to apply a
     * *different* shape than the user's global [LauncherPrefs.IconShape]
     * (e.g. the Home D-pad shortcut pod, always squircle regardless of the
     * global setting).
     */
    fun resolveRawIcon(context: Context, key: String): Drawable? {
        val component = ComponentName.unflattenFromString(key) ?: return null
        val pm = context.packageManager
        val prefs = LauncherPrefs(context)
        return try {
            @Suppress("DEPRECATION")
            val ai = pm.getActivityInfo(component, 0)
            rawIcon(context, prefs, key, ai.loadIcon(pm))
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
        val legacyBg = true
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
