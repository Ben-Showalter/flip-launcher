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
    fun `unknown and null categories map to OTHER`() {
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf("email"))
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf(null))
        assertEquals(NotificationKind.OTHER, NotificationCategorizer.kindOf(""))
    }
}
