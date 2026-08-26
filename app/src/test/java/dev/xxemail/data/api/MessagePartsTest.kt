package dev.xxemail.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePartsTest {

    private fun leaf(mime: String, filename: String? = null, attachmentId: String? = null) =
        MessagePart(
            mimeType = mime,
            filename = filename,
            body = if (attachmentId != null) MessagePartBody(attachmentId = attachmentId) else MessagePartBody(data = "x"),
        )

    private fun nestedThread() = MessagePart(
        mimeType = "multipart/mixed",
        parts = listOf(
            MessagePart(
                mimeType = "multipart/alternative",
                parts = listOf(
                    leaf("text/plain"),
                    leaf("text/html"),
                ),
            ),
            leaf("application/pdf", filename = "report.pdf", attachmentId = "att-1"),
            MessagePart(
                mimeType = "multipart/mixed",
                parts = listOf(
                    // Attachment nested two levels down — the old one-level check missed these.
                    leaf("image/png", filename = "pic.png", attachmentId = "att-2"),
                ),
            ),
        ),
    )

    @Test
    fun `hasAttachment recurses nested multiparts`() {
        assertTrue(MessageParts.hasAttachmentIn(nestedThread()))
        assertTrue(MessageParts.hasAttachment(Message(id = "m", payload = nestedThread())))
    }

    @Test
    fun `plain body has no attachments`() {
        assertFalse(MessageParts.hasAttachmentIn(leaf("text/plain")))
        assertFalse(MessageParts.hasAttachment(Message(id = "m", payload = null)))
    }

    @Test
    fun `attachments collects every depth with metadata`() {
        val found = MessageParts.attachments("msg-1", nestedThread())
        assertEquals(listOf("att-1", "att-2"), found.map { it.attachmentId })
        assertEquals(listOf("report.pdf", "pic.png"), found.map { it.filename })
        assertTrue(found.all { it.messageId == "msg-1" })
        assertEquals("application/pdf", found[0].mimeType)
    }

    @Test
    fun `filename without attachment id is not downloadable`() {
        val part = MessagePart(
            mimeType = "multipart/mixed",
            parts = listOf(leaf("application/octet-stream", filename = "weird.bin")),
        )
        assertTrue(MessageParts.hasAttachmentIn(part))
        assertTrue(MessageParts.attachments("m", part).isEmpty())
    }

    @Test
    fun `findBody walks nested multiparts`() {
        assertNotNull(MessageParts.findBody(nestedThread(), "text/html"))
        assertNull(MessageParts.findBody(nestedThread(), "text/calendar"))
        assertNull(MessageParts.findBody(null, "text/html"))
    }
}
