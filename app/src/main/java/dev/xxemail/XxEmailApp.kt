package dev.xxemail

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import dev.xxemail.di.AppGraph
import dev.xxemail.notify.Notifier
import dev.xxemail.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class XxEmailApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifier.ensureChannels(this)
        // Register the periodic poll with the SAVED interval (default 15). Async so we never
        // block startup on DataStore; WorkManager persists the request across reboots, so
        // this also covers process starts that never reach a mailbox screen.
        CoroutineScope(Dispatchers.Default).launch {
            SyncScheduler.ensurePeriodic(graph.workManager, graph.settings.syncMinutes())
        }
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as XxEmailApp).graph

val Context.workManager: WorkManager
    get() = WorkManager.getInstance(this)
