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
import com.atomikpanda.groundcontrol.data.appHttpClient
import java.util.concurrent.TimeUnit

class WatchBackstopWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ReplyStartupGate.awaitReset()
        val database = NotifiedDatabase.get(applicationContext)
        val repo = ThreadsRepository(SpecApi(appHttpClient(applicationContext).client))
        val renderer = NotificationRenderCoordinator(
            database,
            AndroidNotifier(applicationContext),
            AndroidNeedsYouCanceller(applicationContext)::cancel,
        )
        val reconciler = NeedsYouReconciler(
            RoomNotifiedStore(database.notifiedDao()),
            AndroidNotifier(applicationContext),
            repo,
            retire = renderer::retire,
            adopt = renderer::adopt,
            publish = renderer::publish,
            foregroundThreadKey = { OpenThreadRegistry.snapshot() },
            hasActiveCapability = { connectionId, threadId ->
                database.replyNotificationVersionDao().get(connectionId, threadId)?.active == true
            },
        )
        val conns = ConnectionsRepository(applicationContext).snapshot()
        ReplyOutbox(
            database,
            WorkManagerReplyScheduler(applicationContext),
            { conns },
            { submission -> AndroidNeedsYouCanceller(applicationContext).cancel(submission.connectionId, submission.threadId) },
        ).reconcileEligible()
        renderer.reconcilePending()
        if (conns.isEmpty()) return Result.success()
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
