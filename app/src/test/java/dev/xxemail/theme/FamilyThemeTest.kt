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

    @Test
    fun `presets resolve by stable key`() {
        for (preset in FamilyPreset.entries) {
            assertEquals(preset, FamilyPreset.fromKey(preset.key))
        }
        assertNull(FamilyPreset.fromKey("neon"))
        assertNull(FamilyPreset.fromKey(null))
    }

    @Test
    fun `each preset derives a distinct surface from its own ground`() {
        val surfaces = FamilyPreset.entries.map { preset ->
            paletteFor(SyncedTheme(preset.background, preset.isDark, preset.key)).surfaceMid
        }
        assertEquals(FamilyPreset.entries.size, surfaces.toSet().size)
        val paper = paletteFor(SyncedTheme(FamilyPreset.PAPER.background, false, "paper"))
        assertEquals(FOREGROUND_INK, paper.accent)
        assertEquals(FamilyPreset.PAPER.background, paper.background)
        val forest = paletteFor(SyncedTheme(FamilyPreset.FOREST_NIGHT.background, true, "forest-night"))
        assertEquals(FOREGROUND_WHITE, forest.accent)
        assertEquals(FamilyPreset.FOREST_NIGHT.background, forest.background)
    }

    @Test
    fun `amoled night keeps the vendored ladder`() {
        val palette = paletteFor(
            SyncedTheme(FamilyPreset.AMOLED_NIGHT.background, true, FamilyPreset.AMOLED_NIGHT.key),
        )
        assertEquals(AMOLED_SURFACE_LOW, palette.surfaceLow)
        assertEquals(AMOLED_SURFACE_MID, palette.surfaceMid)
        assertEquals(AMOLED_SURFACE_HIGH, palette.surfaceHigh)
    }
}
