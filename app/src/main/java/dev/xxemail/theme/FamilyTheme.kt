package dev.xxemail.theme

/**
 * The seven named background presets of the PiercingXX family theme set.
 * Pure Kotlin so resolution is JVM-testable without Android.
 *
 * Display names match xx-launcher ThemeBroadcaster.DISPLAY_NAMES verbatim.
 */
enum class FamilyPreset(
    val key: String,
    val displayName: String,
    val background: Long,
    val isDark: Boolean,
) {
    AMOLED_NIGHT("amoled-night", "AMOLED Night", 0xFF000000, true),
    GRAPHITE("graphite", "Graphite", 0xFF131316, true),
    FOREST_NIGHT("forest-night", "Forest Night", 0xFF10261B, true),
    OCEAN_DRIFT("ocean-drift", "Ocean Drift", 0xFF0F1C2E, true),
    BURGUNDY("burgundy", "Burgundy", 0xFF2A1018, true),
    PAPER("paper", "Paper", 0xFFF3EEE2, false),
    MIST("mist", "Mist", 0xFFE6EDF5, false);

    companion object {
        fun fromKey(key: String?): FamilyPreset? =
            entries.firstOrNull { it.key == key }

        fun fromDisplayName(name: String?): FamilyPreset? =
            entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
    }
}

const val CUSTOM_THEME_NAME = "Custom"
const val FOREGROUND_INK: Long = 0xFF1A1A1A
const val FOREGROUND_WHITE: Long = 0xFFFFFFFF

fun luminance(argb: Long): Double {
    val r = ((argb ushr 16) and 0xFF).toDouble()
    val g = ((argb ushr 8) and 0xFF).toDouble()
    val b = (argb and 0xFF).toDouble()
    return 0.299 * r + 0.587 * g + 0.114 * b
}

fun prefersDarkForeground(background: Long): Boolean = luminance(background) > 182.0

fun foregroundFor(background: Long): Long =
    if (prefersDarkForeground(background)) FOREGROUND_INK else FOREGROUND_WHITE

/** Channel-wise mix so raised surfaces keep the preset's hue. */
fun mix(a: Long, b: Long, fraction: Double): Long {
    fun channel(shift: Int): Long {
        val from = (a ushr shift) and 0xFF
        val to = (b ushr shift) and 0xFF
        return ((from + (to - from) * fraction) + 0.5).toLong() and 0xFF
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

fun withAlpha(argb: Long, alpha: Int): Long =
    (alpha.toLong() shl 24) or (argb and 0x00FFFFFF)

const val AMOLED_SURFACE_LOW: Long = 0xFF09090B
const val AMOLED_SURFACE_MID: Long = 0xFF131316
const val AMOLED_SURFACE_HIGH: Long = 0xFF18181B

/**
 * Surfaces and type derived from a family ground. Used by Compose so a preset
 * change restyles cards, bars, and ink — not just the window.
 */
data class FamilyPalette(
    val background: Long,
    val foreground: Long,
    val surfaceLow: Long,
    val surfaceMid: Long,
    val surfaceHigh: Long,
    val onSurface: Long,
    val onSurfaceMuted: Long,
    val outline: Long,
    val accent: Long,
    val onAccent: Long,
)

fun paletteFor(theme: SyncedTheme): FamilyPalette {
    val foreground = foregroundFor(theme.background)
    val amoled = theme.background == FamilyPreset.AMOLED_NIGHT.background && theme.isDark
    return FamilyPalette(
        background = theme.background,
        foreground = foreground,
        surfaceLow = if (amoled) AMOLED_SURFACE_LOW else mix(theme.background, foreground, 0.04),
        surfaceMid = if (amoled) AMOLED_SURFACE_MID else mix(theme.background, foreground, 0.07),
        surfaceHigh = if (amoled) AMOLED_SURFACE_HIGH else mix(theme.background, foreground, 0.10),
        onSurface = withAlpha(foreground, 0xE6),
        onSurfaceMuted = withAlpha(foreground, 0x80),
        outline = withAlpha(foreground, 0x40),
        accent = foreground,
        onAccent = theme.background,
    )
}

data class SyncedTheme(
    val background: Long,
    val isDark: Boolean,
    val presetKey: String? = null,
)

/**
 * Resolve the launcher broadcast. Named presets use their own ground.
 * Custom requires the BACKGROUND extra. Unknown / Custom-without-ground → null.
 */
fun resolveSyncedTheme(displayName: String?, backgroundExtra: Long?): SyncedTheme? {
    val preset = FamilyPreset.fromDisplayName(displayName)
    if (preset != null) {
        return SyncedTheme(preset.background, preset.isDark, preset.key)
    }
    if (CUSTOM_THEME_NAME.equals(displayName, ignoreCase = true) && backgroundExtra != null) {
        return SyncedTheme(backgroundExtra, isDark = !prefersDarkForeground(backgroundExtra))
    }
    return null
}
