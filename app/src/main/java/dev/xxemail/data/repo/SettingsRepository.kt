package dev.xxemail.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.xxemail.ui.theme.ThemePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

enum class SwipeAction { ARCHIVE, DELETE, MARK_READ, STAR, SNOOZE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User preferences. Nothing here ever leaves the device. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val CLIENT_ID = stringPreferencesKey("oauth_client_id")
        val UNDO_SECONDS = intPreferencesKey("undo_send_seconds")
        val SWIPE_LEFT = stringPreferencesKey("swipe_left_action")
        val SWIPE_RIGHT = stringPreferencesKey("swipe_right_action")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val SEND_AND_ARCHIVE = booleanPreferencesKey("send_and_archive")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val SYNC_MINUTES = intPreferencesKey("sync_interval_minutes")
        val REMOTE_IMAGES = booleanPreferencesKey("remote_images_allowed")
        val LAST_ACCOUNT = stringPreferencesKey("last_account")
    }

    // Flows for UI
    val clientIdFlow: Flow<String?> = context.settingsDataStore.data.map { it[Keys.CLIENT_ID] }
    val undoSecondsFlow: Flow<Int> = context.settingsDataStore.data.map { it[Keys.UNDO_SECONDS] ?: DEFAULT_UNDO_SECONDS }
    val swipeLeftFlow: Flow<SwipeAction> = context.settingsDataStore.data.map { enumOr(it[Keys.SWIPE_LEFT], SwipeAction.ARCHIVE) }
    val swipeRightFlow: Flow<SwipeAction> = context.settingsDataStore.data.map { enumOr(it[Keys.SWIPE_RIGHT], SwipeAction.DELETE) }
    val themeFlow: Flow<ThemeMode> = context.settingsDataStore.data.map { enumOr(it[Keys.THEME], ThemeMode.SYSTEM) }
    val themePresetFlow: Flow<ThemePreset> = context.settingsDataStore.data.map { ThemePreset.fromId(it[Keys.THEME_PRESET]) }
    val dynamicColorsFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.DYNAMIC_COLORS] ?: true }
    val sendAndArchiveFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.SEND_AND_ARCHIVE] ?: false }
    val notificationsEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.NOTIFICATIONS_ENABLED] ?: true }
    val syncMinutesFlow: Flow<Int> = context.settingsDataStore.data.map { it[Keys.SYNC_MINUTES] ?: DEFAULT_SYNC_MINUTES }
    val remoteImagesFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.REMOTE_IMAGES] ?: false }

    /** Account whose mailbox was last open — cold start returns here (F1). */
    val lastAccountFlow: Flow<String?> = context.settingsDataStore.data.map { it[Keys.LAST_ACCOUNT]?.takeIf(String::isNotEmpty) }

    // Suspend getters for workers/repos
    suspend fun clientId(): String? = clientIdFlow.first()
    suspend fun undoSeconds(): Int = undoSecondsFlow.first()
    suspend fun swipeLeft(): SwipeAction = swipeLeftFlow.first()
    suspend fun swipeRight(): SwipeAction = swipeRightFlow.first()
    suspend fun sendAndArchive(): Boolean = sendAndArchiveFlow.first()
    suspend fun notificationsEnabled(): Boolean = notificationsEnabledFlow.first()
    suspend fun syncMinutes(): Int = syncMinutesFlow.first()
    suspend fun remoteImagesAllowed(): Boolean = remoteImagesFlow.first()
    suspend fun lastAccount(): String? = lastAccountFlow.first()

    suspend fun setClientId(value: String?) = edit { it[Keys.CLIENT_ID] = value.orEmpty() }
    suspend fun setUndoSeconds(v: Int) = edit { it[Keys.UNDO_SECONDS] = v.coerceIn(5, 30) }
    suspend fun setSwipeLeft(v: SwipeAction) = edit { it[Keys.SWIPE_LEFT] = v.name }
    suspend fun setSwipeRight(v: SwipeAction) = edit { it[Keys.SWIPE_RIGHT] = v.name }
    suspend fun setTheme(v: ThemeMode) = edit { it[Keys.THEME] = v.name }
    suspend fun setThemePreset(v: ThemePreset) = edit { it[Keys.THEME_PRESET] = v.id }
    suspend fun setDynamicColors(v: Boolean) = edit { it[Keys.DYNAMIC_COLORS] = v }
    suspend fun setSendAndArchive(v: Boolean) = edit { it[Keys.SEND_AND_ARCHIVE] = v }
    suspend fun setNotificationsEnabled(v: Boolean) = edit { it[Keys.NOTIFICATIONS_ENABLED] = v }
    suspend fun setSyncMinutes(v: Int) = edit { it[Keys.SYNC_MINUTES] = v.coerceAtLeast(15) }
    suspend fun setRemoteImages(v: Boolean) = edit { it[Keys.REMOTE_IMAGES] = v }
    suspend fun setLastAccount(v: String?) = edit { it[Keys.LAST_ACCOUNT] = v.orEmpty() }

    private suspend inline fun edit(crossinline transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { transform(it) }
    }

    companion object {
        const val DEFAULT_UNDO_SECONDS = 10
        const val DEFAULT_SYNC_MINUTES = 15

        private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
            raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
    }
}
