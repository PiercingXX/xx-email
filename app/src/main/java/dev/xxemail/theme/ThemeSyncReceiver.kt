package dev.xxemail.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Family theme-sync receiver. XX-Launcher broadcasts `xx.launcher.THEME_CHANGED`
 * with per-package setPackage. This app uses-permission THEME_SYNC and gates
 * the exported receiver with android:permission — it must NOT declare the
 * permission (mixed debug keys).
 */
class ThemeSyncReceiver(
    private val action: String = ACTION_THEME_CHANGED,
    private val extractAction: (Intent?) -> String? = { intent -> intent?.action },
    private val extractThemeName: (Intent?) -> String? = { intent ->
        intent?.getStringExtra(EXTRA_THEME_NAME)
    },
    private val extractBackground: (Intent?) -> Long? = { intent ->
        if (intent != null && intent.hasExtra(EXTRA_BACKGROUND)) {
            intent.getIntExtra(EXTRA_BACKGROUND, 0).toLong() and 0xFFFFFFFFL
        } else {
            null
        }
    },
    private val persistTheme: (Context?, SyncedTheme) -> Unit = { context, theme ->
        if (context != null) ThemeStore.of(context).save(theme)
    },
    private val applyLive: (Context?, SyncedTheme) -> Unit = { _, theme ->
        ThemeController.onThemeChanged(theme)
    },
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (extractAction(intent) != action) return
        val theme = resolveSyncedTheme(
            extractThemeName(intent),
            extractBackground(intent),
        ) ?: return
        persistTheme(context, theme)
        applyLive(context, theme)
    }

    companion object {
        const val ACTION_THEME_CHANGED = "xx.launcher.THEME_CHANGED"
        const val EXTRA_THEME_NAME = "xx.launcher.extra.THEME_NAME"
        const val EXTRA_BACKGROUND = "xx.launcher.extra.BACKGROUND"
        const val PERMISSION_THEME_SYNC = "com.piercingxx.xxlauncher.permission.THEME_SYNC"
    }
}
