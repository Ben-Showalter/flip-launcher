package com.flipos.launcher.util

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.flipos.launcher.R

/**
 * Lazily requests a single dangerous permission the first time it's actually
 * needed (never at launch), remembering the action to retry once granted and
 * falling back otherwise (e.g. pre-filling the dialer instead of placing a
 * call directly). Must be constructed as a field initializer — same timing as
 * any other `registerForActivityResult` call — so build one `val` per gated
 * permission on the owning Activity.
 */
class PermissionGate(
    private val activity: AppCompatActivity,
    private val permission: String,
) {
    private var pendingAction: (() -> Unit)? = null
    private var pendingOnDenied: (() -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingAction
        val onDenied = pendingOnDenied
        pendingAction = null
        pendingOnDenied = null
        if (granted) action?.invoke() else onDenied?.invoke()
    }

    fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Runs [action] immediately if the permission is already granted;
     * otherwise requests it, running [action] once granted or [onDenied] if
     * refused.
     */
    fun run(onDenied: () -> Unit = {}, action: () -> Unit) {
        if (isGranted()) {
            action()
            return
        }
        pendingAction = action
        pendingOnDenied = onDenied
        launcher.launch(permission)
    }
}

/**
 * Sends the user to grant a permanently-denied permission by hand. Opens the
 * general Apps list rather than this app's own page - per-app deep links
 * don't work on these OEM builds - with a toast naming the path.
 */
fun AppCompatActivity.openAppPermissionSettings() {
    openSettingsWithPath(Settings.ACTION_APPLICATION_SETTINGS, R.string.path_app_permissions)
}

/**
 * Places a call to [number] immediately if [gate] grants `CALL_PHONE`
 * (requesting it first if needed), otherwise falls back to a pre-filled
 * dialer — the same degrade-gracefully behavior as every other telephony
 * action in this app.
 */
fun AppCompatActivity.placeCall(gate: PermissionGate, number: String) {
    gate.run(onDenied = { dialPrefill(number) }) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        } catch (e: Exception) {
            dialPrefill(number)
        }
    }
}

private fun AppCompatActivity.dialPrefill(number: String) {
    try {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    } catch (e: Exception) {
        Toast.makeText(this, R.string.toast_no_dialer, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the default messaging app, composing a text to [number]. No runtime
 * permission needed since this hands off to the messaging app rather than
 * sending directly.
 */
fun AppCompatActivity.sendMessage(number: String) {
    try {
        startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
    } catch (e: Exception) {
        Toast.makeText(this, R.string.toast_no_messaging_app, Toast.LENGTH_SHORT).show()
    }
}
