package dev.xxemail.ui.compose

import android.content.Context
import android.net.Uri
import android.util.Log
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
import dev.xxemail.data.api.MimeComposer
import dev.xxemail.data.repo.ComposeRequest
import dev.xxemail.data.repo.MailRepository
import dev.xxemail.di.AppGraph
import dev.xxemail.domain.Recipients
import dev.xxemail.domain.SafePaths
import dev.xxemail.ui.components.SendEvents
import dev.xxemail.ui.components.fullDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.ZonedDateTime

class ComposeViewModel(
    private val graph: AppGraph,
    val account: String,
) : ViewModel() {

    private val repo = graph.mailRepository(account)

    var to by mutableStateOf("")
    var cc by mutableStateOf("")
    var bcc by mutableStateOf("")
    var subject by mutableStateOf("")
    var body by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Forward-only offer: original message attachments the user can pull in. */
    var offeredForwardAttachments by mutableStateOf<List<MailRepository.AttachmentMeta>>(emptyList())
        private set

    val attachments = mutableStateListOf<File>()
    private var threadId: String? = null
    private var inReplyToHeader: String? = null

    /** Args signature of the last prefill — identical args (config change) keep user edits. */
    private var prefillKey: String? = null

    /**
     * Resets every draft field. Called at most once per distinct argument set so a fresh
     * compose never inherits a previous draft's body/attachments/threading state (E1).
     */
    private fun resetDraft() {
        to = ""
        cc = ""
        bcc = ""
        subject = ""
        body = ""
        error = null
        offeredForwardAttachments = emptyList()
        inReplyToHeader = null
        threadId = null
        val stale = attachments.toList()
        attachments.clear()
        if (stale.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) { stale.forEach { it.delete() } }
        }
    }

    fun prefill(
        initialTo: String,
        initialCc: String,
        initialSubject: String,
        threadIdArg: String,
        quoteMessageId: String,
        mode: String,
    ) {
        val key = listOf(account, initialTo, initialCc, initialSubject, threadIdArg, quoteMessageId, mode).joinToString("|")
        if (prefillKey == key) return // same screen recomposed — keep in-progress edits
        prefillKey = key
        resetDraft()

        to = initialTo
        cc = initialCc
        subject = initialSubject
        threadId = threadIdArg.ifBlank { null }
        if (quoteMessageId.isBlank()) return
        viewModelScope.launch {
            val original = runCatching { repo.messageSnapshot(quoteMessageId) }.getOrNull() ?: return@launch
            inReplyToHeader = original.messageIdHeader
            if (threadId.isNullOrBlank()) threadId = original.threadId

            when (mode) {
                "reply" -> {
                    to = Recipients.replyTo(original.fromAddress, original.toCsv, account).joinToString(", ")
                    cc = ""
                }
                "reply_all" -> {
                    val (toSet, ccSet) = Recipients.replyAll(original.fromAddress, original.toCsv, original.ccCsv, account)
                    to = toSet.joinToString(", ")
                    cc = ccSet.joinToString(", ")
                }
                "forward" -> {
                    to = ""
                    cc = ""
                    subject = if (subject.startsWith("Fwd:")) subject else "Fwd: ${original.subject}"
                    if (original.hasAttachments) {
                        loadForwardAttachmentOffer(quoteMessageId, original.threadId)
                    }
                }
            }
            if (mode != "forward" && subject.isBlank()) subject = "Re: ${original.subject}"

            // Quote from the real body; fetch it if sync only stored metadata (E2).
            val plain = fetchQuotedBody(quoteMessageId, original)
            val wroteLine =
                "On ${fullDate(original.date).ifBlank { "an unknown date" }}, " +
                    original.fromAddress.ifBlank { "an unknown sender" } + " wrote:"
            body = if (mode == "forward") {
                "\n\n$wroteLine\n\n$plain\n"
            } else {
                val quoted = plain.lines().joinToString("\n") { "> $it" }
                "\n\n$wroteLine\n\n$quoted\n"
            }
        }
    }

    /** Bodies may still be metadata-only right after open — go through loadFullThread. */
    private suspend fun fetchQuotedBody(
        quoteMessageId: String,
        snapshot: dev.xxemail.data.db.MessageEntity,
    ): String {
        snapshot.bodyPlain?.let { return it }
        val full = runCatching { repo.loadFullThread(snapshot.threadId) }.getOrDefault(emptyList())
        return full.firstOrNull { it.entity.id == quoteMessageId }?.let { it.plain ?: it.entity.bodyPlain }
            ?: snapshot.snippet
    }

    /** Offers the forwarded message's attachments for inclusion (downloaded on accept). */
    private suspend fun loadForwardAttachmentOffer(quoteMessageId: String, threadId: String) {
        val full = runCatching { repo.loadFullThread(threadId) }.getOrDefault(emptyList())
        offeredForwardAttachments = full
            .firstOrNull { it.entity.id == quoteMessageId }
            ?.attachments
            .orEmpty()
    }

    /** Downloads the offered originals into the attachment list (existing repo method). */
    fun includeOriginalAttachments() {
        val metas = offeredForwardAttachments
        if (metas.isEmpty()) return
        offeredForwardAttachments = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            metas.forEach { meta ->
                runCatching { repo.downloadAttachment(meta) }
                    .onSuccess { file -> attachments.add(file) }
                    .onFailure { t ->
                        Log.w(TAG, "Could not include forwarded attachment ${meta.filename}", t)
                        withContext(Dispatchers.Main) {
                            error = t.message ?: "Could not include attachment"
                        }
                    }
            }
        }
    }

    fun declineForwardAttachments() {
        offeredForwardAttachments = emptyList()
    }

    /**
     * Copies the selected document into `cacheDir/uploads` under a sanitized name.
     * Provider-supplied display names are reduced to a safe single path segment, the
     * target is verified inside the uploads directory, and copying is capped at
     * [MAX_UPLOAD_BYTES] so a huge file cannot exhaust memory/disk.
     */
    fun addAttachment(context: Context, uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
            val name = SafePaths.childNameOr(
                raw = displayName,
                fallbackSeed = uri.lastPathSegment,
                lastResort = "upload-${System.currentTimeMillis()}",
            )
            val target = File(dir, "${System.currentTimeMillis()}-$name")
            runCatching {
                check(SafePaths.isInside(dir, target)) { "Resolved upload path escapes the uploads directory" }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedOutputStream(FileOutputStream(target)).use { output ->
                        val buf = ByteArray(COPY_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            check(total <= MAX_UPLOAD_BYTES) {
                                "Attachment exceeds the ${MAX_UPLOAD_BYTES / 1024 / 1024} MB limit"
                            }
                            output.write(buf, 0, n)
                        }
                    }
                } ?: error("Could not open the selected file")
            }.onSuccess { if (target.exists()) attachments.add(target) }
                .onFailure { t ->
                    Log.w(TAG, "Attachment failed for $displayName", t)
                    target.delete()
                    withContext(Dispatchers.Main) { error = t.message ?: "Could not attach file" }
                }
        }
    }

    fun removeAttachment(file: File) {
        attachments.remove(file)
        file.delete()
    }

    fun send(scheduledAt: Long?, onQueued: () -> Unit) {
        sending = true
        error = null
        viewModelScope.launch {
            try {
                val request = ComposeRequest(
                    to = Recipients.parseValidated(to),
                    cc = Recipients.parseValidated(cc),
                    bcc = Recipients.parseValidated(bcc),
                    subject = subject.ifBlank { "(no subject)" },
                    bodyText = body,
                    threadId = threadId,
                    inReplyToMessageId = inReplyToHeader,
                    attachmentFiles = attachments.toList(),
                )
                require(request.to.isNotEmpty() || request.cc.isNotEmpty() || request.bcc.isNotEmpty()) {
                    "No valid recipients"
                }
                val outboxId = repo.enqueueSend(request, scheduledAt)
                SendEvents.queued.emit(
                    SendEvents.QueuedSend(
                        accountEmail = account,
                        outboxId = outboxId,
                        label = if (scheduledAt == null) "Sending…" else "Send scheduled",
                    ),
                )
                onQueued()
            } catch (t: Throwable) {
                error = t.message ?: "Failed to queue message"
            } finally {
                sending = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Draft discarded/left: staged upload copies and downloaded originals are no
        // longer referenced — drop them so nothing lingers past this compose session.
        attachments.forEach { it.delete() }
        attachments.clear()
    }

    companion object {
        private const val TAG = "ComposeViewModel"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Per-file cap mirrors the compose-time total attachment budget. */
        val MAX_UPLOAD_BYTES: Long = MimeComposer.MAX_TOTAL_ATTACHMENT_BYTES

        /** Schedule presets mirroring Gmail's UX constants. */
        fun schedulePresets(now: ZonedDateTime): List<Pair<String, ZonedDateTime>> {
            val tomorrow8 = now.plusDays(1).toLocalDate().atTime(8, 0).atZone(now.zone)
            val tomorrow13 = now.plusDays(1).toLocalDate().atTime(13, 0).atZone(now.zone)
            var monday = now.plusDays(1).toLocalDate()
            while (monday.dayOfWeek != DayOfWeek.MONDAY) monday = monday.plusDays(1)
            return listOf(
                "Tomorrow 8:00 AM" to tomorrow8,
                "Tomorrow 1:00 PM" to tomorrow13,
                "Monday 8:00 AM" to monday.atTime(8, 0).atZone(now.zone),
                "In 1 hour" to now.plusHours(1),
            )
        }
    }
}

@Composable
fun rememberComposeViewModel(
    account: String,
    threadId: String,
    quoteMessageId: String,
    mode: String,
): ComposeViewModel {
    val graph = LocalContext.current.appGraph
    return viewModel(
        // Key includes the route identity so a reply/forward/new-compose never reuses a
        // previous compose's ViewModel (and its leftover state) — E1.
        key = "compose-$account-$threadId-$quoteMessageId-$mode",
        factory = viewModelFactory { initializer { ComposeViewModel(graph, account) } },
    )
}
