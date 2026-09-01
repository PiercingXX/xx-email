package dev.xxemail.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.xxemail.appGraph
import dev.xxemail.ui.compose.ComposeScreen
import dev.xxemail.ui.mailbox.MailboxScreen
import dev.xxemail.ui.search.SearchScreen
import dev.xxemail.ui.settings.SettingsScreen
import dev.xxemail.ui.setup.SetupScreen
import dev.xxemail.ui.thread.ThreadScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun XxNavHost(notificationAccount: String? = null, notificationThreadId: String? = null) {
    val navController = rememberNavController()
    val graph = LocalContext.current.appGraph
    val accounts by graph.accounts.observeAccounts().collectAsStateWithLifecycle(initialValue = null)
    val lastUsedAccount by graph.settings.lastAccountFlow.collectAsStateWithLifecycle(initialValue = null)

    // F1: never start at Setup while the account list is unknown — hold a minimal loading
    // frame until Room emits, then land in Setup OR the mailbox the user actually left.
    if (accounts == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Notification taps land on the target account's mailbox (never a bare start screen);
    // the exact thread is pushed on top once the mailbox back stack exists.
    val notificationTargetAccount = notificationAccount
        ?.takeIf { target -> accounts?.any { it.email == target } == true }
    val startDestination = when {
        accounts!!.isEmpty() -> Routes.SETUP
        notificationTargetAccount != null -> Routes.mailbox(notificationTargetAccount)
        // Last-used account when it still exists; `accounts.first()` is not "the account I was using".
        accounts!!.any { it.email == lastUsedAccount } -> Routes.mailbox(lastUsedAccount!!)
        else -> Routes.mailbox(accounts!!.first().email)
    }

    // A notification tap is honoured on cold start AND on a warm tap (process alive,
    // singleTask): the target is keyed so a fresh intent re-runs this effect. The
    // handledTarget guard stops us re-pushing the same thread on unrelated recompositions.
    var handledTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val currentBackStack = navController.currentBackStackEntry
    LaunchedEffect(accounts, notificationTargetAccount, notificationThreadId, currentBackStack?.destination) {
        val targetAccount = notificationTargetAccount ?: return@LaunchedEffect
        val targetThreadId = notificationThreadId ?: return@LaunchedEffect
        val target = targetAccount to targetThreadId
        if (handledTarget == target) return@LaunchedEffect
        val current = navController.currentBackStackEntry
        when (val action = notificationTapAction(
            currentRoute = current?.destination?.route,
            currentAccount = current?.arguments?.getString("account"),
            targetAccount = targetAccount,
            targetThreadId = targetThreadId,
        )) {
            is NotificationTapAction.OpenThread -> {
                handledTarget = target
                navController.navigate(Routes.thread(action.account, action.threadId))
            }
            // Warm tap for a different screen/account: clear the stale stack and land on
            // the target mailbox; the effect re-runs once that destination is current and
            // then pushes the thread.
            is NotificationTapAction.OpenMailbox -> {
                navController.navigate(Routes.mailbox(action.account)) {
                    popUpTo(Routes.MAILBOX) { inclusive = true }
                }
            }
            NotificationTapAction.Noop -> Unit
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SETUP) {
            SetupScreen(
                onAccountReady = { email ->
                    navController.navigate(Routes.mailbox(email)) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MAILBOX, arguments = Routes.mailboxArgs) { entry ->
            val account = entry.arguments?.getString("account").orEmpty()
            MailboxScreen(
                account = account,
                onOpenThread = { navController.navigate(Routes.thread(account, it)) },
                onCompose = { navController.navigate(Routes.compose(account)) },
                onSearch = { navController.navigate(Routes.search(account)) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchAccount = { target ->
                    navController.navigate(Routes.mailbox(target)) {
                        popUpTo(Routes.MAILBOX) { inclusive = true }
                    }
                },
                onAddAccount = { navController.navigate(Routes.SETUP) },
                onSignInAgain = { navController.navigate(Routes.SETUP) },
            )
        }
        composable(Routes.THREAD, arguments = Routes.threadArgs) { entry ->
            val account = entry.arguments?.getString("account").orEmpty()
            val threadId = entry.arguments?.getString("threadId").orEmpty()
            ThreadScreen(
                account = account,
                threadId = threadId,
                onBack = { navController.popBackStack() },
                onReply = { mode, subject, messageId ->
                    navController.navigate(Routes.compose(account, subject = subject, threadId = threadId, quoteMessageId = messageId, mode = mode))
                },
            )
        }
        composable(Routes.COMPOSE, arguments = Routes.composeArgs) { entry ->
            ComposeScreen(
                account = entry.arguments?.getString("account").orEmpty(),
                initialTo = entry.arguments?.getString("to").orEmpty(),
                initialCc = entry.arguments?.getString("cc").orEmpty(),
                initialSubject = entry.arguments?.getString("subject").orEmpty(),
                threadId = entry.arguments?.getString("threadId").orEmpty(),
                quoteMessageId = entry.arguments?.getString("quoteMessageId").orEmpty(),
                mode = entry.arguments?.getString("mode").orEmpty(),
                onDone = { navController.popBackStack() },
            )
        }
        composable(Routes.SEARCH, arguments = Routes.mailboxArgs) { entry ->
            val account = entry.arguments?.getString("account").orEmpty()
            SearchScreen(
                account = account,
                onBack = { navController.popBackStack() },
                onOpenThread = { navController.navigate(Routes.thread(account, it)) },
            )
        }
        composable(Routes.SETTINGS) {
            // F2: if the account whose mailbox sits below us on the back stack was just
            // removed, leave Settings and land in another mailbox (or Setup). Runs only
            // after the post-removal dialog is dismissed (onAccountRemoved fires on Done).
            val scope = rememberCoroutineScope()
            val removedMailboxAccount = remember {
                navController.previousBackStackEntry
                    ?.takeIf { it.destination.route == Routes.MAILBOX }
                    ?.arguments?.getString("account")
            }
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAccountRemoved = { removed ->
                    scope.launch {
                        // Keep the last-used pointer from resurrecting a deleted account.
                        if (graph.settings.lastAccount() == removed) graph.settings.setLastAccount(null)
                        val staleMailbox = removedMailboxAccount?.takeIf { it == removed } ?: return@launch
                        val remaining = graph.accounts.observeAccounts().first()
                        if (remaining.none { it.email == staleMailbox }) {
                            val target = if (remaining.isEmpty()) Routes.SETUP else Routes.mailbox(remaining.first().email)
                            // Pops Settings AND the now-dead mailbox entry in one operation.
                            navController.navigate(target) {
                                popUpTo(Routes.MAILBOX) { inclusive = true }
                            }
                        }
                    }
                },
            )
        }
    }
}
