package dev.xxemail.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavType
import androidx.navigation.navArgument

/** Launches the OAuth consent flow; delivers Result<signed-in email>. */
typealias AuthLauncher = ((Result<String>) -> Unit) -> Unit

val LocalAuthLauncher = staticCompositionLocalOf<AuthLauncher> {
    error("AuthLauncher not provided")
}

object Routes {
    const val SETUP = "setup"
    const val MAILBOX = "mailbox/{account}"
    const val THREAD = "thread/{account}/{threadId}"
    const val SEARCH = "search/{account}"
    const val SETTINGS = "settings"
    const val COMPOSE = "compose/{account}?to={to}&cc={cc}&subject={subject}&threadId={threadId}&quoteMessageId={quoteMessageId}&mode={mode}"

    fun mailbox(account: String) = "mailbox/$account"
    fun thread(account: String, threadId: String) = "thread/$account/$threadId"
    fun search(account: String) = "search/$account"
    fun compose(
        account: String,
        to: String = "",
        cc: String = "",
        subject: String = "",
        threadId: String = "",
        quoteMessageId: String = "",
        mode: String = "", // reply | reply_all | forward | ""
    ): String = "compose/$account?to=${android.net.Uri.encode(to)}&cc=${android.net.Uri.encode(cc)}" +
        "&subject=${android.net.Uri.encode(subject)}&threadId=$threadId&quoteMessageId=$quoteMessageId&mode=$mode"

    val mailboxArgs = listOf(navArgument("account") { type = NavType.StringType })
    val threadArgs = listOf(
        navArgument("account") { type = NavType.StringType },
        navArgument("threadId") { type = NavType.StringType },
    )
    val composeArgs = listOf(
        navArgument("account") { type = NavType.StringType },
        navArgument("to") { type = NavType.StringType; defaultValue = "" },
        navArgument("cc") { type = NavType.StringType; defaultValue = "" },
        navArgument("subject") { type = NavType.StringType; defaultValue = "" },
        navArgument("threadId") { type = NavType.StringType; defaultValue = "" },
        navArgument("quoteMessageId") { type = NavType.StringType; defaultValue = "" },
        navArgument("mode") { type = NavType.StringType; defaultValue = "" },
    )
}
