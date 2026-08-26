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
import dev.xxemail.di.AppGraph
import dev.xxemail.domain.SafePaths
import dev.xxemail.ui.components.SendEvents
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

    val attachments = mutableStateListOf<File>()
    private var threadId: String? = null
    private var inReplyToHeader: String? = null

    fun prefill(
        initialTo: String,
        initialCc: String,
        initialSubject: String,
        threadIdArg: String,
        quoteMessageId: String,
        mode: String,
    ) {
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
                "reply", "reply_all" -> {
                    if (to.isBlank()) to = extractAddress(original.fromAddress)
                    if (mode == "reply_all" && cc.isBlank()) cc = original.ccCsv
                }
                "forward" -> {
                    to = ""
                    subject = if (subject.startsWith("Fwd:")) subject else "Fwd: ${original.subject}"
                }
            }
            if (subject.isBlank()) subject = "Re: ${original.subject}"
            val quoted = (original.bodyPlain ?: original.snippet).lines().joinToString("\n") { "> $it" }
            body = "\n\n$quoted\n\nOn ${original.date}, ${original.fromAddress} wrote:\n"
        }
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
                    error = t.message ?: "Could not attach file"
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
                    to = splitAddresses(to),
                    cc = splitAddresses(cc),
                    bcc = splitAddresses(bcc),
                    subject = subject.ifBlank { "(no subject)" },
                    bodyText = body,
                    threadId = threadId,
                    inReplyToMessageId = inReplyToHeader,
                    attachmentFiles = attachments.toList(),
                )
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

    companion object {
        private const val TAG = "ComposeViewModel"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Per-file cap mirrors the compose-time total attachment budget. */
        val MAX_UPLOAD_BYTES: Long = MimeComposer.MAX_TOTAL_ATTACHMENT_BYTES

        fun splitAddresses(raw: String): List<String> =
            raw.split(',', ';').map { it.trim() }.filter { it.contains('@') }

        private fun extractAddress(headerValue: String): String {
            val lt = headerValue.lastIndexOf('<')
            val gt = headerValue.lastIndexOf('>')
            return if (lt in 1 until gt) headerValue.substring(lt + 1, gt).trim() else headerValue.trim()
        }

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
fun rememberComposeViewModel(account: String): ComposeViewModel {
    val graph = LocalContext.current.appGraph
    return viewModel(
        key = "compose-$account",
        factory = viewModelFactory { initializer { ComposeViewModel(graph, account) } },
    )
}
