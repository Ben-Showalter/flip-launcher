package com.flipos.launcher.util

import android.content.Context
import android.net.Uri

/** A contact bound to a speed-dial digit key in the phone's own dialer. */
data class SystemSpeedDialEntry(val number: String, val label: String)

/**
 * Reads speed dial slot [digit] directly from the phone's own dialer data
 * (content://speed_dial/speed_dial, backed by com.android.providers.contacts
 * - confirmed via adb on this Kyocera hardware), instead of keeping a
 * separate copy of the same assignment in our own storage. Requires
 * READ_CONTACTS; returns null on any failure (permission denied, provider
 * unavailable/different on another OEM, slot unassigned) - callers treat
 * that uniformly as "not set."
 */
fun systemSpeedDial(context: Context, digit: Int): SystemSpeedDialEntry? = try {
    context.contentResolver.query(
        Uri.parse("content://speed_dial/speed_dial"),
        arrayOf("data1", "display_name"),
        "_id = ?",
        arrayOf(digit.toString()),
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val number = cursor.getString(0)
            val name = cursor.getString(1)
            if (number.isNullOrBlank()) null else SystemSpeedDialEntry(number, name ?: number)
        } else {
            null
        }
    }
} catch (e: Exception) {
    null
}
