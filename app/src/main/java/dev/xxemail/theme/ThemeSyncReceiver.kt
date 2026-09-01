package dev.xxemail.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.xxemail.appGraph
import dev.xxemail.ui.theme.ThemePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Family theme-sync receiver.
 *
 * The launcher (a sibling app in the same family, listed in its FAMILY_PACKAGES)
 * broadcasts the currently selected theme preset so every family app restyles in
 * lockstep. The receiver is declared with `android:permission="THEME_SYNC"` in the
 * manifest, so only a sender holding that (uses-permission-only, never declared)
 * permission can deliver to it. On receipt we persist the preset to DataStore;
 * MainActivity already collects [dev.xxemail.data.repo.SettingsRepository.themePresetFlow]
 * and re-applies the M3 scheme, so the change is live without a restart.
 */
class ThemeSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val preset = ThemePreset.fromId(intent.getStringExtra(EXTRA_PRESET))
        CoroutineScope(Dispatchers.Default).launch {
            context.appGraph.settings.setThemePreset(preset)
        }
    }

    companion object {
        const val ACTION_THEME_SYNC = "dev.xxemail.action.THEME_SYNC"
        const val EXTRA_PRESET = "dev.xxemail.extra.THEME_PRESET"
    }
}