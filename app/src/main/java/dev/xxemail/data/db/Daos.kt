package dev.xxemail.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Upsert suspend fun upsert(account: AccountEntity)
    @Query("SELECT * FROM accounts ORDER BY email")
    fun observeAll(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts WHERE email = :email")
    suspend fun get(email: String): AccountEntity?
    @Query("UPDATE accounts SET historyId = :historyId, lastSyncAt = :syncedAt WHERE email = :email")
    suspend fun updateSyncPoint(email: String, historyId: String?, syncedAt: Long)
    @Query("DELETE FROM accounts WHERE email = :email")
    suspend fun delete(email: String)
}

@Dao
interface LabelDao {
    @Upsert suspend fun upsertAll(labels: List<LabelEntity>)
    @Query("SELECT * FROM labels WHERE accountEmail = :account ORDER BY name")
    fun observeForAccount(account: String): Flow<List<LabelEntity>>
    @Query("DELETE FROM labels WHERE accountEmail = :account")
    suspend fun deleteForAccount(account: String)
}

@Dao
interface ThreadDao {
    @Upsert suspend fun upsertAll(threads: List<ThreadEntity>)
    @Query("SELECT * FROM threads WHERE accountEmail = :account AND id = :id")
    suspend fun get(account: String, id: String): ThreadEntity?
    @Query("SELECT * FROM threads WHERE accountEmail = :account AND id = :id")
    fun observe(account: String, id: String): Flow<ThreadEntity?>

    @Query(
        """SELECT * FROM threads
           WHERE accountEmail = :account AND inInbox = 1 AND snoozedUntil IS NULL
             AND ((:includeEmpty = 1 AND categories = '') OR (:category <> '' AND categories LIKE '%' || :category || '%'))
           ORDER BY date DESC""",
    )
    fun observeInboxCategory(account: String, category: String, includeEmpty: Boolean): Flow<List<ThreadEntity>>

    @Query(
        """SELECT * FROM threads
           WHERE accountEmail = :account AND starred = 1 AND labelsCsv NOT LIKE '%TRASH%'
           ORDER BY date DESC""",
    )
    fun observeStarred(account: String): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE accountEmail = :account AND snoozedUntil IS NOT NULL ORDER BY snoozedUntil ASC")
    fun observeSnoozed(account: String): Flow<List<ThreadEntity>>

    @Query(
        """SELECT * FROM threads
           WHERE accountEmail = :account AND labelsCsv LIKE '%' || :labelId || '%'
           ORDER BY date DESC LIMIT 500""",
    )
    fun observeWithLabel(account: String, labelId: String): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE accountEmail = :account ORDER BY date DESC LIMIT 500")
    fun observeAllMail(account: String): Flow<List<ThreadEntity>>

    @Query("SELECT COUNT(*) FROM threads WHERE accountEmail = :account")
    suspend fun count(account: String): Int

    @Query("SELECT * FROM threads WHERE accountEmail = :account AND inInbox = 1 AND snoozedUntil IS NULL ORDER BY date DESC")
    suspend fun inboxAll(account: String): List<ThreadEntity>

    @Query("SELECT COUNT(*) FROM threads WHERE accountEmail = :account AND labelsCsv LIKE '%' || :labelId || '%'")
    suspend fun countWithLabel(account: String, labelId: String): Int

    @Query("SELECT * FROM threads WHERE accountEmail = :account AND labelsCsv LIKE '%' || :labelId || '%' ORDER BY date DESC LIMIT 200")
    suspend fun withLabelList(account: String, labelId: String): List<ThreadEntity>

    @Query("UPDATE threads SET snoozedUntil = :wakeAt WHERE accountEmail = :account AND id = :id")
    suspend fun setSnoozed(account: String, id: String, wakeAt: Long?)

    @Query("UPDATE threads SET inInbox = :inInbox WHERE accountEmail = :account AND id = :id")
    suspend fun setInInbox(account: String, id: String, inInbox: Boolean)

    @Query("UPDATE threads SET starred = :starred WHERE accountEmail = :account AND id = :id")
    suspend fun setStarred(account: String, id: String, starred: Boolean)

    @Query("UPDATE threads SET unreadCount = CASE WHEN :read THEN 0 ELSE MAX(unreadCount, 1) END WHERE accountEmail = :account AND id = :id")
    suspend fun applyRead(account: String, id: String, read: Boolean)

    @Query("DELETE FROM threads WHERE accountEmail = :account")
    suspend fun deleteForAccount(account: String)

    @Query("DELETE FROM threads WHERE accountEmail = :account AND id IN (:ids)")
    suspend fun deleteByIds(account: String, ids: List<String>)
}

@Dao
interface MessageDao {
    @Upsert suspend fun upsertAll(messages: List<MessageEntity>)
    @Query("SELECT * FROM messages WHERE accountEmail = :account AND threadId = :threadId ORDER BY date ASC")
    fun observeForThread(account: String, threadId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE accountEmail = :account AND threadId = :threadId ORDER BY date ASC")
    suspend fun listForThread(account: String, threadId: String): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE accountEmail = :account AND threadId = :threadId")
    suspend fun countForThread(account: String, threadId: String): Int
    @Query("DELETE FROM messages WHERE accountEmail = :account AND id = :id")
    suspend fun deleteById(account: String, id: String)
    @Query("SELECT * FROM messages WHERE accountEmail = :account AND id = :id")
    suspend fun get(account: String, id: String): MessageEntity?
    @Query("UPDATE messages SET bodyHtml = :html, bodyPlain = :plain, bodyFetched = 1 WHERE accountEmail = :account AND id = :id")
    suspend fun updateBodies(account: String, id: String, html: String?, plain: String?)
    @Query("DELETE FROM messages WHERE accountEmail = :account AND threadId IN (:threadIds)")
    suspend fun deleteByThreadIds(account: String, threadIds: List<String>)
    @Query("DELETE FROM messages WHERE accountEmail = :account")
    suspend fun deleteForAccount(account: String)

    @androidx.room.SkipQueryVerification
    @Query(
        """SELECT messages.* FROM messages
           JOIN messages_fts ON messages_fts.rowid = messages.rowid
           WHERE messages_fts MATCH :query AND messages.accountEmail = :account
           ORDER BY messages.date DESC LIMIT 200""",
    )
    suspend fun searchLocal(account: String, query: String): List<MessageEntity>
}

@Dao
interface OutboxDao {
    @androidx.room.Insert suspend fun insert(entry: OutboxEntity): Long
    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxEntity?
    @Query("SELECT * FROM outbox WHERE state = 'QUEUED' ORDER BY targetAt ASC")
    fun observeQueued(): Flow<List<OutboxEntity>>
    @Query("SELECT COUNT(*) FROM outbox WHERE accountEmail = :account AND state = 'FAILED'")
    fun observeFailedCount(account: String): Flow<Int>
    @Query("UPDATE outbox SET state = :state, error = :error WHERE id = :id")
    suspend fun setState(id: Long, state: String, error: String? = null)
    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)
    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("DELETE FROM outbox WHERE accountEmail = :account")
    suspend fun deleteForAccount(account: String)
}
