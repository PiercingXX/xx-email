package dev.xxemail.theme

import android.content.Context
import android.content.SharedPreferences

interface ThemeKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class SharedPreferencesThemeKeyValueStore(
    private val prefs: SharedPreferences,
) : ThemeKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

class ThemeStore(private val kv: ThemeKeyValueStore) {

    fun save(theme: SyncedTheme) {
        kv.putString(KEY_BACKGROUND, theme.background.toString())
        kv.putBoolean(KEY_IS_DARK, theme.isDark)
        kv.putString(KEY_PRESET, theme.presetKey)
    }

    fun load(): SyncedTheme? {
        val background = kv.getString(KEY_BACKGROUND)?.toLongOrNull() ?: return null
        return SyncedTheme(
            background = background,
            isDark = kv.getBoolean(KEY_IS_DARK, !prefersDarkForeground(background)),
            presetKey = kv.getString(KEY_PRESET),
        )
    }

    companion object {
        const val PREFS_NAME = "xx_email_theme"
        const val KEY_BACKGROUND = "background"
        const val KEY_IS_DARK = "is_dark"
        const val KEY_PRESET = "preset_key"

        fun of(context: Context): ThemeStore = ThemeStore(
            SharedPreferencesThemeKeyValueStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
}
