package dev.xxemail.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val emailAddress: String,
    // Gmail historyId is an unsigned 64-bit int — modeled as String to avoid Long overflow.
    @SerialName("historyId") val historyId: String? = null,
    val messagesTotal: Int? = null,
    val threadsTotal: Int? = null,
)

@Serializable
data class Label(
    val id: String,
    val name: String,
    val type: String? = null, // "system" | "user"
    val color: LabelColor? = null,
)

@Serializable
data class LabelColor(
    val background: String? = null,
    val text: String? = null,
)

@Serializable
data class LabelListResponse(val labels: List<Label> = emptyList())

@Serializable
data class MessageRef(val id: String, val threadId: String? = null)

@Serializable
data class ListResponse(
    val messages: List<MessageRef> = emptyList(),
    val nextPageToken: String? = null,
    val resultSizeEstimate: Long? = null,
)

@Serializable
data class ThreadRef(val id: String, val snippet: String? = null, val historyId: String? = null)

@Serializable
data class ThreadListResponse(
    val threads: List<ThreadRef> = emptyList(),
    val nextPageToken: String? = null,
    val resultSizeEstimate: Long? = null,
)

@Serializable
data class Header(val name: String, val value: String)

@Serializable
data class MessagePartBody(val size: Int = 0, val data: String? = null, val attachmentId: String? = null)

@Serializable
data class MessagePart(
    val partId: String? = null,
    val mimeType: String? = null,
    val filename: String? = null,
    val headers: List<Header> = emptyList(),
    val body: MessagePartBody? = null,
    val parts: List<MessagePart> = emptyList(),
)

@Serializable
data class Message(
    val id: String,
    val threadId: String? = null,
    val labelIds: List<String> = emptyList(),
    val snippet: String? = null,
    val historyId: String? = null,
    val internalDate: String? = null, // ms since epoch as string
    val payload: MessagePart? = null,
    val sizeEstimate: Int? = null,
)

@Serializable
data class Thread(
    val id: String,
    val snippet: String? = null,
    val historyId: String? = null,
    val messages: List<Message> = emptyList(),
)

@Serializable
data class ModifyLabelsRequest(
    val addLabelIds: List<String>? = null,
    val removeLabelIds: List<String>? = null,
)

@Serializable
data class SendRawRequest(val raw: String, val threadId: String? = null)

@Serializable
data class Attachment(
    val attachmentId: String? = null,
    val filename: String? = null,
    val size: Int = 0,
    val data: String? = null,
)

// --- History (delta sync) ---

@Serializable
data class HistoryMessageAdded(val message: MessageRef? = null)

@Serializable
data class HistoryLabelChange(
    val labelIds: List<String> = emptyList(),
    val message: MessageRef? = null,
)

@Serializable
data class HistoryItem(
    // Unsigned 64-bit history sequence number — kept as String, never arithmetic.
    val id: String,
    val messages: List<HistoryMessageAdded> = emptyList(),
    val messagesAdded: List<HistoryMessageAdded> = emptyList(),
    val messagesDeleted: List<HistoryMessageAdded> = emptyList(),
    val labelsAdded: List<HistoryLabelChange> = emptyList(),
    val labelsRemoved: List<HistoryLabelChange> = emptyList(),
)

@Serializable
data class HistoryResponse(
    val history: List<HistoryItem> = emptyList(),
    val historyId: String? = null,
    val nextPageToken: String? = null,
)

// --- Settings ---

@Serializable
data class VacationSettings(
    val enableAutoReply: Boolean = false,
    val endTime: Long? = null,
    val startTime: Long? = null,
    val restrictToContacts: Boolean = false,
    val responseSubject: String? = null,
    val responseBodyHtml: String? = null,
)

@Serializable
data class SendAs(
    val sendAsEmail: String,
    val displayName: String? = null,
    val replyToAddress: String? = null,
    val signature: String? = null,
    val isDefault: Boolean = false,
    val isPrimary: Boolean = false,
)

@Serializable
data class SendAsListResponse(val sendAs: List<SendAs> = emptyList())
