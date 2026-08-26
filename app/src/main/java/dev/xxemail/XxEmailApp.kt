package dev.xxemail

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import dev.xxemail.di.AppGraph
import dev.xxemail.notify.Notifier
import dev.xxemail.sync.SyncScheduler

class XxEmailApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifier.ensureChannels(this)
        SyncScheduler.ensurePeriodic(graph.workManager, 15)
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as XxEmailApp).graph

val Context.workManager: WorkManager
    get() = WorkManager.getInstance(this)
