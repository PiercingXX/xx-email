package dev.xxemail.sync

import dev.xxemail.data.db.OutboxKind
import dev.xxemail.data.db.OutboxState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * E3: enqueue-when-offline is extractable — compose writes a QUEUED row and
 * a payload file, and the work is planned with a CONNECTED constraint. Gmail
 * is never contacted from this path.
 */
class OutboxSendTest {

    @Test
    fun `immediate send is queued with the undo window and requires network`() {
        val now = 1_700_000_000_000L
        val plan = OutboxSend.plan(nowMs = now, undoSeconds = 10, scheduledAt = null)
        assertEquals(OutboxKind.SEND, plan.kind)
        assertEquals(OutboxState.QUEUED, plan.state)
        assertEquals(now + 10_000L, plan.targetAt)
        assertEquals(10_000L, plan.initialDelayMs)
        assertTrue(plan.requiresConnectedNetwork)
    }

    @Test
    fun `scheduled send waits until the target and still requires network`() {
        val now = 1_700_000_000_000L
        val at = now + 3_600_000L
        val plan = OutboxSend.plan(nowMs = now, undoSeconds = 10, scheduledAt = at)
        assertEquals(OutboxKind.SCHEDULED_SEND, plan.kind)
        assertEquals(at, plan.targetAt)
        assertEquals(3_600_000L, plan.initialDelayMs)
        assertTrue(plan.requiresConnectedNetwork)
    }

    @Test
    fun `enqueue when offline persists a queued payload without sending`() {
        val filesDir = createTempDirectory("xx-email-outbox").toFile()
        try {
            val now = 1_700_000_000_000L
            val plan = OutboxSend.plan(nowMs = now, undoSeconds = 5, scheduledAt = null)
            val entity = OutboxSend.entity(
                accountEmail = "me@dev.xxemail",
                threadId = "thread-1",
                subject = "hello from airplane",
                plan = plan,
                nowMs = now,
            )
            assertEquals(OutboxState.QUEUED.name, entity.state)
            assertEquals(OutboxKind.SEND.name, entity.kind)
            assertEquals("hello from airplane", entity.subject)
            assertEquals("thread-1", entity.threadId)
            assertEquals(null, entity.rfc822Base64)

            val id = 42L
            val bytes = "From: me@dev.xxemail\r\nTo: you@example.com\r\nSubject: hello from airplane\r\n\r\nbody\r\n"
                .toByteArray()
            val (path, size) = OutboxSend.writePayload(filesDir, id, bytes)
            assertEquals("outbox/42.eml", path)
            assertEquals(bytes.size.toLong(), size)
            assertArrayEquals(bytes, File(filesDir, path).readBytes())
            assertTrue(
                "payload must survive without a Gmail round-trip",
                OutboxFiles.resolve(filesDir, path)!!.isFile,
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `compose snackbar says Queued not a failed send`() {
        assertEquals("Queued", OutboxSend.queuedLabel(scheduled = false))
        assertEquals("Send scheduled", OutboxSend.queuedLabel(scheduled = true))
    }

    @Test
    fun `failed rows can retry or discard and queued can discard`() {
        assertTrue(OutboxSend.canRetry(OutboxState.FAILED.name))
        assertTrue(OutboxSend.canDiscard(OutboxState.FAILED.name))
        assertFalse(OutboxSend.canRetry(OutboxState.QUEUED.name))
        assertTrue(OutboxSend.canDiscard(OutboxState.QUEUED.name))
        assertFalse(OutboxSend.canRetry(OutboxState.SENDING.name))
        assertFalse(OutboxSend.canDiscard(OutboxState.SENDING.name))
        assertFalse(OutboxSend.canDiscard(OutboxState.SENT.name))
        assertTrue(OutboxSend.isOpen(OutboxState.QUEUED.name))
        assertTrue(OutboxSend.isOpen(OutboxState.FAILED.name))
        assertTrue(OutboxSend.isOpen(OutboxState.SENDING.name))
        assertFalse(OutboxSend.isOpen(OutboxState.SENT.name))
    }
}
