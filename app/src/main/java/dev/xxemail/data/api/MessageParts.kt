package dev.xxemail.data.api

/**
 * Pure recursive walkers over Gmail message payloads (nested multiparts included).
 * JVM-testable; no Android deps.
 */
object MessageParts {

    data class Found(
        val messageId: String,
        val attachmentId: String,
        val filename: String,
        val mimeType: String,
        val size: Int,
    )

    fun hasAttachment(m: Message): Boolean = m.payload?.let(::hasAttachmentIn) == true

    fun hasAttachmentIn(part: MessagePart): Boolean =
        !part.filename.isNullOrBlank() || part.parts.any { hasAttachmentIn(it) }

    fun findBody(root: MessagePart?, mime: String): MessagePart? {
        fun walk(part: MessagePart): MessagePart? {
            if (part.mimeType == mime && part.body?.data != null) return part
            part.parts.forEach { walk(it)?.let { hit -> return hit } }
            return null
        }
        return root?.let(::walk)
    }

    /** Every part with a filename + attachment id, at any nesting depth. */
    fun attachments(messageId: String, root: MessagePart?): List<Found> {
        val out = mutableListOf<Found>()
        fun walk(part: MessagePart) {
            val body = part.body
            if (!part.filename.isNullOrBlank() && body?.attachmentId != null) {
                out += Found(messageId, body.attachmentId!!, part.filename!!, part.mimeType.orEmpty(), body.size)
            }
            part.parts.forEach(::walk)
        }
        root?.let(::walk)
        return out
    }
}
