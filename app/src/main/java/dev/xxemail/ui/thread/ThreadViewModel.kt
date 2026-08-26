package dev.xxemail.ui.thread

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private val _undoEvents = MutableSharedFlow<Undoable>(extraBufferCapacity = 4)
    val undoEvents: SharedFlow<Undoable> = _undoEvents

    init {
        viewModelScope.launch {
            runCatching { repo.markRead(listOf(threadId), read = true) }
            full = runCatching { repo.loadFullThread(threadId) }.getOrDefault(emptyList())
        }
    }

    fun attachments(): List<MailRepository.AttachmentMeta> = full.flatMap { it.attachments }

    suspend fun downloadAttachment(meta: MailRepository.AttachmentMeta): java.io.File = repo.downloadAttachment(meta)

    fun archive() = launchUndo { repo.archive(listOf(threadId)) }
    fun trash() = launchUndo { repo.trash(listOf(threadId)) }
    fun markUnread() = launchUndo { repo.markRead(listOf(threadId), read = false) }
    fun reportSpam() = launchUndo { repo.reportSpam(listOf(threadId)) }
    fun toggleStar(starred: Boolean) = launchUndo { repo.toggleStar(threadId, starred) }
    fun snooze(wakeAtMs: Long) = launchUndo { repo.snooze(threadId, wakeAtMs) }
    fun applyLabel(labelId: String, add: Boolean) = launchUndo { repo.applyLabel(labelId, add, listOf(threadId)) }

    private fun launchUndo(block: suspend () -> Undoable) {
        viewModelScope.launch { _undoEvents.emit(block()) }
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
