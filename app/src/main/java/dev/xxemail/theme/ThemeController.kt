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

    private val _theme = MutableStateFlow<SyncedTheme?>(null)
    val theme: StateFlow<SyncedTheme?> = _theme

    fun init(context: Context) {
        _theme.value = ThemeStore.of(context).load()
    }

    fun onThemeChanged(theme: SyncedTheme) {
        _theme.value = theme
    }
}
