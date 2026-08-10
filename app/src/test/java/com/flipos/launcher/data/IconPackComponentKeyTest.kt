package com.flipos.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPackComponentKeyTest {

    @Test
    fun `parses standard ComponentInfo wrapper`() {
        assertEquals(
            "com.app/com.app.Main",
            IconPackRepository.componentKeyOf("ComponentInfo{com.app/com.app.Main}"),
        )
    }

    @Test
    fun `parses bare flattened component`() {
        assertEquals("com.app/com.app.Main", IconPackRepository.componentKeyOf("com.app/com.app.Main"))
    }

    @Test
    fun `expands dot-class shorthand against the package`() {
        assertEquals("com.app/com.app.Main", IconPackRepository.componentKeyOf("com.app/.Main"))
        assertEquals(
            "com.app/com.app.Main",
            IconPackRepository.componentKeyOf("ComponentInfo{com.app/.Main}"),
        )
    }

    @Test
    fun `rejects malformed input`() {
        assertNull(IconPackRepository.componentKeyOf("ComponentInfo{com.app}"))
        assertNull(IconPackRepository.componentKeyOf("com.app"))
        assertNull(IconPackRepository.componentKeyOf("/com.app.Main"))
        assertNull(IconPackRepository.componentKeyOf("com.app/"))
        assertNull(IconPackRepository.componentKeyOf(""))
    }
}
