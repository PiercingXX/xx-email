package dev.xxemail.data.repo

import android.util.Log
import dev.xxemail.data.api.GmailApi
import dev.xxemail.data.api.ModifyLabelsRequest
import dev.xxemail.data.auth.TokenStore
import dev.xxemail.data.db.AccountDao
import dev.xxemail.data.db.AccountEntity
import dev.xxemail.data.db.LabelDao
import dev.xxemail.data.db.MessageDao
import dev.xxemail.data.db.OutboxDao
import dev.xxemail.data.db.SnoozeWakeDao
import dev.xxemail.data.db.ThreadDao
import dev.xxemail.sync.OutboxFiles
import dev.xxemail.sync.OutboxWorker
import dev.xxemail.sync.SnoozeWorker
import kotlinx.coroutines.flow.Flow
import java.io.File

class AccountRepository(
    private val accountDao: AccountDao,
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val labelDao: LabelDao,
    private val outboxDao: OutboxDao,
    private val wakeDao: SnoozeWakeDao,
    private val tokens: TokenStore,
    /** Live-API accessor; used for best-effort restores while tokens still exist. */
    private val apiProvider: (String) -> GmailApi,
    private val workManager: androidx.work.WorkManager,
    private val filesDir: File,
) {
    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    suspend fun register(email: String) {
        accountDao.upsert(AccountEntity(email = email, displayName = email))
    }

    data class RemoveResult(
        val restoredToInbox: Int,
        val warnings: List<String>,
    )

    /**
     * Removes the account locally: token, mail cache, payload files and queued jobs.
     * Server data untouched. Still-snoozed threads are restored to INBOX server-side
     * BEFORE tokens drop (best-effort — offline failures are reported in [RemoveResult.warnings]
     * so callers can tell the user their mail will not reappear automatically).
     */
    suspend fun remove(email: String): RemoveResult {
        val warnings = mutableListOf<String>()
        var restored = 0
        for (thread in threadDao.snoozedList(email)) {
            try {
                apiProvider(email).modifyThread(thread.id, ModifyLabelsRequest(addLabelIds = listOf("INBOX")))
                restored++
            } catch (t: Throwable) {
                Log.w(TAG, "Could not restore snoozed thread ${thread.id} of $email", t)
                warnings += "A snoozed mail could not be returned to the inbox (offline?) — " +
                    "unsnooze it from another client or remove the account while online."
            }
        }

        // Cancel scheduled device work so nothing fires against a removed account.
        outboxDao.queuedIdsForAccount(email).forEach { workManager.cancelUniqueWork(OutboxWorker.workName(it)) }
        wakeDao.listForAccount(email).forEach {
            workManager.cancelUniqueWork(SnoozeWorker.workName(email, it.threadId))
        }

        tokens.remove(email)

        // Payload files first (rows carry their paths), then all local rows.
        outboxDao.listForAccount(email).forEach { OutboxFiles.deletePayloadFile(filesDir, it.path, it.id) }
        wakeDao.deleteForAccount(email)
        outboxDao.deleteForAccount(email)
        messageDao.deleteForAccount(email)
        threadDao.deleteForAccount(email)
        labelDao.deleteForAccount(email)
        accountDao.delete(email)
        return RemoveResult(restored, warnings)
    }

    private companion object {
        const val TAG = "AccountRepository"
    }
}
