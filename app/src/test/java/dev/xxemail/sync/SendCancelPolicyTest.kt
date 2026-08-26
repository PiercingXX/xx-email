package dev.xxemail.sync

import dev.xxemail.data.db.OutboxState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendCancelPolicyTest {

    @Test
    fun `undo is only permitted while the row is still queued`() {
        assertTrue(SendCancelPolicy.canUndo(OutboxState.QUEUED.name))
        assertFalse(SendCancelPolicy.canUndo(OutboxState.SENDING.name))
        assertFalse(SendCancelPolicy.canUndo(OutboxState.SENT.name))
        assertFalse(SendCancelPolicy.canUndo(null))
    }
}
