package dev.xxemail.ui.mailbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.xxemail.appGraph
import dev.xxemail.data.db.ThreadEntity
import dev.xxemail.data.repo.SwipeAction
import dev.xxemail.data.repo.Undoable
import dev.xxemail.di.AppGraph
import dev.xxemail.domain.MailboxFolder
import dev.xxemail.domain.SnoozePresets
import dev.xxemail.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

class MailboxViewModel(
    private val graph: AppGraph,
    val account: String,
) : ViewModel() {

    private val repo = graph.mailRepository(account)

    /** null ⇒ tabbed inbox (Primary/Social/Promotions/Updates/Forums). */
    var folder by mutableStateOf<MailboxFolder?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    val selection = mutableStateListOf<String>()

    /** True when token refresh failed permanently for this account (re-auth required). */
    val needsReauth: StateFlow<Boolean> = graph.auth.reauthNeeded
        .map { it.contains(account) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the encrypted token file exists but could not be decrypted/read. */
    val storeUnreadable: StateFlow<Boolean> = graph.tokens.unreadable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _undoEvents = MutableSharedFlow<Undoable>(extraBufferCapacity = 4)
    val undoEvents: SharedFlow<Undoable> = _undoEvents

    val labels = repo.observeLabels()

    private val folderFlows = HashMap<MailboxFolder, StateFlow<List<ThreadEntity>>>()

    init {
        refresh()
        viewModelScope.launch {
            SyncScheduler.ensurePeriodic(graph.workManager, graph.settings.syncMinutes())
        }
    }

    fun flowFor(folder: MailboxFolder): StateFlow<List<ThreadEntity>> =
        folderFlows.getOrPut(folder) {
            repo.observeFolder(folder)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    fun selectFolder(folder: MailboxFolder?) {
        this.folder = folder
        selection.clear()
        folder?.let { target ->
            viewModelScope.launch { runCatching { repo.ensureHydrated(target) } }
        }
    }

    fun refresh(forceFull: Boolean = false) {
        viewModelScope.launch {
            refreshing = true
            runCatching { repo.sync(forceFull) }
            refreshing = false
        }
    }

    fun toggleSelect(threadId: String) {
        if (!selection.remove(threadId)) selection.add(threadId)
    }

    fun clearSelection() = selection.clear()

    private suspend fun emit(undoable: Undoable) = _undoEvents.emit(undoable)

    fun perform(action: SwipeAction, threadIds: List<String>) {
        if (threadIds.isEmpty()) return
        viewModelScope.launch {
            val undoable = when (action) {
                SwipeAction.ARCHIVE -> repo.archive(threadIds)
                SwipeAction.DELETE -> repo.trash(threadIds)
                SwipeAction.MARK_READ -> repo.markRead(threadIds, read = true)
                SwipeAction.STAR -> repo.toggleStar(threadIds.first(), starred = true)
                SwipeAction.SNOOZE -> snoozeInternal(threadIds.first(), SnoozePresets.tomorrowMorning(ZonedDateTime.now()))
            }
            clearSelection()
            emit(undoable)
        }
    }

    fun markUnread(threadIds: List<String>) {
        viewModelScope.launch {
            emit(repo.markRead(threadIds, read = false))
            clearSelection()
        }
    }

    fun reportSpam(threadIds: List<String>) {
        viewModelScope.launch {
            emit(repo.reportSpam(threadIds))
            clearSelection()
        }
    }

    fun snoozeUntil(threadId: String, wakeAtEpochMs: Long) {
        viewModelScope.launch {
            val wakeAt = java.time.Instant.ofEpochMilli(wakeAtEpochMs).atZone(java.time.ZoneId.systemDefault())
            emit(snoozeInternal(threadId, wakeAt))
            clearSelection()
        }
    }

    fun applyLabel(labelId: String, add: Boolean, threadIds: List<String>) {
        viewModelScope.launch {
            runCatching { repo.applyLabel(labelId, add, threadIds) }
                .onSuccess { emit(it) }
            clearSelection()
        }
    }

    fun toggleStar(thread: ThreadEntity) {
        viewModelScope.launch { emit(repo.toggleStar(thread.id, !thread.starred)) }
    }

    fun cancelQueuedSend(outboxId: Long) {
        viewModelScope.launch { runCatching { repo.cancelQueuedSend(outboxId) } }
    }

    private suspend fun snoozeInternal(threadId: String, wakeAt: ZonedDateTime): Undoable =
        repo.snooze(threadId, wakeAt.toInstant().toEpochMilli())
}

@Composable
fun rememberMailboxViewModel(account: String): MailboxViewModel {
    val graph = LocalContext.current.appGraph
    return viewModel(
        key = "mailbox-$account",
        factory = viewModelFactory {
            initializer { MailboxViewModel(graph, account) }
        },
    )
}
