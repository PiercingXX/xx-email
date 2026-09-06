package dev.xxemail.sync

import dev.xxemail.data.db.OutboxEntity
import dev.xxemail.data.db.OutboxKind
import dev.xxemail.data.db.OutboxState
import java.io.File

/**
 * Offline-compose enqueue math. MIME is composed locally, the RFC822 payload
 * is written under `files/outbox/`, and WorkManager is asked to send later
 * under a CONNECTED constraint. Nothing here talks to Gmail — that is the
 * airplane-mode contract (E3).
 */
data class OutboxSendPlan(
    val kind: OutboxKind,
    val targetAt: Long,
    val initialDelayMs: Long,
    val requiresConnectedNetwork: Boolean = true,
) {
    val state: OutboxState get() = OutboxState.QUEUED
}

object OutboxSend {

    fun plan(nowMs: Long, undoSeconds: Int, scheduledAt: Long?): OutboxSendPlan {
        val kind = if (scheduledAt == null) OutboxKind.SEND else OutboxKind.SCHEDULED_SEND
        val targetAt = scheduledAt ?: (nowMs + undoSeconds.coerceAtLeast(0) * 1000L)
        return OutboxSendPlan(
            kind = kind,
            targetAt = targetAt,
            initialDelayMs = (targetAt - nowMs).coerceAtLeast(0),
            requiresConnectedNetwork = true,
        )
    }

    fun entity(
        accountEmail: String,
        threadId: String?,
        subject: String,
        plan: OutboxSendPlan,
        nowMs: Long,
    ): OutboxEntity = OutboxEntity(
        accountEmail = accountEmail,
        kind = plan.kind.name,
        threadId = threadId,
        rfc822Base64 = null,
        subject = subject,
        targetAt = plan.targetAt,
        state = OutboxState.QUEUED.name,
        createdAt = nowMs,
    )

    /**
     * File-backed payload write. Returns the relative path and size stored on
     * the outbox row. Does not touch the network.
     */
    fun writePayload(filesDir: File, id: Long, bytes: ByteArray): Pair<String, Long> {
        val path = OutboxFiles.writeNew(filesDir, id, bytes)
        return path to bytes.size.toLong()
    }

    /** Compose snackbar: enqueue is success, even with no network. */
    fun queuedLabel(scheduled: Boolean): String = if (scheduled) "Send scheduled" else "Queued"

    fun canRetry(state: String): Boolean = state == OutboxState.FAILED.name

    fun canDiscard(state: String): Boolean =
        state == OutboxState.FAILED.name || state == OutboxState.QUEUED.name

    fun isOpen(state: String): Boolean = state == OutboxState.QUEUED.name ||
        state == OutboxState.FAILED.name ||
        state == OutboxState.SENDING.name
}
