package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.flipos.launcher.activities.CallLogActivity

/** The phone's own call log screen, falling back to the launcher's [CallLogActivity]. */
object SystemCallLog {

    /**
     * The Kyocera dialer's own call log, tried in order:
     * `.calllog.CallLogActivityKc` is the E4610's (confirmed via logcat),
     * `.app.calllog.CallLogActivityKc` the newer models'.
     */
    val COMPONENTS = listOf(
        ComponentName("com.android.dialer", "com.android.dialer.calllog.CallLogActivityKc"),
        ComponentName("com.android.dialer", "com.android.dialer.app.calllog.CallLogActivityKc"),
    )

    /** An intent for the first call log screen present on this phone. */
    @Suppress("DEPRECATION")
    fun intent(context: Context): Intent {
        for (component in COMPONENTS) {
            val intent = Intent(Intent.ACTION_MAIN).setComponent(component)
            if (context.packageManager.resolveActivity(intent, 0) != null) return intent
        }
        return Intent(context, CallLogActivity::class.java)
    }
}
