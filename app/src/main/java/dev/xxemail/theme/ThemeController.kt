package dev.xxemail.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live family theme for Compose. [init] loads the last launcher broadcast.
 * [onThemeChanged] is called from [ThemeSyncReceiver] so a visible mailbox
 * restyles without a restart.
 */
object ThemeController {

    val DEFAULT = SyncedTheme(
        background = FamilyPreset.AMOLED_NIGHT.background,
        isDark = FamilyPreset.AMOLED_NIGHT.isDark,
        presetKey = FamilyPreset.AMOLED_NIGHT.key,
    )

    private val _theme = MutableStateFlow<SyncedTheme?>(null)
    val theme: StateFlow<SyncedTheme?> = _theme

    fun init(context: Context) {
        _theme.value = ThemeStore.of(context).load()
    }

    fun onThemeChanged(theme: SyncedTheme) {
        _theme.value = theme
    }

    /** In-app picker: persist and restyle exactly like a launcher broadcast. */
    fun applyManual(context: Context, theme: SyncedTheme) {
        ThemeStore.of(context).save(theme)
        onThemeChanged(theme)
    }

    fun applyPreset(context: Context, preset: FamilyPreset) {
        applyManual(context, SyncedTheme(preset.background, preset.isDark, preset.key))
    }
}
