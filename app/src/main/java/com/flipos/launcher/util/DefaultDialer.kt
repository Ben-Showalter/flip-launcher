package com.flipos.launcher.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager

/** The default phone app's package (TelecomManager), or null when there's none or it can't be read. */
fun defaultDialerPackage(context: Context): String? = try {
    if (Build.VERSION.SDK_INT >= 23) {
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
    } else {
        null
    }
} catch (e: Exception) {
    null
}

/**
 * This call/dial/call-log intent aimed at the default phone app, when that
 * app handles it; otherwise unchanged. With a second phone app installed (a
 * VoIP dialer) a plain `CALL tel:` or `DIAL tel:` makes Android ask which app
 * to use - awkward on a keypad - so every call the launcher starts goes
 * through here. Anything the default app doesn't handle (e.g. `voicemail:`,
 * which only the stock dialer knows) still goes out as before.
 */
@Suppress("DEPRECATION")
fun Intent.forDefaultDialer(context: Context): Intent {
    val dialer = defaultDialerPackage(context) ?: return this
    val aimed = Intent(this).setPackage(dialer)
    val target = context.packageManager.resolveActivity(aimed, PackageManager.MATCH_DEFAULT_ONLY)
    return if (target != null) aimed else this
}
