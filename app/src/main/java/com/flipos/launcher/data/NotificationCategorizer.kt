package com.flipos.launcher.data

/** The three buckets the Home summary badges group notifications into. */
enum class NotificationKind { CALL, MESSAGE, OTHER }

/**
 * Maps a notification's `category` string to a [NotificationKind]. Kept as a pure
 * function (matching against the framework's string constants directly) so it can
 * be unit-tested off-device, and so the "call" bucket's coverage (missed calls,
 * voicemail) lives in one place.
 */
object NotificationCategorizer {

    // Values of Notification.CATEGORY_* — matched as literals so this stays a
    // plain-Kotlin, framework-free function.
    private const val CATEGORY_CALL = "call"
    private const val CATEGORY_MISSED_CALL = "missed_call"
    private const val CATEGORY_VOICEMAIL = "voicemail"
    private const val CATEGORY_MESSAGE = "msg"

    fun kindOf(category: String?): NotificationKind = when (category) {
        CATEGORY_CALL, CATEGORY_MISSED_CALL, CATEGORY_VOICEMAIL -> NotificationKind.CALL
        CATEGORY_MESSAGE -> NotificationKind.MESSAGE
        else -> NotificationKind.OTHER
    }
}
