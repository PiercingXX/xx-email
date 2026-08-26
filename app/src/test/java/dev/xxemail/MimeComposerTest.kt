package dev.xxemail

import dev.xxemail.data.api.MimeComposer
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MimeComposerTest {

    @Test
    fun `composes simple message with headers`() {
        val raw = MimeComposer.compose(
            from = "me@example.com",
            to = listOf("alice@example.com", "bob@example.com"),
            subject = "Hello world",
            bodyText = "Hi there",
        )
        val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(decoded.contains("From: me@example.com"))
        assertTrue(decoded.contains("alice@example.com"))
        assertTrue(decoded.contains("bob@example.com"))
        assertTrue(decoded.contains("Subject: Hello world"))
        assertTrue(decoded.contains("Hi there"))
    }

    @Test
    fun `encodes non-ascii subject`() {
        val raw = MimeComposer.compose(
            from = "me@example.com",
            to = listOf("x@example.com"),
            subject = "Grüße aus München",
            bodyText = "body",
        )
        val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        // Encoded-word form (B- or Q-encoding) must be used for non-ASCII subjects.
        assertTrue(decoded.contains("Subject: =?UTF-8?"))
    }

    @Test
    fun `threading headers are preserved`() {
        val raw = MimeComposer.compose(
            from = "me@example.com",
            to = listOf("x@example.com"),
            subject = "Re: thread",
            bodyText = "reply",
            inReplyToMessageId = "<abc@example.com>",
        )
        val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(decoded.contains("In-Reply-To: <abc@example.com>"))
        assertTrue(decoded.contains("References: <abc@example.com>"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects message without recipients`() {
        MimeComposer.compose(from = "me@example.com", to = emptyList(), subject = "s", bodyText = "b")
    }

    @Test
    fun `rejects oversized attachment totals`() {
        val big = File.createTempFile("big", ".bin")
        big.deleteOnExit()
        // Write just over the limit without allocating it fully in memory.
        java.io.RandomAccessFile(big, "rw").use { it.setLength(MimeComposer.MAX_TOTAL_ATTACHMENT_BYTES + 1) }
        try {
            MimeComposer.compose(
                from = "me@example.com",
                to = listOf("x@example.com"),
                subject = "too big",
                bodyText = "b",
                attachments = listOf(MimeComposer.Attachment(big, "application/octet-stream")),
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // pass
        }
    }

    @Test
    fun `attaches files as multipart`() {
        val tmp = File.createTempFile("report", ".txt")
        tmp.writeText("attachment payload")
        tmp.deleteOnExit()
        val raw = MimeComposer.compose(
            from = "me@example.com",
            to = listOf("x@example.com"),
            subject = "with file",
            bodyText = "see attached",
            attachments = listOf(MimeComposer.Attachment(tmp, "text/plain")),
        )
        val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(decoded.contains("multipart/mixed"))
        assertTrue(decoded.contains("report"))
        assertTrue(decoded.contains("attachment payload"))
    }
}
