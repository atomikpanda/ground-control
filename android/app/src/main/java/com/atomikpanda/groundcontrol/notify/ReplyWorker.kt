package com.atomikpanda.groundcontrol.notify

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.appHttpClient
import com.atomikpanda.groundcontrol.data.dto.Thread

internal fun retiredReplyConnectionId(
    persistedConnectionId: String,
    conn: WorkspaceConnection,
): String? = persistedConnectionId.takeIf { it != conn.id }

internal fun buildReplyNotificationEvent(
    conn: WorkspaceConnection,
    threadId: String,
    fallbackSubject: String,
    preview: String,
    thread: Thread?,
): NeedsYouEvent = NeedsYouEvent(
    connectionId = conn.id,
    baseUrl = conn.baseUrl,
    workspaceName = conn.workspaceName,
    threadId = threadId,
    subject = thread?.subject?.ifBlank { fallbackSubject } ?: fallbackSubject,
    preview = preview,
    updatedAt = thread?.updatedAt ?: "",
    messages = thread?.messages ?: emptyList(),
    decision = activeDecision(thread?.messages ?: emptyList()),
)

/** WorkManager adapter. Its input is deliberately only the opaque outbox capability. */
class ReplyWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val actionKey = inputData.getString(K_ACTION_KEY) ?: return Result.success()
        val database = NotifiedDatabase.get(applicationContext)
        val renderer = NotificationRenderCoordinator(
            database,
            AndroidNotifier(applicationContext),
            AndroidNeedsYouCanceller(applicationContext)::cancel,
        )
        val repo = ThreadsRepository(SpecApi(appHttpClient(applicationContext).client))
        return ReplyExecutor(
            store = RoomReplyOutboxStore(database),
            currentConnections = { ConnectionsRepository(applicationContext).snapshot() },
            post = { row, connection -> repo.postMessage(connection, row.threadId, row.replyText) },
            renderPending = renderer::renderPending,
        ).execute(actionKey, id.toString())
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.WATCHING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sending reply…")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FG_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FG_ID, notification)
        }
    }

    companion object {
        private const val FG_ID = 43
        internal const val K_ACTION_KEY = "reply_action_key"

        internal fun uniqueWorkName(actionKey: String): String = "reply_$actionKey"

        internal fun workData(actionKey: String) = workDataOf(K_ACTION_KEY to actionKey)

        fun enqueue(context: Context, actionKey: String) {
            val request = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(workData(actionKey))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(actionKey), ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
