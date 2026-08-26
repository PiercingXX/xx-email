package dev.xxemail.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xxemail.appGraph
import dev.xxemail.data.repo.SettingsRepository
import dev.xxemail.data.repo.SwipeAction
import dev.xxemail.data.repo.ThemeMode
import dev.xxemail.sync.SyncScheduler
import kotlinx.coroutines.launch

private const val REVOKE_URL = "https://myaccount.google.com/permissions"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val accounts by graph.accounts.observeAccounts().collectAsStateWithLifecycle(initialValue = emptyList())
    val swipeLeft by settings.swipeLeftFlow.collectAsStateWithLifecycle(SwipeAction.ARCHIVE)
    val swipeRight by settings.swipeRightFlow.collectAsStateWithLifecycle(SwipeAction.DELETE)
    val undoSeconds by settings.undoSecondsFlow.collectAsStateWithLifecycle(SettingsRepository.DEFAULT_UNDO_SECONDS)
    val sendArchive by settings.sendAndArchiveFlow.collectAsStateWithLifecycle(false)
    val theme by settings.themeFlow.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val dynamic by settings.dynamicColorsFlow.collectAsStateWithLifecycle(true)
    val notifications by settings.notificationsEnabledFlow.collectAsStateWithLifecycle(true)
    val syncMinutes by settings.syncMinutesFlow.collectAsStateWithLifecycle(SettingsRepository.DEFAULT_SYNC_MINUTES)

    var removeTarget by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                SectionHeader("Accounts")
                accounts.forEach { account ->
                    ListItem(
                        headlineContent = { Text(account.email) },
                        trailingContent = {
                            IconButton(onClick = { removeTarget = account.email }) {
                                Icon(Icons.Filled.Delete, "Remove account")
                            }
                        },
                    )
                }
                HorizontalDivider()
            }
            item {
                SectionHeader("Gestures")
                DropdownRow("Swipe right", swipeRight.name) { action ->
                    scope.launch { settings.setSwipeRight(SwipeAction.valueOf(action)) }
                }
                DropdownRow("Swipe left", swipeLeft.name) { action ->
                    scope.launch { settings.setSwipeLeft(SwipeAction.valueOf(action)) }
                }
                HorizontalDivider()
            }
            item {
                SectionHeader("Sending")
                DropdownRow("Undo window", "${undoSeconds}s", options = listOf("5s", "10s", "20s", "30s")) { value ->
                    scope.launch { settings.setUndoSeconds(value.removeSuffix("s").toInt()) }
                }
                ToggleRow("Send & archive", sendArchive) { scope.launch { settings.setSendAndArchive(it) } }
                HorizontalDivider()
            }
            item {
                SectionHeader("Sync & notifications")
                DropdownRow("Sync interval", "${syncMinutes} min", options = listOf("15 min", "30 min", "60 min")) { value ->
                    val minutes = value.removeSuffix(" min").toInt()
                    scope.launch {
                        settings.setSyncMinutes(minutes)
                        SyncScheduler.ensurePeriodic(graph.workManager, minutes)
                    }
                }
                ToggleRow("New-mail notifications", notifications) { scope.launch { settings.setNotificationsEnabled(it) } }
                HorizontalDivider()
            }
            item {
                SectionHeader("Appearance")
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = theme == mode,
                            onClick = { scope.launch { settings.setTheme(mode) } },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                ToggleRow("Dynamic colors (Material You)", dynamic) { scope.launch { settings.setDynamicColors(it) } }
                HorizontalDivider()
            }
            item {
                SectionHeader("Privacy")
                Text(
                    buildString {
                        appendLine("• No analytics, crash reporting, ads SDKs or trackers of any kind.")
                        appendLine("• Network access goes only to Google's OAuth and Gmail API endpoints.")
                        appendLine("• Tokens are stored encrypted in the Android Keystore; never exported.")
                        appendLine("• Cloud backup of mail cache and credentials is disabled at the OS level.")
                        appendLine("• Remote images in emails are blocked by default (tracking pixels).")
                        appendLine("• Requested scope: gmail.modify — this app cannot permanently delete your mail.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TextButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REVOKE_URL))) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text("Revoke Google access for this app") }
                HorizontalDivider()
            }
            item {
                SectionHeader("About")
                Text(
                    "XX Email 0.1.0 · GPL-3.0-or-later\nAn original, telemetry-free Gmail client.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.padding(bottom = 32.dp))
            }
        }
    }

    removeTarget?.let { email ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove $email?") },
            text = { Text("Removes the account, its cached mail and queued sends from this device. Your Gmail data is untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { graph.accounts.remove(email) }
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun DropdownRow(label: String, current: String, options: List<String> = SwipeAction.entries.map { it.name }, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Column {
                TextButton(onClick = { open = true }) { Text(current) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { open = false; onPick(option) })
                    }
                }
            }
        },
        modifier = Modifier.clickable { open = true },
    )
}
