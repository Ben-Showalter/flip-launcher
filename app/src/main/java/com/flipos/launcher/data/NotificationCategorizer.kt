package com.flipos.launcher.data

/** The buckets the Home banner and summary badges group notifications into. */
enum class NotificationKind { CALL, MESSAGE, EVENT, OTHER }

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
    private const val CATEGORY_EVENT = "event"
    private const val CATEGORY_REMINDER = "reminder"

    fun kindOf(category: String?): NotificationKind = when (category) {
        CATEGORY_CALL, CATEGORY_MISSED_CALL, CATEGORY_VOICEMAIL -> NotificationKind.CALL
        CATEGORY_MESSAGE -> NotificationKind.MESSAGE
        CATEGORY_EVENT, CATEGORY_REMINDER -> NotificationKind.EVENT
        else -> NotificationKind.OTHER
    }

    /**
     * Like [kindOf], but an uncategorized notification from the phone's
     * calendar app ([calendarPackage]) counts as an event, and one from the
     * phone/dialer apps ([phonePackages]) - a missed call or voicemail - as a
     * call: the stock apps on these phones don't always set a category.
     */
    fun kindOf(
        category: String?,
        packageName: String,
        calendarPackage: String?,
        phonePackages: Set<String> = emptySet(),
    ): NotificationKind {
        val kind = kindOf(category)
        return when {
            kind != NotificationKind.OTHER -> kind
            packageName == calendarPackage -> NotificationKind.EVENT
            packageName in phonePackages -> NotificationKind.CALL
            else -> kind
        }
    }
}
