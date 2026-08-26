package dev.xxemail.di

import android.content.Context
import androidx.work.WorkManager
import dev.xxemail.data.api.GmailApi
import dev.xxemail.data.api.GmailApiFactory
import dev.xxemail.data.auth.AuthRepository
import dev.xxemail.data.auth.TokenStore
import dev.xxemail.data.db.XxEmailDb
import dev.xxemail.data.repo.AccountRepository
import dev.xxemail.data.repo.MailRepository
import dev.xxemail.data.repo.SettingsRepository

/**
 * Manual dependency graph. Deliberately framework-free: fewer moving parts,
 * easier to audit for a privacy-focused client.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val settings: SettingsRepository = SettingsRepository(appContext)
    val db: XxEmailDb = XxEmailDb.build(appContext)
    val tokens: TokenStore = TokenStore(appContext.filesDir)
    val auth: AuthRepository = AuthRepository(appContext, tokens, settings)

    val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    val accounts: AccountRepository = AccountRepository(
        accountDao = db.accountDao(),
        threadDao = db.threadDao(),
        messageDao = db.messageDao(),
        labelDao = db.labelDao(),
        outboxDao = db.outboxDao(),
        wakeDao = db.snoozeWakeDao(),
        folderPageDao = db.folderPageDao(),
        tokens = tokens,
        apiProvider = ::gmailApi,
        workManager = workManager,
        filesDir = appContext.filesDir,
    )

    private val apiCache = HashMap<String, GmailApi>()
    private val mailRepoCache = HashMap<String, MailRepository>()

    @Synchronized
    fun gmailApi(accountEmail: String): GmailApi =
        apiCache.getOrPut(accountEmail) { GmailApiFactory.create(accountEmail, auth) }

    @Synchronized
    fun mailRepository(accountEmail: String): MailRepository =
        mailRepoCache.getOrPut(accountEmail) {
            MailRepository(
                accountEmail = accountEmail,
                api = gmailApi(accountEmail),
                accountDao = db.accountDao(),
                labelDao = db.labelDao(),
                threadDao = db.threadDao(),
                messageDao = db.messageDao(),
                outboxDao = db.outboxDao(),
                wakeDao = db.snoozeWakeDao(),
                folderPageDao = db.folderPageDao(),
                settings = settings,
                workManager = workManager,
                appContext = appContext,
            )
        }
}
