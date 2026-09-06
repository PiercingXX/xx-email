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
