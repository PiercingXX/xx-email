package dev.xxemail.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Indigo = Color(0xFF23306B)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3A4A9F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE1FF),
    secondary = Color(0xFF595D72),
    surface = Color(0xFFFBF8FF),
    background = Color(0xFFFBF8FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08184B),
    primaryContainer = Indigo,
    secondary = Color(0xFFC2C5DD),
    surface = Color(0xFF121318),
    background = Color(0xFF121318),
)

/**
 * Launcher family theme presets, mapped onto the M3 colour scheme.
 *
 * The seven launcher presets (the standard Material You colourways a launcher
 * exposes) plus "Custom" are each mapped to a concrete light/dark scheme so a
 * family-sync broadcast can restyle the app live. "Custom" keeps the app's own
 * dynamic-colour behaviour (Material You from the wallpaper).
 */
enum class ThemePreset(val id: String) {
    DEFAULT("default"),
    BLUE("blue"),
    GREEN("green"),
    PURPLE("purple"),
    ORANGE("orange"),
    RED("red"),
    TEAL("teal"),
    CUSTOM("custom");

    companion object {
        fun fromId(raw: String?): ThemePreset =
            entries.firstOrNull { it.id == raw?.lowercase() } ?: DEFAULT
    }
}

/** Light scheme for a fixed preset (deterministic, no dynamic colour). */
private fun presetLightScheme(preset: ThemePreset): ColorScheme = when (preset) {
    ThemePreset.DEFAULT -> LightColors
    ThemePreset.BLUE -> lightColorScheme(
        primary = Color(0xFF1B69D2),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD7E2FF),
        secondary = Color(0xFF5A5D72),
        surface = Color(0xFFFAF9FD),
        background = Color(0xFFFAF9FD),
    )
    ThemePreset.GREEN -> lightColorScheme(
        primary = Color(0xFF2E7D32),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB7F0B3),
        secondary = Color(0xFF4E6350),
        surface = Color(0xFFF7FBF4),
        background = Color(0xFFF7FBF4),
    )
    ThemePreset.PURPLE -> lightColorScheme(
        primary = Color(0xFF7B4FA6),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9DDFF),
        secondary = Color(0xFF635B70),
        surface = Color(0xFFFCF8FF),
        background = Color(0xFFFCF8FF),
    )
    ThemePreset.ORANGE -> lightColorScheme(
        primary = Color(0xFFC2530D),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDCC2),
        secondary = Color(0xFF745943),
        surface = Color(0xFFFFF8F5),
        background = Color(0xFFFFF8F5),
    )
    ThemePreset.RED -> lightColorScheme(
        primary = Color(0xFFC62828),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDAD6),
        secondary = Color(0xFF775652),
        surface = Color(0xFFFFF8F7),
        background = Color(0xFFFFF8F7),
    )
    ThemePreset.TEAL -> lightColorScheme(
        primary = Color(0xFF00796B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF9FF2E0),
        secondary = Color(0xFF4A635D),
        surface = Color(0xFFF5FBF7),
        background = Color(0xFFF5FBF7),
    )
    ThemePreset.CUSTOM -> LightColors
}

/** Dark scheme for a fixed preset (deterministic, no dynamic colour). */
private fun presetDarkScheme(preset: ThemePreset): ColorScheme = when (preset) {
    ThemePreset.DEFAULT -> DarkColors
    ThemePreset.BLUE -> darkColorScheme(
        primary = Color(0xFFAEC6FF),
        onPrimary = Color(0xFF002F6B),
        primaryContainer = Color(0xFF0A4A9E),
        secondary = Color(0xFFBEC6DC),
        surface = Color(0xFF111318),
        background = Color(0xFF111318),
    )
    ThemePreset.GREEN -> darkColorScheme(
        primary = Color(0xFF9CD69B),
        onPrimary = Color(0xFF00390F),
        primaryContainer = Color(0xFF1B5E20),
        secondary = Color(0xFFB9CCB8),
        surface = Color(0xFF101411),
        background = Color(0xFF101411),
    )
    ThemePreset.PURPLE -> darkColorScheme(
        primary = Color(0xFFD3BCF5),
        onPrimary = Color(0xFF3A1E5C),
        primaryContainer = Color(0xFF603A8A),
        secondary = Color(0xFFCBC2D9),
        surface = Color(0xFF141218),
        background = Color(0xFF141218),
    )
    ThemePreset.ORANGE -> darkColorScheme(
        primary = Color(0xFFFFB77C),
        onPrimary = Color(0xFF5C1F00),
        primaryContainer = Color(0xFF8A3A00),
        secondary = Color(0xFFE2BFA6),
        surface = Color(0xFF181310),
        background = Color(0xFF181310),
    )
    ThemePreset.RED -> darkColorScheme(
        primary = Color(0xFFFFB4AB),
        onPrimary = Color(0xFF690005),
        primaryContainer = Color(0xFF93000A),
        secondary = Color(0xFFE7BDB8),
        surface = Color(0xFF181313),
        background = Color(0xFF181313),
    )
    ThemePreset.TEAL -> darkColorScheme(
        primary = Color(0xFF7BD2C0),
        onPrimary = Color(0xFF00382F),
        primaryContainer = Color(0xFF005047),
        secondary = Color(0xFFB5CCC5),
        surface = Color(0xFF0F1513),
        background = Color(0xFF0F1513),
    )
    ThemePreset.CUSTOM -> DarkColors
}

@Composable
fun XxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    preset: ThemePreset = ThemePreset.DEFAULT,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Custom preset keeps the app's own dynamic-colour behaviour.
        dynamicColor && preset == ThemePreset.CUSTOM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> presetDarkScheme(preset)
        else -> presetLightScheme(preset)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
