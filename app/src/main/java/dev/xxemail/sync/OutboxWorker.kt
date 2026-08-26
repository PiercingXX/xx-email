package dev.xxemail.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.xxemail.XxEmailApp
import dev.xxemail.data.api.send
import dev.xxemail.data.db.OutboxEntity
import dev.xxemail.data.db.OutboxState
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Sends queued RFC822 payloads. The undo window / schedule delay is expressed as the
 * WorkRequest's initialDelay, so cancelling the unique work == undo.
 *
 * Payload bytes come from `files/outbox/{id}.eml` (see [OutboxFiles]); legacy v0.1 rows
 * carrying only `rfc822Base64` are decoded, persisted to a file, and their column cleared,
 * so upgraded queues keep sending.
 */
class OutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as XxEmailApp).graph
        val id = inputData.getLong(KEY_OUTBOX_ID, -1)
        if (id <= 0) return Result.failure()
        val dao = graph.db.outboxDao()
        val entry = dao.get(id) ?: return Result.success() // cancelled/undone before we ran

        when (entry.state) {
            OutboxState.QUEUED.name -> Unit // proceed below
            // Process death mid-send leaves the row SENDING with no live worker. WorkManager
            // re-runs persisted work, so this branch is the recovery path: surface it as
            // FAILED (payload file kept) so the failed-send banner and manual retry can
            // redeliver it, instead of stranding the row in SENDING forever.
            OutboxState.SENDING.name -> {
                Log.w(TAG, "Outbox #$id found SENDING with no live send — marking interrupted")
                dao.setState(id, OutboxState.FAILED.name, "send interrupted")
                return Result.success()
            }
            else -> return Result.success() // SENT / FAILED / CANCELLED — nothing to do
        }

        // Scheduled send fired early (OS quirks): put it back.
        if (entry.kind == dev.xxemail.data.db.OutboxKind.SCHEDULED_SEND.name && System.currentTimeMillis() < entry.targetAt) {
            return Result.retry()
        }

        // Atomic claim (two-sided CAS with cancelIfQueued): if undo flipped the row to
        // CANCELLED between the read above and here, the claim returns 0 and we must not
        // send. No unconditional write can clobber a cancel anymore.
        if (dao.claimIfQueued(id) == 0) return Result.success()
        dao.incrementAttempts(id)
        val attemptsUsed = entry.attempts + 1
        return try {
            val bytes = resolvePayload(applicationContext.filesDir, dao, entry)
            val repo = graph.mailRepository(entry.accountEmail)
            // Media upload cannot carry threadId; threaded sends go through the JSON
            // multipart variant (see GmailUploads).
            graph.gmailApi(entry.accountEmail).send(bytes, entry.threadId)
            // SENT is recorded BEFORE any post-send work (archiveAfterSend): if archiving
            // throws or the process dies here, the message must never be sent again.
            markSent(dao, id, entry.path)
            if (graph.settings.sendAndArchive()) {
                runCatching { repo.archiveAfterSend(entry.threadId) }
                    .onFailure { Log.w(TAG, "Post-send archive failed for outbox #$id", it) }
            }
            Log.i(TAG, "Sent outbox #$id (${entry.subject})")
            Result.success()
        } catch (e: retrofit2.HttpException) {
            applyOutcome(dao, id, attemptsUsed, SendRetryPolicy.decide(httpCode = e.code(), transportError = false), e.message, entry.path)
        } catch (e: kotlinx.serialization.SerializationException) {
            // A non-2xx response would have surfaced as HttpException, so reaching body
            // decoding means the server answered 2xx: the send succeeded even though the
            // response could not be decoded. Never retry a message that left the device.
            applyOutcome(dao, id, attemptsUsed, SendRetryPolicy.decide(httpCode = 200, transportError = false), null, entry.path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Delivery is unknown. Do NOT flip back to QUEUED — WorkManager will rerun
            // this work when constraints return, and a second sendRaw would duplicate
            // mail if the first request already left the device. FAILED + banner retry
            // matches the SENDING-after-process-death recovery path.
            val current = dao.get(id)
            if (current?.state == OutboxState.SENDING.name) {
                dao.setState(id, OutboxState.FAILED.name, "send interrupted")
            }
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Send transport error for outbox #$id (attempt $attemptsUsed)", e)
            applyOutcome(dao, id, attemptsUsed, SendRetryPolicy.decide(httpCode = null, transportError = true), e.message, entry.path)
        } catch (e: Throwable) {
            Log.w(TAG, "Send failed for outbox #$id (attempt $attemptsUsed)", e)
            applyOutcome(dao, id, attemptsUsed, SendRetryPolicy.decide(httpCode = null, transportError = true), e.message, entry.path)
        }
    }

    /**
     * Prefers the file-backed payload; falls back to the legacy base64 column for rows
     * migrated from v0.1 (decoded bytes are then persisted to disk and the column cleared
     * to get them off CursorWindow-sized reads).
     */
    private suspend fun resolvePayload(filesDir: File, dao: dev.xxemail.data.db.OutboxDao, entry: OutboxEntity): ByteArray =
        when {
            entry.path != null ->
                OutboxFiles.resolve(filesDir, entry.path)?.readBytes()
                    ?: throw IOException("Outbox #${entry.id} payload file is missing (${entry.path})")
            entry.rfc822Base64 != null -> {
                val bytes = Base64.getUrlDecoder().decode(entry.rfc822Base64)
                runCatching {
                    val rel = OutboxFiles.writeNew(filesDir, entry.id, bytes)
                    dao.setPayload(entry.id, rel, bytes.size.toLong())
                    dao.clearLegacyPayload(entry.id)
                }.onFailure { Log.w(TAG, "Could not migrate outbox #${entry.id} payload to file", it) }
                bytes
            }
            else -> throw IOException("Outbox #${entry.id} has neither payload file nor legacy base64")
        }

    private suspend fun markSent(dao: dev.xxemail.data.db.OutboxDao, id: Long, path: String?) {
        dao.setState(id, OutboxState.SENT.name)
        OutboxFiles.deletePayloadFile(applicationContext.filesDir, path, id)
    }

    /** Applies a [SendRetryPolicy.Outcome] to the outbox row and the WorkManager result. */
    private suspend fun applyOutcome(
        dao: dev.xxemail.data.db.OutboxDao,
        id: Long,
        attemptsUsed: Int,
        outcome: SendRetryPolicy.Outcome,
        error: String?,
        path: String?,
    ): Result = when (outcome) {
        // Decode-failure-after-2xx used to return success without flipping the row,
        // so the next run treated it as interrupted and the user could resend.
        SendRetryPolicy.Outcome.MARK_SENT -> {
            markSent(dao, id, path)
            Result.success()
        }
        SendRetryPolicy.Outcome.MARK_FAILED -> {
            dao.setState(id, OutboxState.FAILED.name, error)
            Result.failure()
        }
        SendRetryPolicy.Outcome.RETRY ->
            if (attemptsUsed >= MAX_ATTEMPTS) {
                dao.setState(id, OutboxState.FAILED.name, error)
                Result.failure()
            } else {
                dao.setState(id, OutboxState.QUEUED.name)
                Result.retry()
            }
    }

    companion object {
        private const val TAG = "OutboxWorker"
        const val KEY_OUTBOX_ID = "outbox_id"
        const val MAX_ATTEMPTS = 5

        fun workName(outboxId: Long): String = "outbox-$outboxId"
    }
}

/**
 * Wakes a snoozed thread back into the inbox at its scheduled time.
 *
 * The `snooze_wakes` table is the source of truth: input data is a fast path, and when it
 * is missing the worker drains all due wake rows instead. Requires network and retries
 * until every wake succeeds — an un-woken snooze means mail silently stuck out of the inbox.
 */
class SnoozeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as XxEmailApp).graph
        val wakeDao = graph.db.snoozeWakeDao()
        val now = System.currentTimeMillis()

        val account = inputData.getString(KEY_ACCOUNT)
        val threadId = inputData.getString(KEY_THREAD_ID)
        val targets = if (account != null && threadId != null) {
            val wake = wakeDao.get(account, threadId) ?: return Result.success() // unsnoozed early
            if (wake.targetAt > now) return Result.retry() // fired early — wait via backoff
            listOf(wake)
        } else {
            // Input data lost — recover from the durable table (all accounts).
            val due = wakeDao.due(now)
            if (due.isEmpty()) return Result.success()
            due
        }

        var failed = false
        targets.forEach { wake ->
            try {
                // restoreFromSnooze (not unsnooze) — cancelling our own running work would
                // interrupt the restore mid-flight.
                graph.mailRepository(wake.accountEmail).restoreFromSnooze(wake.threadId)
            } catch (t: Throwable) {
                failed = true
                Log.w(TAG, "Wake failed for ${wake.accountEmail}/${wake.threadId}", t)
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SnoozeWorker"
        const val KEY_ACCOUNT = "account"
        const val KEY_THREAD_ID = "thread_id"

        /** Tag applied to every wake request so per-account cancellation stays easy. */
        const val WORK_TAG = "snooze-wake"

        fun workName(account: String, threadId: String): String = "snooze-$account-$threadId"
    }
}
