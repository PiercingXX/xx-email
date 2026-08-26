package dev.xxemail.ui.mailbox

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.xxemail.data.repo.SwipeAction
import dev.xxemail.domain.INBOX_TABS
import dev.xxemail.domain.MailboxFolder
import dev.xxemail.domain.SnoozePresets
import dev.xxemail.ui.components.Avatar
import dev.xxemail.ui.components.EmptyState
import dev.xxemail.ui.components.SendEvents
import dev.xxemail.ui.components.ThreadRow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val OTHER_FOLDERS =
    listOf(MailboxFolder.STARRED, MailboxFolder.SNOOZED, MailboxFolder.SENT, MailboxFolder.DRAFTS, MailboxFolder.SPAM, MailboxFolder.TRASH, MailboxFolder.ALL_MAIL)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(
    account: String,
    onOpenThread: (String) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
) {
    val vm = rememberMailboxViewModel(account)
    val graph = LocalContext.current.appGraph
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { INBOX_TABS.size })
    var sheetOpen by remember { mutableStateOf(false) }
    var snoozeTarget by remember { mutableStateOf<String?>(null) }
    var labelSheetOpen by remember { mutableStateOf(false) }

    val accounts by graph.accounts.observeAccounts().collectAsStateWithLifecycle(initialValue = emptyList())
    val swipeLeft by graph.settings.swipeLeftFlow.collectAsStateWithLifecycle(SwipeAction.ARCHIVE)
    val swipeRight by graph.settings.swipeRightFlow.collectAsStateWithLifecycle(SwipeAction.DELETE)

    LaunchedEffect(vm, account) {
        launch {
            vm.undoEvents.collect { u ->
                val result = snackbarHostState.showSnackbar(u.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) runCatching { u.revert() }
            }
        }
        launch {
            SendEvents.queued.collect { event ->
                if (event.accountEmail != account) return@collect
                val result = snackbarHostState.showSnackbar(event.label, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) vm.cancelQueuedSend(event.outboxId)
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.folder == null) "Inbox" else vm.folder!!.title) },
                navigationIcon = {
                    IconButton(onClick = { sheetOpen = true }) { Avatar(account) }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (vm.selection.isNotEmpty()) {
                SelectionBar(
                    count = vm.selection.size,
                    onArchive = { vm.perform(SwipeAction.ARCHIVE, vm.selection.toList()) },
                    onDelete = { vm.perform(SwipeAction.DELETE, vm.selection.toList()) },
                    onMarkUnread = { vm.markUnread(vm.selection.toList()) },
                    onStar = { vm.perform(SwipeAction.STAR, vm.selection.toList()) },
                    onSnooze = { snoozeTarget = vm.selection.first() },
                    onLabels = { labelSheetOpen = true },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCompose, icon = { Icon(Icons.Filled.Edit, null) }, text = { Text("Compose") })
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val currentFolder = vm.folder
            if (currentFolder == null) {
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage) {
                    INBOX_TABS.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(tab.title) },
                        )
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    ThreadListPage(vm, INBOX_TABS[page], onOpenThread, swipeLeft, swipeRight, onSnooze = { snoozeTarget = it })
                }
            } else {
                ThreadListPage(vm, currentFolder, onOpenThread, swipeLeft, swipeRight, onSnooze = { snoozeTarget = it })
            }
        }
    }

    if (sheetOpen) {
        AccountFolderSheet(
            accounts = accounts.map { it.email },
            currentAccount = account,
            onSwitchAccount = { sheetOpen = false; onSwitchAccount(it) },
            onAddAccount = { sheetOpen = false; onAddAccount() },
            onSelectFolder = { sheetOpen = false; vm.selectFolder(it) },
            onOpenTabbedInbox = { sheetOpen = false; vm.selectFolder(null) },
            onSettings = { sheetOpen = false; onSettings() },
            onDismiss = { sheetOpen = false },
        )
    }
    snoozeTarget?.let { threadId ->
        SnoozeSheet(
            onPick = { wakeAt -> vm.snoozeUntil(threadId, wakeAt); snoozeTarget = null },
            onDismiss = { snoozeTarget = null },
        )
    }
    if (labelSheetOpen) {
        LabelSheet(
            viewModel = vm,
            onApply = { labelId, add -> vm.applyLabel(labelId, add, vm.selection.toList()); labelSheetOpen = false },
            onDismiss = { labelSheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.ThreadListPage(
    vm: MailboxViewModel,
    folder: MailboxFolder,
    onOpenThread: (String) -> Unit,
    swipeLeft: SwipeAction,
    swipeRight: SwipeAction,
    onSnooze: (String) -> Unit,
) {
    val threads by vm.flowFor(folder).collectAsStateWithLifecycle(emptyList())
    if (threads.isEmpty()) {
        EmptyState("Nothing here")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(threads, key = { it.id }) { thread ->
                ThreadRow(
                    thread = thread,
                    onClick = {
                        if (vm.selection.isNotEmpty()) vm.toggleSelect(thread.id) else onOpenThread(thread.id)
                    },
                    onStarToggle = { vm.toggleStar(thread) },
                    onLongClick = { vm.toggleSelect(thread.id) },
                    selected = thread.id in vm.selection,
                    leftAction = swipeLeft,
                    rightAction = swipeRight,
                    onSwipe = { action ->
                        if (action == SwipeAction.SNOOZE) onSnooze(thread.id) else vm.perform(action, listOf(thread.id))
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkUnread: () -> Unit,
    onStar: () -> Unit,
    onSnooze: () -> Unit,
    onLabels: () -> Unit,
) {
    BottomAppBar {
        Text("  $count selected", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        IconButton(onArchive) { Icon(Icons.Filled.Archive, "Archive") }
        IconButton(onDelete) { Icon(Icons.Filled.Delete, "Delete") }
        IconButton(onMarkUnread) { Icon(Icons.Filled.MarkEmailUnread, "Mark unread") }
        IconButton(onStar) { Icon(Icons.Filled.Star, "Star") }
        IconButton(onSnooze) { Icon(Icons.Filled.Schedule, "Snooze") }
        IconButton(onLabels) { Icon(Icons.Filled.Label, "Labels") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountFolderSheet(
    accounts: List<String>,
    currentAccount: String,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSelectFolder: (MailboxFolder) -> Unit,
    onOpenTabbedInbox: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Accounts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
        accounts.forEach { email ->
            ListItem(
                headlineContent = { Text(email) },
                leadingContent = { Avatar(email) },
                trailingContent = { if (email == currentAccount) Text("✓", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onSwitchAccount(email) },
            )
        }
        ListItem(
            headlineContent = { Text("Add account") },
            leadingContent = { Icon(Icons.Filled.Add, null) },
            modifier = Modifier.clickable(onClick = onAddAccount),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Inbox (tabs)") },
            leadingContent = { Icon(Icons.Filled.Inbox, null) },
            modifier = Modifier.clickable(onClick = onOpenTabbedInbox),
        )
        OTHER_FOLDERS.forEach { folder ->
            ListItem(
                headlineContent = { Text(folder.title) },
                leadingContent = { Icon(Icons.Filled.Label, null) },
                modifier = Modifier.clickable { onSelectFolder(folder) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Settings") },
            leadingContent = { Icon(Icons.Filled.Settings, null) },
            modifier = Modifier.clickable(onClick = onSettings),
        )
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnoozeSheet(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val now = ZonedDateTime.now()
    data class Preset(val label: String, val at: ZonedDateTime)

    val presets = buildList {
        add(Preset("Later today (${SnoozePresets.laterToday(now).format(TIME_FMT)})", SnoozePresets.laterToday(now)))
        add(Preset("Tomorrow 8:00 AM", SnoozePresets.tomorrowMorning(now)))
        add(Preset("Next week Monday 8:00 AM", SnoozePresets.nextWeek(now)))
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Snooze until…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        presets.forEach { preset ->
            ListItem(
                headlineContent = { Text(preset.label) },
                modifier = Modifier.clickable { onPick(preset.at.toInstant().toEpochMilli()) },
            )
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelSheet(
    viewModel: MailboxViewModel,
    onApply: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val labels by viewModel.labels.collectAsStateWithLifecycle(initialValue = emptyList())
    val userLabels = labels.filter { it.type == "user" }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Apply label", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        if (userLabels.isEmpty()) {
            Text(
                "No user labels found. Create labels on gmail.com and they appear here.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        userLabels.forEach { label ->
            ListItem(
                headlineContent = { Text(label.name) },
                modifier = Modifier.clickable { onApply(label.id, true) },
            )
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
