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
    var lastDirection by remember { mutableStateOf(SwipeToDismissBoxValue.Settled) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            lastDirection = value
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onAction(rightAction)
                SwipeToDismissBoxValue.EndToStart -> onAction(leftAction)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // always snap back; the action itself performs the removal
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val action = if (lastDirection == SwipeToDismissBoxValue.EndToStart) leftAction else rightAction
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 24.dp),
                horizontalArrangement =
                    if (lastDirection == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
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
    onStarToggle: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean,
    leftAction: SwipeAction,
    rightAction: SwipeAction,
    onSwipe: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    onUnsnooze: (() -> Unit)? = null,
) {
    SwipeableRow(leftAction = leftAction, rightAction = rightAction, onAction = onSwipe, modifier = modifier) {
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

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
