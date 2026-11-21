package com.flipos.launcher.data

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet

/** One active notification, as shown in the custom Notices screen. */
data class NoticeItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val icon: Drawable?,
    val kind: NotificationKind,
    /** What tapping it in the shade would open; null when the app gave none. */
    val contentIntent: PendingIntent? = null,
)

/**
 * Live list of active notifications, fed by
 * [com.flipos.launcher.service.NotificationCountService], backing the custom
 * Notices screen (we don't use the system shade - see NoticesActivity).
 *
 * [update] runs on the listener's binder thread while the UI reads on the main
 * thread, so state is [Volatile], the listener set is copy-on-write, and
 * callbacks are marshalled to the main thread.
 */
object NotificationStore {

    @Volatile
    var items: List<NoticeItem> = emptyList()
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * The most recent item passing [filter], by post time. The listener keeps
     * [items] newest-first but the accessibility fallback appends, so never
     * rely on list order - always pick by [NoticeItem.postTime].
     */
    fun newest(filter: (NoticeItem) -> Boolean): NoticeItem? =
        items.filter(filter).maxByOrNull { it.postTime }

    fun update(items: List<NoticeItem>) {
        this.items = items
        val keys = items.mapTo(HashSet()) { it.key }
        seenKeys.retainAll(keys)
        notifyListeners()
    }

    // ------------------------------------------------- Seen (accessibility feed)
    //
    // With the accessibility fallback there's no removal event, so an entry is
    // dismissed once the user has seen its app and left it. Both the service
    // (window/screen events) and the launcher (it opened the app; Home came
    // back) record that here. Synchronized: [update] can run on the
    // listener's binder thread.

    /** Keys of [items] whose app the user has had on screen since they were posted. */
    private val seenKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Marks every current entry from [packageName] as seen. */
    fun markSeen(packageName: String) {
        items.filter { it.packageName == packageName }.forEach { seenKeys.add(it.key) }
    }

    /** Marks one entry as seen - posted while its app was on screen. */
    fun markSeenKey(key: String) {
        seenKeys.add(key)
    }

    /** Removes [packageName]'s seen entries (the user left that app). Returns how many. */
    fun dismissSeen(packageName: String): Int = dismissWhere { it.packageName == packageName && it.key in seenKeys }

    /** Removes every seen entry (the user is back on Home, so they've left those apps). Returns how many. */
    fun dismissAllSeen(): Int = dismissWhere { it.key in seenKeys }

    private fun dismissWhere(predicate: (NoticeItem) -> Boolean): Int {
        val kept = items.filterNot(predicate)
        val removed = items.size - kept.size
        if (removed > 0) update(kept)
        return removed
    }

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it() }
        } else {
            mainHandler.post { listeners.forEach { it() } }
        }
    }
}
