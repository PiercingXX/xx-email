package dev.xxemail.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun XxNavHost() {
    val navController = rememberNavController()
    val graph = LocalContext.current.appGraph
    val accounts by graph.accounts.observeAccounts().collectAsStateWithLifecycle(initialValue = null)

    val startDestination = when {
        accounts == null -> Routes.SETUP // not loaded yet; SetupScreen self-corrects
        accounts!!.isEmpty() -> Routes.SETUP
        else -> Routes.mailbox(accounts!!.first().email)
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
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
