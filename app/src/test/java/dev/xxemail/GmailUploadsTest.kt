package dev.xxemail

import dev.xxemail.data.api.GmailUploads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailUploadsTest {

    @Test
    fun `media body is message rfc822 octets`() {
        val body = GmailUploads.mediaBody(byteArrayOf(1, 2, 3))
        assertEquals("message/rfc822", body.contentType()?.toString())
    }

    @Test
    fun `multipart body carries threadId metadata and rfc822 parts`() {
        val raw = "AAECAwQFBgcICQ=="
        val body = GmailUploads.multipartBody(rfc822Base64Url = raw, threadId = "thread-123")
        val sink = okio.Buffer()
        body.writeTo(sink)
        val text = sink.readUtf8()

        val ct = requireNotNull(body.contentType())
        assertEquals("multipart", ct.type)
        assertEquals("related", ct.subtype)
        assertEquals(GmailUploads.BOUNDARY, ct.parameter("boundary"))
        assertTrue(text.contains("--${GmailUploads.BOUNDARY}\r\n"))
        assertTrue(text.contains("Content-Type: application/json; charset=UTF-8"))
        assertTrue(text.contains("{\"threadId\":\"thread-123\"}"))
        assertTrue(text.contains("Content-Type: message/rfc822"))
        assertTrue(text.contains("Content-Transfer-Encoding: base64"))
        assertTrue(text.contains(raw))
        assertTrue(text.endsWith("--${GmailUploads.BOUNDARY}--"))
        // Exactly two boundary-delimited parts.
        assertEquals(3, Regex("--${Regex.escape(GmailUploads.BOUNDARY)}").findAll(text).count())
    }

    @Test
    fun `thread metadata json escapes nothing but stays valid for gmail ids`() {
        assertEquals("{\"threadId\":\"17fadf0d3b0a1c2b\"}", GmailUploads.threadMetadataJson("17fadf0d3b0a1c2b"))
    }
}
