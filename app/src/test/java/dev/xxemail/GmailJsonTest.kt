package dev.xxemail

import dev.xxemail.data.api.HistoryResponse
import dev.xxemail.data.api.Message
import dev.xxemail.data.api.Profile
import dev.xxemail.data.api.Thread
import dev.xxemail.data.api.ThreadListResponse
import dev.xxemail.data.api.ThreadRef
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden JSON samples shaped like real Gmail REST responses. Gmail returns uint64 fields
 * (historyId) as decimal STRINGS that can exceed Long.MAX_VALUE — deserialization must
 * preserve them verbatim as Kotlin Strings.
 */
class GmailJsonTest {

    // Same configuration as GmailApiFactory's converter.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `getProfile decodes quoted uint64 historyId as string`() {
        val body = """
            {
              "emailAddress": "piercingxx@example.com",
              "messagesTotal": 10934,
              "threadsTotal": 1822,
              "historyId": "9223372036854775808",
              "labelTotals": { "unread": 42 }
            }
        """.trimIndent()
        val profile = json.decodeFromString<Profile>(body)
        assertEquals("piercingxx@example.com", profile.emailAddress)
        assertEquals("9223372036854775808", profile.historyId)
        assertEquals(10934, profile.messagesTotal)
        assertEquals(1822, profile.threadsTotal)
    }

    @Test
    fun `threads list decodes ThreadRef historyId as string`() {
        val body = """
            {
              "threads": [
                { "id": "17fadf0d3b0a1c2b", "snippet": "Hello there", "historyId": "9223372036854775999" },
                { "id": "17fadf0e00000001", "snippet": "Second", "historyId": "9223372036854776000" }
              ],
              "nextPageToken": "page-2",
              "resultSizeEstimate": 2
            }
        """.trimIndent()
        val page = json.decodeFromString<ThreadListResponse>(body)
        assertEquals(2, page.threads.size)
        assertEquals("9223372036854775999", page.threads[0].historyId)
        assertEquals("9223372036854776000", page.threads[1].historyId)
        assertEquals("page-2", page.nextPageToken)
    }

    @Test
    fun `threads get decodes thread and message historyIds as strings`() {
        val body = """
            {
              "id": "17fadf0d3b0a1c2b",
              "snippet": "Lunch tomorrow?",
              "historyId": "9223372036854780000",
              "messages": [
                {
                  "id": "17fadf0d3b0a1c2b.1",
                  "threadId": "17fadf0d3b0a1c2b",
                  "labelIds": ["INBOX", "UNREAD", "CATEGORY_PERSONAL"],
                  "snippet": "Lunch tomorrow?",
                  "historyId": "9223372036854779000",
                  "internalDate": "1771891200000",
                  "payload": {
                    "mimeType": "multipart/alternative",
                    "headers": [
                      { "name": "From", "value": "Jane Doe <jane@example.com>" },
                      { "name": "Subject", "value": "Lunch tomorrow?" }
                    ]
                  },
                  "sizeEstimate": 2048
                }
              ]
            }
        """.trimIndent()
        val thread = json.decodeFromString<Thread>(body)
        assertEquals("9223372036854780000", thread.historyId)
        val message: Message = thread.messages.single()
        assertEquals("9223372036854779000", message.historyId)
        assertEquals("1771891200000", message.internalDate)
        assertEquals(listOf("INBOX", "UNREAD", "CATEGORY_PERSONAL"), message.labelIds)
    }

    @Test
    fun `history list decodes item ids and historyId as strings`() {
        val body = """
            {
              "history": [
                {
                  "id": "9223372036854776001",
                  "messages": [{ "id": "msg-a", "threadId": "thread-a" }],
                  "messagesAdded": [
                    { "message": { "id": "msg-b", "threadId": "thread-b", "labelIds": ["INBOX"] } }
                  ]
                },
                {
                  "id": "9223372036854776002",
                  "labelsRemoved": [
                    { "labelIds": ["UNREAD"], "message": { "id": "msg-a", "threadId": "thread-a" } }
                  ]
                }
              ],
              "historyId": "9223372036854776100"
            }
        """.trimIndent()
        val response = json.decodeFromString<HistoryResponse>(body)
        assertEquals("9223372036854776001", response.history[0].id)
        assertEquals("9223372036854776002", response.history[1].id)
        assertEquals("9223372036854776100", response.historyId)
        assertEquals("msg-b", response.history[0].messagesAdded.single().message?.id)
    }

    @Test
    fun `messages send decodes response with quoted historyId`() {
        val body = """
            {
              "id": "17fadf10000000aa",
              "threadId": "17fadf0d3b0a1c2b",
              "labelIds": ["SENT"],
              "historyId": "9223372036854781111"
            }
        """.trimIndent()
        val sent = json.decodeFromString<Message>(body)
        assertEquals("17fadf10000000aa", sent.id)
        assertEquals("17fadf0d3b0a1c2b", sent.threadId)
        assertEquals(listOf("SENT"), sent.labelIds)
        assertEquals("9223372036854781111", sent.historyId)
    }

    @Test
    fun `uint64 values beyond Long range survive as exact strings`() {
        // 2^63 is one past Long.MAX_VALUE; 2^64-1 is the uint64 maximum.
        for (raw in listOf("9223372036854775808", "18446744073709551615")) {
            val body = """{"id":"t","historyId":"$raw"}"""
            assertEquals(raw, json.decodeFromString<ThreadRef>(body).historyId)
        }
    }

    @Test
    fun `numeric historyId is coerced to string`() {
        val body = """{"id":"t","historyId":123456}"""
        assertEquals("123456", json.decodeFromString<ThreadRef>(body).historyId)
    }
}
