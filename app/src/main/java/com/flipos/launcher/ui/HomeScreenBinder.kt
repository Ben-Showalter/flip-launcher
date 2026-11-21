package com.flipos.launcher.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.flipos.launcher.R
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.data.IconShapeRenderer
import com.flipos.launcher.data.LauncherPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fills Home's layout (activity_main) - clock, soft-key labels and icons,
 * D-pad pod - from the user's settings. Shared by Home itself and the
 * wallpaper picker's preview ([HomePreview]), so the preview always shows
 * exactly what Home will.
 */
object HomeScreenBinder {

    /**
     * Formatters are built on every call (about once a minute on Home): a
     * SimpleDateFormat keeps the time zone and language it was created with,
     * and Home lives until reboot, so cached ones missed time-zone and
     * language changes. Follows the system 12/24-hour setting.
     */
    fun bindClock(context: Context, root: View) {
        val clock = root.findViewById<TextView>(R.id.clock)
        val ampm = root.findViewById<TextView>(R.id.ampm)
        val now = Date()
        val locale = Locale.getDefault()
        fun format(pattern: String) = SimpleDateFormat(pattern, locale).format(now)
        fun best(skeleton: String) = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        if (android.text.format.DateFormat.is24HourFormat(context)) {
            ampm.visibility = View.GONE
            clock.text = format(best("Hmm"))
        } else {
            ampm.visibility = View.VISIBLE
            ampm.text = format("a")
            // The locale's 12-hour pattern minus its AM/PM, which has its own view.
            clock.text = format(best("hmm").replace(Regex("\\s*a\\s*"), "").trim())
        }
        root.findViewById<TextView>(R.id.weekday).text = format("EEEE")
        root.findViewById<TextView>(R.id.date_line).text = format(best("MMMd"))
    }

    fun bindLeftKey(context: Context, root: View, prefs: LauncherPrefs) {
        val key = prefs.getLeftKeyApp()
        val label = key?.let { AppRepository.resolveComponent(context, it)?.label }
            ?: context.getString(R.string.softkey_contacts)
        root.findViewById<TextView>(R.id.softkey_left).text = label
        bindKeyIcon(context, root.findViewById(R.id.softkey_left_icon), key)
    }

    fun bindRightKey(context: Context, root: View, prefs: LauncherPrefs) {
        val key = prefs.getRightKeyApp()
        val label = key?.let { AppRepository.resolveComponent(context, it)?.label }
            ?: context.getString(R.string.softkey_messages)
        root.findViewById<TextView>(R.id.softkey_right).text = label
        bindKeyIcon(context, root.findViewById(R.id.softkey_right_icon), key)
    }

    /** The D-pad shortcut pod's four icons; the pod itself always stays visible (it also houses the OK button). */
    fun bindDpad(context: Context, root: View, prefs: LauncherPrefs) {
        bindKeyIcon(context, root.findViewById(R.id.dpad_icon_up), prefs.getDpadUpApp())
        bindKeyIcon(context, root.findViewById(R.id.dpad_icon_down), prefs.getDpadDownApp())
        bindKeyIcon(context, root.findViewById(R.id.dpad_icon_left), prefs.getDpadLeftApp())
        bindKeyIcon(context, root.findViewById(R.id.dpad_icon_right), prefs.getDpadRightApp())
    }

    /** Binds [key]'s squircle-masked icon into [view] and shows it, or hides [view] when [key] is null. Returns whether it was bound. */
    fun bindKeyIcon(context: Context, view: ImageView, key: String?): Boolean {
        val icon = key?.let { AppRepository.resolveRawIcon(context, it) }
        if (icon == null) {
            view.visibility = View.GONE
            return false
        }
        view.setImageDrawable(
            IconShapeRenderer.render(
                context = context,
                source = icon,
                shape = LauncherPrefs.IconShape.SQUIRCLE,
                wrapEnabled = true,
                legacyBackgroundEnabled = true,
            ),
        )
        view.visibility = View.VISIBLE
        return true
    }
}
