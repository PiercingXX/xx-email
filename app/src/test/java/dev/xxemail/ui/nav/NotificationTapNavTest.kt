package dev.xxemail.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTapNavTest {

    @Test
    fun `on the target mailbox pushes the exact thread`() {
        assertEquals(
            NotificationTapAction.OpenThread("a@x.com", "t1"),
            notificationTapAction(
                currentRoute = Routes.mailbox("a@x.com"),
                currentAccount = "a@x.com",
                targetAccount = "a@x.com",
                targetThreadId = "t1",
            ),
        )
    }

    @Test
    fun `elsewhere lands on the target mailbox first`() {
        assertEquals(
            NotificationTapAction.OpenMailbox("b@x.com"),
            notificationTapAction(
                currentRoute = Routes.mailbox("a@x.com"),
                currentAccount = "a@x.com",
                targetAccount = "b@x.com",
                targetThreadId = "t2",
            ),
        )
    }

    @Test
    fun `on a non-mailbox screen lands on the target mailbox first`() {
        assertEquals(
            NotificationTapAction.OpenMailbox("b@x.com"),
            notificationTapAction(
                currentRoute = Routes.SETTINGS,
                currentAccount = null,
                targetAccount = "b@x.com",
                targetThreadId = "t2",
            ),
        )
    }

    @Test
    fun `missing account or thread is a no-op`() {
        assertEquals(
            NotificationTapAction.Noop,
            notificationTapAction(
                currentRoute = Routes.mailbox("a@x.com"),
                currentAccount = "a@x.com",
                targetAccount = null,
                targetThreadId = "t1",
            ),
        )
        assertEquals(
            NotificationTapAction.Noop,
            notificationTapAction(
                currentRoute = Routes.mailbox("a@x.com"),
                currentAccount = "a@x.com",
                targetAccount = "a@x.com",
                targetThreadId = null,
            ),
        )
    }
}