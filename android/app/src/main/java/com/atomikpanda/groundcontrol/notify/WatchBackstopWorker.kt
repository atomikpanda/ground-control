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
        val reconciler = NeedsYouReconciler(
            RoomNotifiedStore(NotifiedDatabase.get(applicationContext).notifiedDao()),
            AndroidNotifier(applicationContext),
        )
        val repo = ThreadsRepository(SpecApi(defaultHttpClient()))
        val conns = ConnectionsRepository(applicationContext).snapshot()
        return runCatching {
            conns.forEach { reconciler.fetchAndReconcile(it, repo) }
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
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
