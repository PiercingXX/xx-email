package dev.xxemail.data.repo

import dev.xxemail.data.auth.TokenStore
import dev.xxemail.data.db.AccountDao
import dev.xxemail.data.db.AccountEntity
import dev.xxemail.data.db.LabelDao
import dev.xxemail.data.db.MessageDao
import dev.xxemail.data.db.OutboxDao
import dev.xxemail.data.db.ThreadDao
import kotlinx.coroutines.flow.Flow

class AccountRepository(
    private val accountDao: AccountDao,
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val labelDao: LabelDao,
    private val outboxDao: OutboxDao,
    private val tokens: TokenStore,
) {
    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    suspend fun register(email: String) {
        accountDao.upsert(AccountEntity(email = email, displayName = email))
    }

    /** Removes the account locally: token, mail cache, queued jobs. Server data untouched. */
    suspend fun remove(email: String) {
        tokens.remove(email)
        outboxDao.deleteForAccount(email)
        messageDao.deleteForAccount(email)
        threadDao.deleteForAccount(email)
        labelDao.deleteForAccount(email)
        accountDao.delete(email)
    }
}
