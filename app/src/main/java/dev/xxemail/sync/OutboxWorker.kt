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
        return try {
            val raw = requireNotNull(entry.rfc822Base64) { "missing payload" }
            val bytes = java.util.Base64.getUrlDecoder().decode(raw)
            val repo = graph.mailRepository(entry.accountEmail)
            val sent = graph.gmailApi(entry.accountEmail).sendRaw(bytes.toRequestBody("message/rfc822".toMediaType()))
            dao.setState(id, OutboxState.SENT.name)
            if (graph.settings.sendAndArchive()) repo.archiveAfterSend(sent.threadId ?: entry.threadId)
            Log.i(TAG, "Sent outbox #$id (${entry.subject})")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Send failed for outbox #$id (attempt ${entry.attempts + 1})", t)
            if (entry.attempts + 1 >= MAX_ATTEMPTS) {
                dao.setState(id, OutboxState.FAILED.name, t.message)
                Result.failure()
            } else {
                dao.setState(id, OutboxState.QUEUED.name)
                Result.retry()
            }
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
