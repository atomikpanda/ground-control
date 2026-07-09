package com.atomikpanda.groundcontrol.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.defaultHttpClient
import java.util.concurrent.TimeUnit

class WatchBackstopWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = ThreadsRepository(SpecApi(defaultHttpClient()))
        val reconciler = NeedsYouReconciler(
            RoomNotifiedStore(NotifiedDatabase.get(applicationContext).notifiedDao()),
            AndroidNotifier(applicationContext),
            repo,
        )
        val conns = ConnectionsRepository(applicationContext).snapshot()
        if (conns.isEmpty()) return Result.success()
        // Isolate failures per connection: one failing/auth-erroring workspace must not skip the
        // others. Retry the whole job only if EVERY connection failed (a wide transient issue).
        val results = conns.map { conn -> runCatching { reconciler.fetchAndReconcile(conn) } }
        return if (results.all { it.isFailure }) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "watch_backstop"
        fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<WatchBackstopWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
