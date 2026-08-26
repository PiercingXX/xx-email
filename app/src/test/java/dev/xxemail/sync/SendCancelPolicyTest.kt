package dev.xxemail.sync

import dev.xxemail.data.db.OutboxState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendCancelPolicyTest {

    @Test
    fun `worker aborts unless the row still shows its SENDING claim`() {
        assertTrue(SendCancelPolicy.shouldAbortBeforeSend(OutboxState.CANCELLED.name))
        assertTrue(SendCancelPolicy.shouldAbortBeforeSend(OutboxState.QUEUED.name))
        assertTrue(SendCancelPolicy.shouldAbortBeforeSend(OutboxState.FAILED.name))
        assertTrue(SendCancelPolicy.shouldAbortBeforeSend(null)) // row deleted by undo
        assertFalse(SendCancelPolicy.shouldAbortBeforeSend(OutboxState.SENDING.name))
    }

    @Test
    fun `undo is only permitted while the row is still queued`() {
        assertTrue(SendCancelPolicy.canUndo(OutboxState.QUEUED.name))
        assertFalse(SendCancelPolicy.canUndo(OutboxState.SENDING.name))
        assertFalse(SendCancelPolicy.canUndo(OutboxState.SENT.name))
        assertFalse(SendCancelPolicy.canUndo(null))
    }
}
