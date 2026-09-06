package dev.xxemail.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyThemeTest {

    @Test
    fun `named presets resolve to their own ground`() {
        assertEquals(
            SyncedTheme(0xFF131316L, isDark = true, presetKey = "graphite"),
            resolveSyncedTheme("Graphite", backgroundExtra = 0xFF00FF00L),
        )
        assertEquals(
            SyncedTheme(0xFFF3EEE2L, isDark = false, presetKey = "paper"),
            resolveSyncedTheme("Paper", null),
        )
    }

    @Test
    fun `custom uses the BACKGROUND extra`() {
        val custom = 0xFF224466L
        assertEquals(
            SyncedTheme(custom, isDark = true),
            resolveSyncedTheme("Custom", custom),
        )
    }

    @Test
    fun `custom without background is ignored`() {
        assertNull(resolveSyncedTheme("Custom", null))
    }

    @Test
    fun `unknown name is ignored`() {
        assertNull(resolveSyncedTheme("Not A Theme", 0xFF000000L))
    }

    @Test
    fun `paper prefers ink foreground`() {
        assertTrue(prefersDarkForeground(FamilyPreset.PAPER.background))
        assertEquals(FOREGROUND_INK, foregroundFor(FamilyPreset.PAPER.background))
    }
}
