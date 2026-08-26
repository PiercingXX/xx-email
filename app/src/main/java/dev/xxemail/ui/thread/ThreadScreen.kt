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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.ReplyAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xxemail.appGraph
import dev.xxemail.domain.AddressUtils
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

    LaunchedEffect(vm) {
        vm.undoEvents.collect { u ->
            val result = snackbarHostState.showSnackbar(u.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) runCatching { u.revert() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(row?.subject.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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
                    FilledTonalButton(onClick = { onReply("forward", row?.subject.orEmpty(), "") }) {
                        Icon(Icons.Filled.Forward, null); Text("Fwd")
                    }
                }
                BottomAppBar {
                    IconButton(onClick = { vm.archive(); onBack() }) { Icon(Icons.Filled.Archive, "Archive") }
                    IconButton(onClick = { vm.trash(); onBack() }) { Icon(Icons.Filled.Delete, "Delete") }
                    IconButton(onClick = { vm.markUnread(); onBack() }) { Icon(Icons.Filled.MarkEmailUnread, "Mark unread") }
                    IconButton(onClick = { vm.snooze(SnoozePresets.tomorrowMorning(ZonedDateTime.now()).toInstant().toEpochMilli()); onBack() }) {
                        Icon(Icons.Filled.Schedule, "Snooze until tomorrow")
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
    AndroidView(
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setTextColor(android.graphics.Color.TRANSPARENT)
                linksClickable = true
            }
        },
        update = { tv ->
            tv.setTextColor(0xFF202124.toInt())
            tv.text = android.text.Html.fromHtml(sanitized, android.text.Html.FROM_HTML_MODE_LEGACY)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
