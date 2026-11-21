package com.flipos.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCategorizerTest {

    @Test
    fun `call categories map to CALL`() {
        assertEquals(NotificationKind.CALL, NotificationCategorizer.kindOf("call"))
        assertEquals(NotificationKind.CALL, NotificationCategorizer.kindOf("missed_call"))
        assertEquals(NotificationKind.CALL, NotificationCategorizer.kindOf("voicemail"))
    }

    @Test
    fun `message category maps to MESSAGE`() {
        assertEquals(NotificationKind.MESSAGE, NotificationCategorizer.kindOf("msg"))
    }

    @Test
    fun `event and reminder categories map to EVENT`() {
        assertEquals(NotificationKind.EVENT, NotificationCategorizer.kindOf("event"))
        assertEquals(NotificationKind.EVENT, NotificationCategorizer.kindOf("reminder"))
    }

    @Test
    fun `calendar app notifications without an event category map to EVENT`() {
        assertEquals(NotificationKind.EVENT, NotificationCategorizer.kindOf(null, "com.cal", "com.cal"))
        assertEquals(NotificationKind.EVENT, NotificationCategorizer.kindOf("email", "com.cal", "com.cal"))
        assertEquals(NotificationKind.MESSAGE, NotificationCategorizer.kindOf("msg", "com.cal", "com.cal"))
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf(null, "com.other", "com.cal"))
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf(null, "com.other", null))
    }

    @Test
    fun `phone app notifications without a call category map to CALL`() {
        val phone = setOf("com.android.dialer", "com.android.server.telecom")
        assertEquals(NotificationKind.CALL, NotificationCategorizer.kindOf(null, "com.android.dialer", null, phone))
        assertEquals(NotificationKind.CALL, NotificationCategorizer.kindOf("status", "com.android.server.telecom", null, phone))
        assertEquals(NotificationKind.MESSAGE, NotificationCategorizer.kindOf("msg", "com.android.dialer", null, phone))
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf(null, "com.other", null, phone))
    }

    @Test
    fun `unknown and null categories map to OTHER`() {
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf("email"))
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf(null))
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf(""))
    }
}
