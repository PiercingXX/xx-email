package dev.xxemail.sync

import dev.xxemail.data.db.ThreadEntity

/**
 * Pure new-mail decision for notifications.
 *
 * A pass may notify only when it resumed from an existing checkpoint — the
 * first sync of an account and forced rebuilds see the whole mailbox at once
 * and must never fire a wall of alerts. "New" means a currently-unread inbox
 * thread whose id was not in the pre-sync snapshot of known inbox thread ids.
 */
object NewMailDetector {

    /**
     * @param knownInboxIdsBefore ids of inbox threads seen before the pass;
     *   null ⇒ checkpoint-less/forced pass ⇒ never notify.
     */
    fun newArrivals(knownInboxIdsBefore: Set<String>?, currentUnreadInbox: List<ThreadEntity>): List<ThreadEntity> {
        val known = knownInboxIdsBefore ?: return emptyList()
        return currentUnreadInbox.filter { it.id !in known }
    }
}
