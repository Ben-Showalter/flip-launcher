package com.flipos.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Detects installed icon pack apps and reads their `appfilter.xml` mapping
 * (the de-facto standard most icon packs — Nova/ADW/Apex-compatible — ship),
 * which maps an app's component to a drawable name inside the pack.
 *
 * Parsed mappings and decoded drawables are cached in memory for the process
 * lifetime since icon packs don't change while the launcher is running.
 */
object IconPackRepository {

    private const val ACTION_ICON_PACK = "org.adw.launcher.THEMES"

    // Guarded by [cacheLock]: loads can run on background threads for several
    // screens at once, and HashMap isn't safe under concurrent structural writes.
    private val appFilterCache = HashMap<String, Map<String, String>>()
    private val drawableCache = HashMap<String, Drawable?>()
    private val cacheLock = Any()

    /** Every installed app that declares itself as an icon pack, sorted by label. */
    fun getInstalledIconPacks(context: Context): List<IconPack> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(ACTION_ICON_PACK), 0)
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                IconPack(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    icon = ri.loadIcon(pm),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Every distinct drawable name declared by [packPackage]'s appfilter, sorted. */
    fun getDrawableNames(context: Context, packPackage: String): List<String> =
        appFilterFor(context, packPackage).values.distinct().sorted()

    /**
     * The drawable name [packPackage] maps [componentKey] to, or null if
     * unmapped. Falls back to a package-level mapping (`<item package="pkg" .../>`)
     * when the exact component isn't listed.
     */
    fun iconNameFor(context: Context, packPackage: String, componentKey: String): String? {
        val map = appFilterFor(context, packPackage)
        return map[componentKey] ?: map[componentKey.substringBefore('/')]
    }

    /** Loads a named drawable out of [packPackage]'s resources, or null if missing. */
    fun loadIcon(context: Context, packPackage: String, drawableName: String): Drawable? {
        val cacheKey = "$packPackage::$drawableName"
        synchronized(cacheLock) {
            if (drawableCache.containsKey(cacheKey)) return drawableCache[cacheKey]
        }
        val drawable = try {
            val res = context.packageManager.getResourcesForApplication(packPackage)
            val id = res.getIdentifier(drawableName, "drawable", packPackage)
            if (id == 0) null else ResourcesCompat.getDrawable(res, id, null)
        } catch (e: Exception) {
            null
        }
        synchronized(cacheLock) { drawableCache[cacheKey] = drawable }
        return drawable
    }

    /** Drops parsed appfilter maps and decoded drawables (e.g. after a pack is installed/updated). */
    fun clearCaches() = synchronized(cacheLock) {
        appFilterCache.clear()
        drawableCache.clear()
    }

    private fun appFilterFor(context: Context, packPackage: String): Map<String, String> {
        synchronized(cacheLock) { appFilterCache[packPackage]?.let { return it } }
        val loaded = loadAppFilter(context, packPackage)
        synchronized(cacheLock) { appFilterCache[packPackage] = loaded }
        return loaded
    }

    private fun loadAppFilter(context: Context, packPackage: String): Map<String, String> {
        val res = try {
            context.packageManager.getResourcesForApplication(packPackage)
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyMap()
        }

        // Most packs ship appfilter.xml as a compiled XML resource; some ship it
        // as a plain-text asset instead, so both are tried.
        val xmlId = res.getIdentifier("appfilter", "xml", packPackage)
        if (xmlId != 0) {
            return try {
                parseAppFilter(res.getXml(xmlId))
            } catch (e: Exception) {
                emptyMap()
            }
        }
        return try {
            res.assets.open("appfilter.xml").use { stream ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(stream, null)
                parseAppFilter(parser)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseAppFilter(parser: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val pkg = parser.getAttributeValue(null, "package")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (drawable != null) {
                    when {
                        // Exact-component mapping (the common case).
                        component != null -> componentKeyOf(component)?.let { map[it] = drawable }
                        // Whole-package mapping: stored under the bare package name
                        // (never contains '/'), looked up as a fallback in iconNameFor.
                        pkg != null -> map[pkg] = drawable
                    }
                }
            }
            eventType = parser.next()
        }
        return map
    }

    /**
     * Normalizes an appfilter `component` attribute to a flattened `pkg/cls` key.
     * Accepts the standard `ComponentInfo{pkg/cls}` wrapper, a bare `pkg/cls`,
     * and the `.Class` shorthand (expanded against the package). Pure string
     * parsing (no [ComponentName]) so it's unit-testable off-device.
     */
    internal fun componentKeyOf(component: String): String? {
        val inner = if (component.startsWith("ComponentInfo{")) {
            val start = component.indexOf('{')
            val end = component.indexOf('}')
            if (start == -1 || end <= start) return null
            component.substring(start + 1, end)
        } else {
            component
        }
        val slash = inner.indexOf('/')
        if (slash <= 0 || slash == inner.length - 1) return null
        val pkg = inner.substring(0, slash)
        val rawClass = inner.substring(slash + 1)
        val cls = if (rawClass.startsWith(".")) pkg + rawClass else rawClass
        return "$pkg/$cls"
    }
}
