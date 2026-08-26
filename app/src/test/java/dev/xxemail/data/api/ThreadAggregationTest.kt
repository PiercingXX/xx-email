package dev.xxemail.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadAggregationTest {

    private fun msg(
        id: String,
        labels: List<String>,
        internalDate: String,
        subject: String = "s$id",
    ) = Message(
        id = id,
        threadId = "t1",
        labelIds = labels,
        snippet = "snip$id",
        internalDate = internalDate,
        payload = MessagePart(headers = listOf(Header("Subject", subject), Header("From", "Jane Doe <jane@example.com>"))),
    )

    @Test
    fun `reply with SENT-only latest stays in inbox when older message has INBOX`() {
        val thread = Thread(
            id = "t1",
            messages = listOf(
                msg("old", listOf("INBOX", "UNREAD"), "1000"),
                msg("latest", listOf("SENT"), "2000"), // reply: INBOX removed from newest only
            ),
        )
        val entity = ThreadAggregation.build("a@x.com", thread)
        assertTrue(entity.inInbox)
    }

    @Test
    fun `sent-only thread is not in inbox`() {
        val thread = Thread(
            id = "t1",
            messages = listOf(msg("m1", listOf("SENT"), "1000")),
        )
        assertFalse(ThreadAggregation.build("a@x.com", thread).inInbox)
    }

    @Test
    fun `categories come from the union of all messages`() {
        val thread = Thread(
            id = "t1",
            messages = listOf(
                msg("old", listOf("INBOX", "CATEGORY_PROMOTIONS"), "1000"),
                msg("latest", listOf("CATEGORY_UPDATES", "CATEGORY_FORUMS"), "2000"),
            ),
        )
        val entity = ThreadAggregation.build("a@x.com", thread)
        assertTrue(entity.categories.contains("CATEGORY_PROMOTIONS"))
        assertTrue(entity.categories.contains("CATEGORY_UPDATES"))
        assertTrue(entity.categories.contains("CATEGORY_FORUMS"))
    }

    @Test
    fun `starred and unread are union-based`() {
        val thread = Thread(
            id = "t1",
            messages = listOf(
                msg("old", listOf("INBOX", "STARRED", "UNREAD"), "1000"),
                msg("latest", listOf("SENT", "UNREAD"), "2000"),
            ),
        )
        val entity = ThreadAggregation.build("a@x.com", thread)
        assertTrue(entity.starred)
        assertEquals(2, entity.unreadCount)
        assertEquals(2, entity.messageCount)
    }

    @Test
    fun `aggregate fields mirror the newest message`() {
        val thread = Thread(
            id = "t1",
            snippet = "thread snippet",
            messages = listOf(
                msg("old", listOf("INBOX"), "1000", subject = "older"),
                msg("latest", listOf("INBOX"), "2000", subject = "newer"),
            ),
        )
        val entity = ThreadAggregation.build("a@x.com", thread)
        assertEquals("newer", entity.subject)
        assertEquals("Jane Doe", entity.fromName)
        assertEquals("thread snippet", entity.snippet)
        assertEquals(2000L, entity.date)
    }

    @Test
    fun `empty thread falls back to placeholder message`() {
        val entity = ThreadAggregation.build("a@x.com", Thread(id = "t1"))
        assertEquals("(no subject)", entity.subject)
        assertFalse(entity.inInbox)
        assertEquals(0L, entity.date)
    }

    @Test
    fun `snoozedUntil passes through`() {
        val entity = ThreadAggregation.build("a@x.com", Thread(id = "t1"), snoozedUntil = 42L)
        assertEquals(42L, entity.snoozedUntil)
    }
}
