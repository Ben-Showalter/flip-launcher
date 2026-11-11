package com.flipos.launcher.data

import android.app.Notification
import android.os.Bundle

/**
 * The newest message in a notification, as (title, text). Messaging apps
 * keep the whole conversation in one notification (MessagingStyle's
 * messages, or InboxStyle's lines), and once a second message arrives the
 * alert often comes from the group's summary, whose plain text is just "2
 * new messages" - so the last message is read from the style's own data
 * first, and the plain title/text are the fallback.
 */
object NotificationText {

    private const val EXTRA_MESSAGES = "android.messages" // Notification.EXTRA_MESSAGES (API 24)
    private const val KEY_TEXT = "text"
    private const val KEY_SENDER = "sender"

    fun latest(notification: Notification): Pair<String, String> {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        @Suppress("DEPRECATION")
        val messages = try {
            extras.getParcelableArray(EXTRA_MESSAGES)
        } catch (e: Exception) {
            null
        }
        val last = messages?.lastOrNull() as? Bundle
        val lastText = last?.getCharSequence(KEY_TEXT)?.toString()
        if (!lastText.isNullOrBlank()) {
            val sender = last.getCharSequence(KEY_SENDER)?.toString()
            return (sender?.takeIf { it.isNotBlank() } ?: title) to lastText
        }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val lastLine = lines?.lastOrNull()?.toString()
        if (!lastLine.isNullOrBlank()) return title to lastLine
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        return title to text
    }
}
