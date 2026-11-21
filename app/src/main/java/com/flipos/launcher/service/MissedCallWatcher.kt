package com.flipos.launcher.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Spots missed calls from the call itself. On the E4810/E4811 the dialer's
 * missed-call notification is silent, so it never reaches an accessibility
 * service; only the ongoing "ringing" one does. A call that rings and goes
 * idle without being answered (off-hook) is a missed call.
 *
 * With Call Log access the newest call-log entry decides (a declined call
 * is `REJECTED`, not missed) and names the caller; without it, the caller
 * comes from the ringing notification ([ringingCaller]) or the number.
 * Call state itself needs no permission on Android 9/10.
 *
 * Create and [start] on the main thread (PhoneStateListener needs a Looper).
 */
class MissedCallWatcher(
    private val context: Context,
    private val onMissedCall: (caller: String?) -> Unit,
) {

    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val handler = Handler(Looper.getMainLooper())

    private var ringing = false
    private var answered = false
    private var ringNumber: String? = null

    /** The caller as the ringing notification showed it (its title), for when the call log can't be read. */
    var ringingCaller: String? = null

    @Suppress("DEPRECATION") // TelephonyCallback is API 31; these phones run 9/10.
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state, phoneNumber)
    }

    @Suppress("DEPRECATION")
    fun start() {
        try {
            telephony?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.w(TAG, "missed calls: can't watch call state", e)
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        try {
            telephony?.listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
            // Already gone.
        }
        handler.removeCallbacksAndMessages(null)
    }

    private fun onState(state: Int, phoneNumber: String?) {
        Log.d(TAG, "call state ${stateName(state)} ringing=$ringing answered=$answered")
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                ringing = true
                answered = false
                if (!phoneNumber.isNullOrEmpty()) ringNumber = phoneNumber
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> if (ringing) answered = true
            TelephonyManager.CALL_STATE_IDLE -> {
                if (ringing && !answered) {
                    val shownName = ringingCaller?.takeIf { it.isNotBlank() }
                    val number = ringNumber
                    // Give the dialer a moment to write the call-log entry.
                    handler.postDelayed({ resolve(shownName, number) }, CALL_LOG_DELAY_MS)
                }
                ringing = false
                answered = false
                ringNumber = null
                ringingCaller = null
            }
        }
    }

    /**
     * Works out who called, off the main thread, then reports on it: the
     * number from the call log (if allowed) or the phone state, named from
     * Contacts first - the call log's cached name is often still empty on
     * these phones - then the call log's name, the ringing notification's
     * [shownName], or the number itself. A declined or blocked call isn't
     * reported.
     */
    private fun resolve(shownName: String?, ringNumber: String?) {
        Thread {
            val entry = if (isGranted(Manifest.permission.READ_CALL_LOG)) newestCall() else null
            if (entry != null && entry.type != CallLog.Calls.MISSED_TYPE) {
                Log.d(TAG, "call ended unanswered as type ${entry.type} (declined/blocked): no banner")
                return@Thread
            }
            val number = entry?.number?.takeIf { it.isNotBlank() } ?: ringNumber?.takeIf { it.isNotBlank() }
            val contact = number?.let { contactName(it) }
            val logName = entry?.name?.takeIf { it.isNotBlank() }
            val caller = contact ?: logName ?: shownName ?: number
            val source = when {
                contact != null -> "contacts"
                logName != null -> "call log"
                shownName != null -> "ringing notification"
                number != null -> "number"
                else -> "nothing (no Call Log access?)"
            }
            Log.d(TAG, "missed call from $caller (named from $source; call log entry=${entry != null})")
            handler.post { onMissedCall(caller) }
        }.start()
    }

    /** [number]'s name in Contacts, or null (not saved, or no Contacts access). Worker thread. */
    private fun contactName(number: String): String? {
        if (!isGranted(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.takeIf { name -> name.isNotBlank() } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "missed calls: contact lookup failed", e)
            null
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private class Call(val type: Int, val name: String?, val number: String?)

    /** The newest call-log entry from the last couple of minutes, or null. */
    private fun newestCall(): Call? = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val date = cursor.getLong(3)
            if (System.currentTimeMillis() - date > RECENT_MS) return@use null
            Call(cursor.getInt(0), cursor.getString(1), cursor.getString(2))
        }
    } catch (e: Exception) {
        Log.w(TAG, "missed calls: call log read failed", e)
        null
    }

    private fun stateName(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
        else -> "IDLE"
    }

    companion object {
        private const val TAG = NotificationAccessibilityService.TAG

        /** How long after the call ends to read the call log. */
        private const val CALL_LOG_DELAY_MS = 1_500L

        /** A call-log entry older than this isn't the call that just ended. */
        private const val RECENT_MS = 2 * 60_000L

        /** Key prefix of the banner entries this posts. */
        const val KEY_PREFIX = "missedcall:"
    }
}
