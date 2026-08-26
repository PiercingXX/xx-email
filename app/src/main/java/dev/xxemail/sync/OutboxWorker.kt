package dev.xxemail.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.xxemail.XxEmailApp
import dev.xxemail.data.db.OutboxState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends queued RFC822 payloads. The undo window / schedule delay is expressed as the
 * WorkRequest's initialDelay, so cancelling the unique work == undo.
 */
class OutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as XxEmailApp).graph
        val id = inputData.getLong(KEY_OUTBOX_ID, -1)
        if (id <= 0) return Result.failure()
        val dao = graph.db.outboxDao()
        val entry = dao.get(id) ?: return Result.success() // cancelled/undone before we ran

        if (entry.state != OutboxState.QUEUED.name) return Result.success()

        // Scheduled send fired early (OS quirks): put it back.
        if (entry.kind == dev.xxemail.data.db.OutboxKind.SCHEDULED_SEND.name && System.currentTimeMillis() < entry.targetAt) {
            return Result.retry()
        }

        dao.setState(id, OutboxState.SENDING.name)
        dao.incrementAttempts(id)
        val attemptsUsed = entry.attempts + 1
        return try {
            val raw = requireNotNull(entry.rfc822Base64) { "missing payload" }
            val bytes = java.util.Base64.getUrlDecoder().decode(raw)
            val repo = graph.mailRepository(entry.accountEmail)
            graph.gmailApi(entry.accountEmail).sendRaw(bytes.toRequestBody("message/rfc822".toMediaType()))
            // SENT is recorded BEFORE any post-send work (archiveAfterSend): if archiving
            // throws or the process dies here, the message must never be sent again.
            dao.setState(id, OutboxState.SENT.name)
            if (graph.settings.sendAndArchive()) {
                runCatching { repo.archiveAfterSend(entry.threadId) }
                    .onFailure { Log.w(TAG, "Post-send archive failed for outbox #$id", it) }
            }
            Log.i(TAG, "Sent outbox #$id (${entry.subject})")
            Result.success()
        } catch (e: retrofit2.HttpException) {
            applyOutcome(dao, id, attemptsUsed, SendRetryPolicy.decide(httpCode = e.code(), transportError = false), e.message)
        } catch (e: kotlinx.serialization.SerializationException) {
            // A non-2xx response would have surfaced as HttpException, so reaching body
            // decoding means the server answered 2xx: the send succeeded even though the
            // response could not be decoded. Never retry a message that left the device.
            applyOutcome(dao, id, attemptsUsed, SendRetryPolicy.decide(httpCode = 200, transportError = false), null)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Send transport error for outbox #$id (attempt $attemptsUsed)", e)
            applyOutcome(dao, id, attemptsUsed, SendRetryPolicy.decide(httpCode = null, transportError = true), e.message)
        } catch (e: Throwable) {
            Log.w(TAG, "Send failed for outbox #$id (attempt $attemptsUsed)", e)
            applyOutcome(dao, id, attemptsUsed, SendRetryPolicy.decide(httpCode = null, transportError = true), e.message)
        }
    }

    /** Applies a [SendRetryPolicy.Outcome] to the outbox row and the WorkManager result. */
    private suspend fun applyOutcome(
        dao: dev.xxemail.data.db.OutboxDao,
        id: Long,
        attemptsUsed: Int,
        outcome: SendRetryPolicy.Outcome,
        error: String?,
    ): Result = when (outcome) {
        SendRetryPolicy.Outcome.MARK_SENT -> Result.success()
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
    }
}

/** Wakes a snoozed thread back into the inbox at its scheduled time. */
class SnoozeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as XxEmailApp).graph
        val account = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val threadId = inputData.getString(KEY_THREAD_ID) ?: return Result.failure()
        return try {
            graph.mailRepository(account).unsnooze(threadId)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Unsnooze failed for $threadId", t)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SnoozeWorker"
        const val KEY_ACCOUNT = "account"
        const val KEY_THREAD_ID = "thread_id"
    }
}
