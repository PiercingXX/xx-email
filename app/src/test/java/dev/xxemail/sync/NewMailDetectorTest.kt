package dev.xxemail.sync

import dev.xxemail.data.db.ThreadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMailDetectorTest {

    private fun unreadInbox(id: String) = ThreadEntity(accountEmail = "a@x.com", id = id, unreadCount = 1)

    @Test
    fun `first sync of an account notifies nothing`() {
        // null snapshot ⇒ checkpoint-less / forced pass ⇒ everything would look new.
        val current = listOf(unreadInbox("1"), unreadInbox("2"), unreadInbox("3"))
        assertTrue(NewMailDetector.newArrivals(null, current).isEmpty())
    }

    @Test
    fun `old unread threads are not re-notified`() {
        val known = setOf("1", "2")
        val current = listOf(unreadInbox("1"), unreadInbox("2"))
        assertTrue(NewMailDetector.newArrivals(known, current).isEmpty())
    }

    @Test
    fun `newly arrived unread threads are detected`() {
        val known = setOf("1", "2")
        val current = listOf(unreadInbox("1"), unreadInbox("9"))
        val arrivals = NewMailDetector.newArrivals(known, current)
        assertEquals(listOf("9"), arrivals.map { it.id })
    }

    @Test
    fun `empty prior inbox means every unread thread is new`() {
        val arrivals = NewMailDetector.newArrivals(emptySet(), listOf(unreadInbox("1")))
        assertEquals(listOf("1"), arrivals.map { it.id })
    }

    @Test
    fun `burst of many arrivals is fully reported`() {
        val known = (1L..60L).map { it.toString() }.toSet()
        val current = (61L..130L).map { unreadInbox(it.toString()) }
        assertEquals(70, NewMailDetector.newArrivals(known, current).size)
    }
}
