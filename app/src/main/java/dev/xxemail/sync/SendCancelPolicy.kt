package dev.xxemail.sync

import dev.xxemail.data.db.OutboxState

/**
 * Pure decision points for the undo-send race (E5).
 *
 * The QUEUED→SENDING flip is a two-sided CAS on the DAO: the worker claims via
 * `OutboxDao.claimIfQueued` (`WHERE state='QUEUED'`) and aborts when it returns 0;
 * undo cancels via `OutboxDao.cancelIfQueued` with the same guard. Whichever side
 * wins the atomic flip, the other deterministically loses — no re-read heuristic.
 */
object SendCancelPolicy {

    /** Undo side: only a still-QUEUED row may be cancelled; SENDING must be refused. */
    fun canUndo(state: String?): Boolean = state == OutboxState.QUEUED.name
}
