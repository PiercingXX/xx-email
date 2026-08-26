package dev.xxemail.data.api

import dev.xxemail.data.db.ThreadEntity
import dev.xxemail.domain.AddressUtils

/**
 * Pure thread → aggregate mapping used by sync hydration (no Android deps).
 *
 * Aggregates are built from the union of ALL messages' labels, not just the
 * newest message: after a reply the newest message carries only SENT, but the
 * thread stays in Inbox as long as any message still has INBOX.
 */
object ThreadAggregation {

    fun build(accountEmail: String, thread: Thread, snoozedUntil: Long? = null): ThreadEntity {
        val msgs = thread.messages
        val latest = msgs.maxByOrNull { it.internalDate?.toLongOrNull() ?: 0L }
            ?: Message(id = thread.id, threadId = thread.id)
        val labelsUnion = msgs.flatMapTo(LinkedHashSet()) { it.labelIds }
        val (name, _) = AddressUtils.split(header(latest, "From"))
        return ThreadEntity(
            accountEmail = accountEmail,
            id = thread.id,
            snippet = thread.snippet ?: latest.snippet.orEmpty(),
            subject = header(latest, "Subject").ifBlank { "(no subject)" },
            fromAddress = header(latest, "From"),
            fromName = name,
            date = latest.internalDate?.toLongOrNull() ?: 0L,
            messageCount = msgs.size,
            unreadCount = msgs.count { it.labelIds.contains("UNREAD") },
            hasAttachments = msgs.any { MessageParts.hasAttachment(it) },
            starred = labelsUnion.contains("STARRED"),
            inInbox = labelsUnion.contains("INBOX"),
            categories = labelsUnion.filter { it.startsWith("CATEGORY_") }.joinToString(","),
            labelsCsv = labelsUnion.joinToString(","),
            snoozedUntil = snoozedUntil,
        )
    }

    private fun header(m: Message, name: String): String =
        m.payload?.headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value.orEmpty()
}
