package com.flipos.launcher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.flipos.launcher.R

/** A contact bound to a speed-dial digit key in the phone's own dialer. */
data class SystemSpeedDialEntry(val number: String, val label: String)

/**
 * Reads speed dial slot [digit] directly from the phone's own dialer data
 * (content://speed_dial/speed_dial, backed by com.android.providers.contacts
 * - confirmed via adb on this Kyocera hardware), instead of keeping a
 * separate copy of the same assignment in our own storage. Requires
 * READ_CONTACTS. Distinguishes an empty slot ([SystemSpeedDialResult.Unset])
 * from data this app can't read at all ([SystemSpeedDialResult.Unsupported] -
 * permission denied, no provider, or the E4610's empty table), so the caller
 * can fall back to the launcher's own slots in the latter case.
 *
 * Projection is deliberately null (select everything) rather than naming
 * "data1"/"display_name" up front: SpeedDialProvider's internal
 * SQLiteQueryBuilder has a projection allowlist that rejects those column
 * names outright (IllegalArgumentException: Invalid column data1), even
 * though the very same names come back fine as columns in an unrestricted
 * query - confirmed both via adb and via a real on-device crash log.
 *
 * The selection column is qualified as "speed_table._id" (the exact alias
 * the provider's own internal query uses for its base speed_dial table,
 * also seen in that same crash log) rather than a bare "_id" - the
 * provider's query joins five tables, several of which have their own
 * _id column, so an unqualified "_id = ?" fails with
 * "ambiguous column name: _id".
 */
fun systemSpeedDial(context: Context, digit: Int): SystemSpeedDialResult = try {
    val cursor = context.contentResolver.query(
        SPEED_DIAL_URI,
        null,
        "speed_table._id = ?",
        arrayOf(digit.toString()),
        null,
    )
    cursor?.use {
        if (it.moveToFirst()) {
            val numberCol = it.getColumnIndex("data1")
            val nameCol = it.getColumnIndex("display_name")
            val number = if (numberCol >= 0) it.getString(numberCol) else null
            val name = if (nameCol >= 0) it.getString(nameCol) else null
            if (number.isNullOrBlank()) {
                SystemSpeedDialResult.Unset
            } else {
                SystemSpeedDialResult.Found(SystemSpeedDialEntry(number, name ?: number))
            }
        } else if (hasAnySpeedDial(context)) {
            SystemSpeedDialResult.Unset
        } else {
            // The provider answers but holds nothing at all - on the E4610
            // (Android 7) it doesn't reflect what the dialer's own Speed Dial
            // screen saves, so treat an empty table as unreadable rather than
            // sending the user to set a slot this app can't see.
            SystemSpeedDialResult.Unsupported
        }
    } ?: SystemSpeedDialResult.Unsupported
} catch (e: Exception) {
    SystemSpeedDialResult.Unsupported
}

/** Outcome of reading one slot of the phone's own speed dial data. */
sealed class SystemSpeedDialResult {
    data class Found(val entry: SystemSpeedDialEntry) : SystemSpeedDialResult()

    /** The phone's data is readable, but this slot is empty. */
    object Unset : SystemSpeedDialResult()

    /** The phone's speed dial data can't be read here (no provider, an error, or no rows at all). */
    object Unsupported : SystemSpeedDialResult()
}

private val SPEED_DIAL_URI: Uri = Uri.parse("content://speed_dial/speed_dial")

private fun hasAnySpeedDial(context: Context): Boolean = try {
    context.contentResolver.query(SPEED_DIAL_URI, null, null, null, null)?.use { it.count > 0 } ?: false
} catch (e: Exception) {
    false
}

/**
 * Opens the phone's own Speed Dial settings screen. The dialer's component
 * path differs by model: `.speeddial.SpeedDialActivity` on the E4610
 * (confirmed via logcat), `.app.speeddial.SpeedDialActivity` on newer ones.
 */
fun Context.openSystemSpeedDial() {
    for (className in SYSTEM_SPEED_DIAL_ACTIVITIES) {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName("com.android.dialer", className))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (e: Exception) {
            // Not on this model; try the next one.
        }
    }
    Toast.makeText(this, R.string.toast_not_available, Toast.LENGTH_SHORT).show()
}

private val SYSTEM_SPEED_DIAL_ACTIVITIES = listOf(
    "com.android.dialer.speeddial.SpeedDialActivity",
    "com.android.dialer.app.speeddial.SpeedDialActivity",
)
