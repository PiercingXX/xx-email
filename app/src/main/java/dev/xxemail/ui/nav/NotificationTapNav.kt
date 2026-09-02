package dev.xxemail.ui.nav

/**
 * Decides what the nav should do when a new-mail notification tap arrives carrying a
 * target account/thread, given the destination currently on top of the back stack.
 *
 * Pure so the decision is unit-testable without a NavController. The singleTask activity
 * can receive a fresh notification intent while the process is alive (see MainActivity),
 * so this runs on every target change — not just once at cold start.
 */
internal sealed interface NotificationTapAction {
    /** We are already on the target account's mailbox — push the exact thread. */
    data class OpenThread(val account: String, val threadId: String) : NotificationTapAction

    /** We are elsewhere — land on the target account's mailbox first. */
    data class OpenMailbox(val account: String) : NotificationTapAction

    /** No actionable target (missing account or thread), or no-op. */
    data object Noop : NotificationTapAction
}

internal fun notificationTapAction(
    currentRoute: String?,
    currentAccount: String?,
    targetAccount: String?,
    targetThreadId: String?,
): NotificationTapAction {
    if (targetAccount == null || targetThreadId == null) return NotificationTapAction.Noop
    // The destination's route is the concrete `mailbox/<account>` (the NavHost renders
    // the pattern with real arguments), so compare against that, not the {account} pattern.
    val onTargetMailbox = currentAccount != null &&
        currentRoute == Routes.mailbox(currentAccount) &&
        currentAccount == targetAccount
    return if (onTargetMailbox) {
        NotificationTapAction.OpenThread(targetAccount, targetThreadId)
    } else {
        NotificationTapAction.OpenMailbox(targetAccount)
    }
}