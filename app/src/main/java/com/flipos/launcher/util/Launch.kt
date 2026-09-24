package com.flipos.launcher.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import com.flipos.launcher.R
import com.flipos.launcher.data.AppRepository
import com.flipos.launcher.data.NotificationStore

/** Launches an app by its stored [key], surfacing a toast if it can't be opened. */
fun Context.launchAppByKey(key: String) {
    val intent = AppRepository.launchIntentFor(key)
    if (intent == null) {
        Toast.makeText(this, R.string.toast_launch_unavailable, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        startActivity(intent)
        intent.component?.packageName?.let { NotificationStore.removeItemsForPackage(it) }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.toast_launch_unavailable, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(this, R.string.toast_launch_denied, Toast.LENGTH_SHORT).show()
    }
}
