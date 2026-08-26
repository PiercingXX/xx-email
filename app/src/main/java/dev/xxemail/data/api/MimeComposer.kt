package dev.xxemail.data.api

import jakarta.activation.DataHandler
import jakarta.activation.FileDataSource
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Date
import java.util.Properties

/**
 * Builds RFC 822 messages with Eclipse Angus Mail (the stack Thunderbird Android uses),
 * returning Gmail's base64url `raw` representation. We never use SMTP transports —
 * composition only; delivery goes through the Gmail REST API.
 */
object MimeComposer {

    data class Attachment(val file: File, val mimeType: String)

    /** Gmail hard limit is 25 MB; stay under it accounting for base64 (~33%) inflation. */
    const val MAX_TOTAL_ATTACHMENT_BYTES: Long = 18L * 1024 * 1024

    fun compose(
        from: String,
        to: List<String>,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        subject: String,
        bodyText: String,
        inReplyToMessageId: String? = null,
        referencesHeader: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): String {
        require(to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty()) { "No recipients" }

        val totalBytes = attachments.sumOf { it.file.length() }
        require(totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES) {
            "Attachments total ${(totalBytes / 1024 / 1024)} MB exceeds the ${MAX_TOTAL_ATTACHMENT_BYTES / 1024 / 1024} MB limit"
        }

        val message = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, to.map(::InternetAddress).toTypedArray())
            if (cc.isNotEmpty()) setRecipients(Message.RecipientType.CC, cc.map(::InternetAddress).toTypedArray())
            if (bcc.isNotEmpty()) setRecipients(Message.RecipientType.BCC, bcc.map(::InternetAddress).toTypedArray())
            setSubject(subject, "UTF-8")
            sentDate = Date()
            inReplyToMessageId?.let { setHeader("In-Reply-To", it) }
            val refs = referencesHeader ?: inReplyToMessageId
            refs?.let { setHeader("References", it) }
        }

        if (attachments.isEmpty()) {
            message.setText(bodyText, "UTF-8")
        } else {
            val multipart = MimeMultipart()
            multipart.addBodyPart(MimeBodyPart().apply { setText(bodyText, "UTF-8") })
            attachments.forEach { att ->
                multipart.addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(FileDataSource(att.file))
                        setFileName(att.file.name)
                    },
                )
            }
            message.setContent(multipart)
        }
        message.saveChanges()

        val bytes = ByteArrayOutputStream().also(message::writeTo).toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
