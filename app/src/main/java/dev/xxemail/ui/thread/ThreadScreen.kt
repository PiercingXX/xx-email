package dev.xxemail.ui.thread

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.ReplyAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xxemail.appGraph
import dev.xxemail.domain.AddressUtils
import dev.xxemail.domain.RemoteImagePolicy
import dev.xxemail.domain.SnoozePresets
import dev.xxemail.ui.components.Avatar
import dev.xxemail.ui.components.sanitizeHtml
import dev.xxemail.ui.components.timeAgo
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    account: String,
    threadId: String,
    onBack: () -> Unit,
    onReply: (mode: String, subject: String, quoteMessageId: String) -> Unit,
) {
    val vm = rememberThreadViewModel(account, threadId)
    val graph = LocalContext.current.appGraph
    val context = LocalContext.current
    val row by vm.row.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val allowRemoteImages by graph.settings.remoteImagesFlow.collectAsStateWithLifecycle(false)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // F4: only failures surface here — successful actions emit their undo to the
    // mailbox-level bus (UndoBus) and pop back via onDone.
    LaunchedEffect(vm) {
        androidx.compose.runtime.snapshotFlow { vm.actionError }
            .collect { message ->
                if (message != null) {
                    snackbarHostState.showSnackbar(message)
                    vm.consumeActionError()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(row?.subject.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    // F8: thread-level star toggle.
                    val starred = row?.starred
                    IconButton(
                        onClick = { if (starred != null) vm.toggleStar(!starred) },
                        enabled = starred != null,
                    ) {
                        Icon(
                            imageVector = if (starred == true) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (starred == true) "Unstar" else "Star",
                            tint = if (starred == true) Color(0xFFF4B400) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { onReply("reply", row?.subject.orEmpty(), vm.full.lastOrNull()?.entity?.id.orEmpty()) }) {
                        Icon(Icons.Filled.Reply, null); Spacer(Modifier.padding(2.dp)); Text("Reply")
                    }
                    FilledTonalButton(onClick = { onReply("reply_all", row?.subject.orEmpty(), vm.full.lastOrNull()?.entity?.id.orEmpty()) }) {
                        Icon(Icons.Filled.ReplyAll, null); Text("All")
                    }
                    FilledTonalButton(onClick = {
                        onReply("forward", row?.subject.orEmpty(), messages.lastOrNull()?.id.orEmpty())
                    }) {
                        Icon(Icons.Filled.Forward, null); Text("Fwd")
                    }
                }
                BottomAppBar {
                    // F4: navigation happens only AFTER the action succeeds (onDone);
                    // failures keep the user here with an error snackbar.
                    IconButton(onClick = { vm.archive(onBack) }) { Icon(Icons.Filled.Archive, "Archive") }
                    IconButton(onClick = { vm.trash(onBack) }) { Icon(Icons.Filled.Delete, "Delete") }
                    IconButton(onClick = { vm.markUnread(onBack) }) { Icon(Icons.Filled.MarkEmailUnread, "Mark unread") }
                    if (row?.snoozedUntil != null) {
                        IconButton(onClick = { vm.unsnooze() }) { Icon(Icons.Filled.AlarmOn, "Unsnooze") }
                    } else {
                        IconButton(
                            onClick = {
                                vm.snooze(SnoozePresets.tomorrowMorning(ZonedDateTime.now()).toInstant().toEpochMilli(), onBack)
                            },
                        ) {
                            Icon(Icons.Filled.Schedule, "Snooze until tomorrow")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                MessageCard(
                    message = message,
                    html = vm.full.getOrNull(index)?.html,
                    plain = vm.full.getOrNull(index)?.plain,
                    allowRemoteImages = allowRemoteImages,
                )
                HorizontalDivider()
            }
            val atts = vm.attachments()
            if (atts.isNotEmpty()) {
                item {
                    Column(Modifier.padding(12.dp)) {
                        Text("Attachments", style = MaterialTheme.typography.titleSmall)
                        atts.forEach { meta ->
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        runCatching { vm.downloadAttachment(meta) }.onSuccess { file ->
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                context.packageName + ".fileprovider",
                                                file,
                                            )
                                            val view = Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(uri, meta.mimeType.ifBlank { "application/octet-stream" })
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            runCatching { context.startActivity(Intent.createChooser(view, meta.filename)) }
                                        }
                                    }
                                },
                                label = { Text("${meta.filename} (${meta.size / 1024} KB)") },
                                leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: dev.xxemail.data.db.MessageEntity,
    html: String?,
    plain: String?,
    allowRemoteImages: Boolean,
) {
    val (name, _) = AddressUtils.split(message.fromAddress)
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name.ifBlank { message.fromAddress })
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(name.ifBlank { message.fromAddress }, fontWeight = FontWeight.Medium)
                Text(
                    "to ${message.toCsv}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(timeAgo(message.date), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.padding(4.dp))
        when {
            html != null -> HtmlBody(html, allowRemoteImages)
            plain != null -> Text(plain)
            else -> Text(message.snippet, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HtmlBody(html: String, allowRemoteImages: Boolean) {
    val sanitized = remember(html, allowRemoteImages) { sanitizeHtml(html, allowRemoteImages) }
    val textColor = MaterialTheme.colorScheme.onSurface
    AndroidView(
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                linksClickable = true
            }
        },
        update = { tv ->
            // F6: theme color instead of hardcoded light-theme gray (invisible in dark mode).
            tv.setTextColor(textColor.toArgb())
            tv.text = android.text.Html.fromHtml(
                sanitized,
                android.text.Html.FROM_HTML_MODE_LEGACY,
                GatedImageGetter(tv, allowRemoteImages),
                null,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * F6: remote-image gate for Html.fromHtml. When loading is disallowed (the default)
 * every <img> becomes a transparent placeholder; when allowed, the bitmap is fetched
 * off the main thread, capped to a sane width, and swapped into its span on the UI
 * thread. sanitizeHtml already strips <img> entirely when disallowed — defense in depth.
 */
private class GatedImageGetter(
    private val textView: android.widget.TextView,
    private val allowed: Boolean,
) : android.text.Html.ImageGetter {

    override fun getDrawable(source: String): android.graphics.drawable.Drawable {
        // 1x1 transparent stand-in keeps the span position stable either way.
        val placeholder = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            .apply { setBounds(0, 0, PLACEHOLDER_PX, PLACEHOLDER_PX) }
        if (!allowed) return placeholder
        if (!RemoteImagePolicy.isHttpsUrl(source)) return placeholder
        Thread {
            runCatching {
                val conn = java.net.URL(source).openConnection() as java.net.HttpURLConnection
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.instanceFollowRedirects = false
                    val bitmap = conn.inputStream.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(CappedInputStream(stream, RemoteImagePolicy.MAX_BYTES))
                    } ?: return@runCatching
                    val scale = minOf(
                        1f,
                        textView.width.coerceAtLeast(1).toFloat() / bitmap.width.coerceAtLeast(1),
                    )
                    val drawable = android.graphics.drawable.BitmapDrawable(textView.resources, bitmap).apply {
                        setBounds(
                            0,
                            0,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                    textView.post {
                        val editable = textView.text as? android.text.Editable ?: return@post
                        editable.getSpans(0, editable.length, android.text.style.ImageSpan::class.java)
                            .firstOrNull { it.drawable === placeholder }
                            ?.let { old ->
                                val start = editable.getSpanStart(old)
                                val end = editable.getSpanEnd(old)
                                if (start >= 0 && end >= start) {
                                    editable.removeSpan(old)
                                    editable.setSpan(
                                        android.text.style.ImageSpan(drawable),
                                        start,
                                        end,
                                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                    )
                                }
                            }
                    }
                } finally {
                    conn.disconnect()
                }
            }.onFailure { android.util.Log.w("GatedImageGetter", "Remote image failed: $source", it) }
        }.start()
        return placeholder
    }

    private companion object {
        const val PLACEHOLDER_PX = 1
    }
}

/** Stops a remote-image download once it exceeds [maxBytes] so a huge part cannot OOM. */
private class CappedInputStream(
    private val inner: java.io.InputStream,
    private val maxBytes: Long,
) : java.io.InputStream() {
    private var seen = 0L

    override fun read(): Int {
        if (seen >= maxBytes) return -1
        val b = inner.read()
        if (b >= 0) seen++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (seen >= maxBytes) return -1
        val allowed = minOf(len.toLong(), maxBytes - seen).toInt()
        val n = inner.read(b, off, allowed)
        if (n > 0) seen += n
        return n
    }

    override fun close() = inner.close()
}
