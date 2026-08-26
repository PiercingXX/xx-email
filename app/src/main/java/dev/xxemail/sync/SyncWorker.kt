package dev.xxemail.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.xxemail.XxEmailApp
import dev.xxemail.di.AppGraph
import dev.xxemail.notify.Notifier
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Polling sync engine (no FCM by design — see docs/architecture.md). */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as XxEmailApp).graph
        val accounts = graph.db.accountDao().observeAll().first()
        if (accounts.isEmpty()) return Result.success()

        val forceFull = inputData.getBoolean(KEY_FORCE_FULL, false)
        var successes = 0
        for (account in accounts) {
            val outcome = graph.mailRepository(account.email).sync(forceFull = forceFull)
            outcome.onSuccess { result ->
                successes++
                if (result.newInboxThreads.isNotEmpty() && graph.settings.notificationsEnabled()) {
                    Notifier.notifyNewMail(applicationContext, account.email, result.newInboxThreads)
                }
            }
        }
        return when {
            successes > 0 -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object { const val KEY_FORCE_FULL = "force_full" }
}

object SyncScheduler {

    const val UNIQUE_PERIODIC = "sync-periodic"

    /** Unique name for one-shot syncs (manual kicks and delta-overflow follow-ups). */
    const val UNIQUE_ONESHOT = "sync-now"

    /** (Re)registers the periodic poll. WorkManager floor is 15 minutes. */
    fun ensurePeriodic(workManager: WorkManager, intervalMinutes: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun kickImmediate(graph: AppGraph, forceFull: Boolean = false) {
        graph.workManager.enqueueUniqueWork(
            UNIQUE_ONESHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf(SyncWorker.KEY_FORCE_FULL to forceFull))
                .setConstraints(
                    androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }
}
