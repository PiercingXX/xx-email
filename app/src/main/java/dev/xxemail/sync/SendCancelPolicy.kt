package dev.xxemail.sync

import dev.xxemail.data.db.OutboxState

/**
 * Pure decision points for the undo-send race (E5).
 *
 * The worker claims a row by writing state=SENDING, then re-reads the row immediately
 * before `sendRaw`. If undo/cancel touched the row after the claim, the re-read no longer
 * shows SENDING and the send must abort. Symmetrically, undo may only cancel rows still
 * QUEUED — once SENDING the row must be refused, never deleted under a live send.
 */
object SendCancelPolicy {

    /**
     * Worker-side guard input: the row state observed at the pre-send re-read
     * (null ⇒ row was deleted by undo). Abort unless it is exactly our SENDING claim.
     */
    fun shouldAbortBeforeSend(stateAtRecheck: String?): Boolean =
        stateAtRecheck != OutboxState.SENDING.name

    /** Undo side: only a still-QUEUED row may be cancelled; SENDING must be refused. */
    fun canUndo(state: String?): Boolean = state == OutboxState.QUEUED.name
}
