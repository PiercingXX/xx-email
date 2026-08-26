package dev.xxemail.data.api

import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Request bodies for Gmail `messages.send`.
 *
 * The plain media upload (`uploadType=media`) carries only RFC822 bytes and CANNOT set
 * `threadId`, so replies would start a new Gmail thread. For threaded sends we use the
 * JSON multipart upload (`uploadType=multipart`): a multipart/related body whose metadata
 * part is `{raw, threadId}` JSON and whose media part is the base64url message.
 */
object GmailUploads {

    /** Static boundary — bodies are built per request and never user-controlled. */
    const val BOUNDARY = "xxemail-send-boundary"

    private val RFC822 = "message/rfc822".toMediaType()
    private const val CRLF = "\r\n"

    /** Plain media upload body (fresh sends). */
    fun mediaBody(rfc822: ByteArray): RequestBody = rfc822.toRequestBody(RFC822)

    /** Metadata JSON part. threadId is a Gmail opaque id ([A-Za-z0-9_-]) — safe to inline. */
    fun threadMetadataJson(threadId: String): String = "{\"threadId\":\"$threadId\"}"

    /** Multipart/related upload body (threaded sends): metadata part + RFC822 part. */
    fun multipartBody(rfc822Base64Url: String, threadId: String): RequestBody {
        val body = buildString {
            append("--").append(BOUNDARY).append(CRLF)
            append("Content-Type: application/json; charset=UTF-8").append(CRLF)
            append(CRLF)
            append(threadMetadataJson(threadId)).append(CRLF)
            append("--").append(BOUNDARY).append(CRLF)
            append("Content-Type: message/rfc822").append(CRLF)
            append("Content-Transfer-Encoding: base64").append(CRLF)
            append(CRLF)
            append(rfc822Base64Url).append(CRLF)
            append("--").append(BOUNDARY).append("--")
        }
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
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rfc822)
        sendMultipart(GmailUploads.multipartBody(b64, threadId))
    }
