package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import com.flipos.launcher.activities.CallLogActivity

/**
 * The phone's call log screen - whichever dialer is the default - falling
 * back to the launcher's own [CallLogActivity].
 *
 * Asked for the standard way first: `ACTION_VIEW` of the call-log type
 * (`vnd.android.cursor.dir/calls`), aimed at the default dialer. Any
 * dialer that declares that filter answers it, so a new dialer needs no
 * package name here.
 */
object SystemCallLog {

    /**
     * The Kyocera dialer's own call log, for when the standard request
     * isn't answered: `.calllog.CallLogActivityKc` is the E4610's
     * (confirmed via logcat), `.app.calllog.CallLogActivityKc` the newer models'.
     */
    private val KYOCERA_COMPONENTS = listOf(
        ComponentName("com.android.dialer", "com.android.dialer.calllog.CallLogActivityKc"),
        ComponentName("com.android.dialer", "com.android.dialer.app.calllog.CallLogActivityKc"),
    )

    /** An intent for the first call log screen this phone has, in the order above. */
    fun intent(context: Context): Intent {
        val pm = context.packageManager
        val standard = Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE)
        defaultDialer(context)?.let { dialer ->
            val toDialer = Intent(standard).setPackage(dialer)
            if (resolves(pm, toDialer)) return toDialer
        }
        // No default dialer answering: any one app that does, but not
        // Android's chooser (package "android"), which a single press can't use.
        if (resolves(pm, standard)) return standard
        for (component in KYOCERA_COMPONENTS) {
            val intent = Intent(Intent.ACTION_MAIN).setComponent(component)
            if (resolves(pm, intent)) return intent
        }
        return Intent(context, CallLogActivity::class.java)
    }

    private fun defaultDialer(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 23) {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun resolves(pm: PackageManager, intent: Intent): Boolean {
        val target = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        return target.activityInfo?.packageName != "android"
    }
}
