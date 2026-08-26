package dev.xxemail.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Request bodies for Gmail `messages.send`.
 *
 * The plain media upload (`uploadType=media`) carries only RFC822 bytes and CANNOT set
 * `threadId`, so replies would start a new Gmail thread. For threaded sends we use the
 * JSON multipart upload (`uploadType=multipart`): a multipart/related body whose metadata
 * part is `{threadId}` JSON and whose media part is the raw RFC822 octets.
 */
object GmailUploads {

    /** Static boundary — bodies are built per request and never user-controlled. */
    const val BOUNDARY = "xxemail-send-boundary"

    private val RFC822 = "message/rfc822".toMediaType()
    private const val CRLF = "\r\n"

    /** Plain media upload body (fresh sends). */
    fun mediaBody(rfc822: ByteArray): RequestBody = rfc822.toRequestBody(RFC822)

    /** Metadata JSON part. threadId is escaped so a quote cannot break the object. */
    fun threadMetadataJson(threadId: String): String {
        val escaped = threadId.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"threadId\":\"$escaped\"}"
    }

    /**
     * Multipart/related upload body (threaded sends): JSON metadata + raw RFC822 octets.
     * The media part is the same bytes [mediaBody] would send — Gmail parses it as
     * message/rfc822, not as base64. Encoding here produced unreadable mail.
     */
    fun multipartBody(rfc822: ByteArray, threadId: String): RequestBody {
        val header = buildString {
            append("--").append(BOUNDARY).append(CRLF)
            append("Content-Type: application/json; charset=UTF-8").append(CRLF)
            append(CRLF)
            append(threadMetadataJson(threadId)).append(CRLF)
            append("--").append(BOUNDARY).append(CRLF)
            append("Content-Type: message/rfc822").append(CRLF)
            append(CRLF)
        }.toByteArray(Charsets.US_ASCII)
        val footer = "\r\n--$BOUNDARY--".toByteArray(Charsets.US_ASCII)
        val body = ByteArray(header.size + rfc822.size + footer.size)
        header.copyInto(body, 0)
        rfc822.copyInto(body, header.size)
        footer.copyInto(body, header.size + rfc822.size)
        return body.toRequestBody("multipart/related; boundary=\"$BOUNDARY\"".toMediaType())
    }
}

/**
 * Chooses the messages.send transport for an outbox payload:
 * plain media upload when there is no threadId, JSON multipart (which accepts threadId)
 * for replies into an existing Gmail thread.
 */
suspend fun GmailApi.send(rfc822: ByteArray, threadId: String?): dev.xxemail.data.api.Message =
    if (threadId.isNullOrBlank()) {
        sendRaw(GmailUploads.mediaBody(rfc822))
    } else {
        sendMultipart(GmailUploads.multipartBody(rfc822, threadId))
    }
