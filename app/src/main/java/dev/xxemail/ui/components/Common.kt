package dev.xxemail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xxemail.data.db.ThreadEntity
import dev.xxemail.data.repo.SwipeAction
import dev.xxemail.data.repo.Undoable
import dev.xxemail.domain.AddressUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cross-screen bus so ComposeScreen can surface an Undo-send snackbar on the list screen. */
object SendEvents {
    data class QueuedSend(val accountEmail: String, val outboxId: Long, val label: String)

    val queued = MutableSharedFlow<QueuedSend>(extraBufferCapacity = 8)
}

/**
 * Mailbox-level undo bus (F4): thread-screen actions emit here so the undo snackbar is
 * hosted by the mailbox and survives leaving the thread. Events carry the owning account;
 * mailboxes ignore other accounts' events. replay=1 covers the pop-back window where the
 * mailbox recomposes just after the emission.
 */
object UndoBus {
    data class Event(val accountEmail: String, val undoable: Undoable)

    val events = MutableSharedFlow<Event>(replay = 1, extraBufferCapacity = 8)

    suspend fun emit(accountEmail: String, undoable: Undoable) {
        events.emit(Event(accountEmail, undoable))
    }
}

fun avatarColor(seed: String): Color {
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    val hues = listOf(0xFF23306B, 0xFF6B2E23, 0xFF1F5C45, 0xFF5B2367, 0xFF23486B, 0xFF6B5A23)
    return Color(hues[Math.floorMod(hash, hues.size)])
}

fun timeAgo(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val diff = System.currentTimeMillis() - epochMs
    val minutes = diff / 60_000
    val hours = minutes / 60
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        hours < 24 * 7 -> "${hours / 24}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}

fun fullDate(epochMs: Long): String =
    if (epochMs <= 0) "" else SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(epochMs))

/**
 * Strips remote-content and script vectors from email HTML.
 * Remote images are removed unless the user globally allows them (tracking-pixel defense).
 *
 * TextView-only sanitizer for the Html.fromHtml render path (F6): it is a best-effort
 * regex pass over a plain string, NOT a general HTML hardening layer — do not reuse it
 * with a WebView, which needs a real parser/allowlist.
 */
fun sanitizeHtml(html: String, allowRemoteImages: Boolean): String {
    var out = html
        .replace(Regex("(?is)<script.*?</script>"), "")
        .replace(Regex("(?is)<style.*?</style>"), "")
        .replace(Regex("(?i)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
        .replace(Regex("(?i)javascript:"), "")
    if (!allowRemoteImages) {
        out = out.replace(Regex("(?is)<img[^>]*>"), "")
    }
    return out
}

@Composable
fun Avatar(nameOrAddress: String, modifier: Modifier = Modifier) {
    val display = nameOrAddress.ifBlank { "?" }
    Box(
        modifier = modifier
            .size(40.dp)
            .background(avatarColor(display), CircleShape)
            .semantics { contentDescription = "Avatar for $display" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = AddressUtils.initials(display),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

private fun swipeIcon(action: SwipeAction): ImageVector = when (action) {
    SwipeAction.ARCHIVE -> Icons.Filled.Archive
    SwipeAction.DELETE -> Icons.Filled.Delete
    SwipeAction.MARK_READ -> Icons.Filled.MarkEmailUnread
    SwipeAction.STAR -> Icons.Filled.Star
    SwipeAction.SNOOZE -> Icons.Filled.Schedule
}

private fun swipeLabel(action: SwipeAction): String = when (action) {
    SwipeAction.ARCHIVE -> "Archive"
    SwipeAction.DELETE -> "Delete"
    SwipeAction.MARK_READ -> "Mark read"
    SwipeAction.STAR -> "Star"
    SwipeAction.SNOOZE -> "Snooze"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableRow(
    leftAction: SwipeAction,
    rightAction: SwipeAction,
    onAction: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // F8: confirmValueChange only records the intent; the action itself fires AFTER the
    // box settles back, via this effect. No mail actions run inside the gesture callback.
    var pendingAction by remember { mutableStateOf<SwipeAction?>(null) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> pendingAction = rightAction
                SwipeToDismissBoxValue.EndToStart -> pendingAction = leftAction
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // always snap back; the action itself performs the removal
        },
    )
    LaunchedEffect(pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect
        onAction(action)
        pendingAction = null
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            // Live target drives the hint so it follows the finger during the drag.
            val direction = state.targetValue
            val action = if (direction == SwipeToDismissBoxValue.EndToStart) leftAction else rightAction
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 24.dp),
                horizontalArrangement =
                    if (direction == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(swipeIcon(action), contentDescription = swipeLabel(action))
                Text(" ${swipeLabel(action)}", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        content()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ThreadRow(
    thread: ThreadEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** Null disables swipe gestures entirely — no dead chrome (search results, F7). */
    onSwipe: ((SwipeAction) -> Unit)? = {},
    leftAction: SwipeAction = SwipeAction.ARCHIVE,
    rightAction: SwipeAction = SwipeAction.DELETE,
    /** Null hides the star control instead of rendering a no-op button (F7). */
    onStarToggle: (() -> Unit)? = null,
    onUnsnooze: (() -> Unit)? = null,
) {
    val rowContent: @Composable () -> Unit = {
        Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(thread.fromName.ifBlank { thread.fromAddress })
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = thread.fromName.ifBlank { thread.fromAddress },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = timeAgo(thread.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = thread.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = thread.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (thread.hasAttachments) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Has attachments",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onUnsnooze != null) {
                    IconButton(onClick = onUnsnooze) {
                        Icon(
                            imageVector = Icons.Filled.AlarmOn,
                            contentDescription = "Unsnooze",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (onStarToggle != null) {
                    IconButton(onClick = onStarToggle) {
                        Icon(
                            imageVector = if (thread.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (thread.starred) "Unstar" else "Star",
                            tint = if (thread.starred) Color(0xFFF4B400) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (onSwipe != null) {
        SwipeableRow(leftAction = leftAction, rightAction = rightAction, onAction = onSwipe, modifier = modifier) {
            rowContent()
        }
    } else {
        Box(modifier) { rowContent() }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
