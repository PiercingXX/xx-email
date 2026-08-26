package dev.xxemail

import dev.xxemail.data.api.MimeComposer
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `references chain is prior chain plus parent message id`() {
        val raw = MimeComposer.compose(
            from = "me@example.com",
            to = listOf("x@example.com"),
            subject = "Re: deep thread",
            bodyText = "reply",
            inReplyToMessageId = "<c@example.com>",
            referencesHeader = "<a@example.com> <b@example.com>",
        )
        val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(decoded.contains("In-Reply-To: <c@example.com>"))
        assertTrue(decoded.contains("References: <a@example.com> <b@example.com> <c@example.com>"))
    }

    @Test
    fun `buildReferences combines dedups and tolerates missing parts`() {
        assertEquals("<a@x> <b@x>", MimeComposer.buildReferences("<a@x>", "<b@x>"))
        assertEquals("<b@x>", MimeComposer.buildReferences(null, "<b@x>"))
        assertEquals("<a@x>", MimeComposer.buildReferences("<a@x>", null))
        assertNull(MimeComposer.buildReferences(null, null))
        // Parent already present in a folded prior chain must not be repeated.
        assertEquals("<a@x> <b@x>", MimeComposer.buildReferences("<a@x>\n <b@x>", "<b@x>"))
    }

    @Test
    fun `attachment content type is applied explicitly not guessed`() {
        val tmp = File.createTempFile("blob-without-extension", ".bin")
        tmp.writeBytes(byteArrayOf(1, 2, 3))
        tmp.deleteOnExit()
        val raw = MimeComposer.compose(
            from = "me@example.com",
            to = listOf("x@example.com"),
            subject = "typed attachment",
            bodyText = "b",
            attachments = listOf(MimeComposer.Attachment(tmp, "image/png")),
        )
        val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(decoded.contains("Content-Type: image/png"))
        assertTrue(!decoded.substringBefore("\r\n\r\n").contains("application/octet-stream"))
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
