package com.flipos.launcher.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Live counts of active notifications by category, fed by
 * [com.flipos.launcher.service.NotificationCountService]. A simple in-memory
 * singleton (not persisted) since the listener service recomputes on every
 * change and re-syncs as soon as it reconnects.
 *
 * [update] runs on the listener's binder thread while UI listeners read on the
 * main thread, so state is [Volatile], the listener set is copy-on-write, and
 * callbacks are marshalled to the main thread.
 */
object NotificationCounts {

    @Volatile
    var calls: Int = 0
        private set

    @Volatile
    var messages: Int = 0
        private set

    @Volatile
    var other: Int = 0
        private set

    /** Packages with at least one active notification, backing the per-app icon dot. */
    @Volatile
    var packagesWithNotifications: Set<String> = emptySet()
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun update(calls: Int, messages: Int, other: Int, packagesWithNotifications: Set<String>) {
        this.calls = calls
        this.messages = messages
        this.other = other
        this.packagesWithNotifications = packagesWithNotifications
        notifyListeners()
    }

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it() }
        } else {
            mainHandler.post { listeners.forEach { it() } }
        }
    }
}
