package dev.xxemail.ui.thread

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.xxemail.appGraph
import dev.xxemail.data.db.MessageEntity
import dev.xxemail.data.db.ThreadEntity
import dev.xxemail.data.repo.MailRepository
import dev.xxemail.data.repo.Undoable
import dev.xxemail.di.AppGraph
import dev.xxemail.ui.components.UndoBus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ThreadViewModel(
    private val graph: AppGraph,
    private val account: String,
    val threadId: String,
) : ViewModel() {

    private val repo: MailRepository = graph.mailRepository(account)

    val row: StateFlow<ThreadEntity?> =
        repo.observeThreadRow(threadId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<MessageEntity>> =
        repo.observeThread(threadId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Full bodies + attachment metadata, fetched lazily on open. */
    var full by mutableStateOf<List<MailRepository.FullMessage>>(emptyList())
        private set

    /** Last failed action's message — surfaced as a snackbar; the user stays put (F4). */
    var actionError by mutableStateOf<String?>(null)
        private set

    /** Called by the UI once an [actionError] was displayed so it can fire again. */
    fun consumeActionError() {
        actionError = null
    }

    init {
        viewModelScope.launch {
            runCatching { repo.markRead(listOf(threadId), read = true) }
            full = runCatching { repo.loadFullThread(threadId) }.getOrDefault(emptyList())
        }
    }

    fun attachments(): List<MailRepository.AttachmentMeta> = full.flatMap { it.attachments }

    suspend fun downloadAttachment(meta: MailRepository.AttachmentMeta): java.io.File = repo.downloadAttachment(meta)

    fun archive(onDone: () -> Unit = {}) = launchUndo(onDone) { repo.archive(listOf(threadId)) }
    fun trash(onDone: () -> Unit = {}) = launchUndo(onDone) { repo.trash(listOf(threadId)) }
    fun markUnread(onDone: () -> Unit = {}) = launchUndo(onDone) { repo.markRead(listOf(threadId), read = false) }
    fun reportSpam(onDone: () -> Unit = {}) = launchUndo(onDone) { repo.reportSpam(listOf(threadId)) }
    fun toggleStar(starred: Boolean, onDone: () -> Unit = {}) = launchUndo(onDone) { repo.toggleStar(threadId, starred) }
    fun snooze(wakeAtMs: Long, onDone: () -> Unit = {}) = launchUndo(onDone) { repo.snooze(threadId, wakeAtMs) }
    fun applyLabel(labelId: String, add: Boolean, onDone: () -> Unit = {}) =
        launchUndo(onDone) { repo.applyLabel(labelId, add, listOf(threadId)) }

    /** Real unsnooze (cancels wake work + row, restores INBOX). No undo — snoozing again is one tap. */
    fun unsnooze() {
        viewModelScope.launch {
            runCatching { repo.unsnooze(threadId) }
                .onFailure {
                    Log.w(TAG, "Unsnooze failed for $threadId", it)
                    actionError = it.message ?: "Unsnooze failed"
                }
        }
    }

    /**
     * F4: actions run to completion BEFORE the caller navigates back; undo events go to
     * the mailbox-level bus so the snackbar survives leaving this screen; failures set
     * [actionError] instead of being swallowed.
     */
    private fun launchUndo(onSuccess: () -> Unit = {}, block: suspend () -> Undoable) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { undoable ->
                    actionError = null
                    UndoBus.emit(account, undoable)
                    onSuccess()
                }
                .onFailure { actionError = it.message ?: "Action failed" }
        }
    }
}

@Composable
fun rememberThreadViewModel(account: String, threadId: String): ThreadViewModel {
    val graph = LocalContext.current.appGraph
    return viewModel(
        key = "thread-$account-$threadId",
        factory = viewModelFactory { initializer { ThreadViewModel(graph, account, threadId) } },
    )
}

private const val TAG = "ThreadViewModel"
