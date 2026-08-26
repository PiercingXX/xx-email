package dev.xxemail.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val email: String,
    val displayName: String = "",
    /** Last processed Gmail history id (uint64 stored as string — never arithmetic). */
    val historyId: String? = null,
    val lastSyncAt: Long? = null,
)

@Entity(tableName = "labels", primaryKeys = ["accountEmail", "id"])
data class LabelEntity(
    val accountEmail: String,
    val id: String,
    val name: String,
    val type: String? = null,
    val colorBg: String? = null,
    val colorText: String? = null,
)

/**
 * Denormalized conversation row for fast list rendering. Aggregates are rebuilt
 * from [MessageEntity] rows during sync.
 */
@Entity(tableName = "threads", primaryKeys = ["accountEmail", "id"])
data class ThreadEntity(
    val accountEmail: String,
    val id: String,
    val snippet: String = "",
    val subject: String = "",
    val fromAddress: String = "",
    val fromName: String = "",
    /** Timestamp (ms) of the newest message — sort key. */
    val date: Long = 0L,
    val messageCount: Int = 0,
    val unreadCount: Int = 0,
    val hasAttachments: Boolean = false,
    val starred: Boolean = false,
    /** True while the server-side INBOX label is present. */
    val inInbox: Boolean = true,
    /** CSV of CATEGORY_* labels present (empty ⇒ Primary). */
    val categories: String = "",
    /** CSV of all labels on the thread's newest message union. */
    val labelsCsv: String = "",
    /** Local-only snooze wake time (ms epoch), null when not snoozed. */
    val snoozedUntil: Long? = null,
)

@Entity(tableName = "messages", primaryKeys = ["accountEmail", "id"])
data class MessageEntity(
    val accountEmail: String,
    val id: String,
    val threadId: String,
    val subject: String = "",
    val fromAddress: String = "",
    val toCsv: String = "",
    val ccCsv: String = "",
    val date: Long = 0L,
    val snippet: String = "",
    val read: Boolean = false,
    val starred: Boolean = false,
    val hasAttachments: Boolean = false,
    val labelsCsv: String = "",
    val messageIdHeader: String? = null,
    val bodyHtml: String? = null,
    val bodyPlain: String? = null,
    val bodyFetched: Boolean = false,
)

/** Offline full-text index over cached messages. */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    val subject: String,
    val snippet: String,
    val fromAddress: String,
    val toCsv: String,
)

/**
 * Local queue powering undo-send, scheduled send and snooze wake-ups.
 *
 * Send payloads are stored as files (`files/outbox/{id}.eml`) — [path]/[size] —
 * so Room never pulls megabytes of RFC822 through a CursorWindow.
 * [rfc822Base64] is a legacy v0.1 column kept nullable for migration safety:
 * pre-upgrade QUEUED rows carry only the base64 payload; the worker decodes it,
 * writes the file, and clears the column.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountEmail: String,
    /** One of [OutboxKind] names. */
    val kind: String,
    val threadId: String? = null,
    /** Legacy v0.1 base64url RFC822 payload; null for new rows after first send attempt. */
    val rfc822Base64: String? = null,
    /** Relative path (under filesDir) of the file-backed RFC822 payload, when present. */
    val path: String? = null,
    /** Payload size in bytes. */
    val size: Long = 0,
    val subject: String = "",
    /** Epoch ms at which the job should fire (send time / wake time). */
    val targetAt: Long,
    /** One of [OutboxState] names. */
    val state: String = OutboxState.QUEUED.name,
    val attempts: Int = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class OutboxKind { SEND, SCHEDULED_SEND, SNOOZE_WAKE }

enum class OutboxState { QUEUED, SENDING, SENT, WOKEN, FAILED, CANCELLED }

/**
 * Durable record of a pending snooze wake. Source of truth for [dev.xxemail.sync.SnoozeWorker]:
 * wakes survive WorkManager input-data loss because the worker can rediscover due
 * rows here even with empty input data.
 */
@Entity(tableName = "snooze_wakes", primaryKeys = ["accountEmail", "threadId"])
data class SnoozeWakeEntity(
    val accountEmail: String,
    val threadId: String,
    /** Epoch ms at which INBOX should be restored. */
    val targetAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
)
