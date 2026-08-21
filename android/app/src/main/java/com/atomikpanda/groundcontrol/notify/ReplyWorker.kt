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

internal data class LegacyReplyInput(
    val connectionId: String,
    val threadId: String,
    val replyText: String,
    val subject: String,
    val workspace: String,
    val baseUrl: String,
)

/** WorkManager adapter. Current work carries only an opaque outbox capability. */
class ReplyWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        awaitStartupGate()
        val database = NotifiedDatabase.get(applicationContext)
        val actionKey = inputData.getString(K_ACTION_KEY)
        if (actionKey == null) return rejectLegacyWork(database)
        val renderer = NotificationRenderCoordinator(
            database,
            AndroidNotifier(applicationContext),
            AndroidNeedsYouCanceller(applicationContext)::cancel,
        )
        renderer.reconcilePending()
        val repo = ThreadsRepository(SpecApi(appHttpClient(applicationContext).client))
        return ReplyExecutor(
            store = RoomReplyOutboxStore(database),
            currentConnections = { ConnectionsRepository(applicationContext).snapshot() },
            post = { row, connection -> repo.postMessage(connection, row.threadId, row.replyText) },
            renderPending = renderer::renderPending,
        ).execute(actionKey, id.toString())
    }

    private suspend fun rejectLegacyWork(database: NotifiedDatabase): Result {
        val legacy = legacyReplyInput(inputData) ?: return Result.failure()
        // PR-76's former worker persisted only these fields. It has neither a capability nor an
        // idempotency key, so replaying it after upgrade could duplicate an already delivered POST.
        database.replyActionTombstoneDao().insert(
            ReplyActionTombstone("$LEGACY_ACTION_PREFIX$id", "LEGACY_UNEXECUTABLE"),
        )
        runCatching {
            AndroidNotifier(applicationContext).renderCurrent(
                NeedsYouEvent(
                    legacy.connectionId,
                    legacy.baseUrl,
                    legacy.workspace,
                    legacy.threadId,
                    legacy.subject,
                    "",
                    "",
                ),
                capability = null,
                errorLine = legacy.replyText,
            )
        }
        return Result.failure()
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
        private const val K_LEGACY_CONN = "conn_id"
        private const val K_LEGACY_THREAD = "thread_id"
        private const val K_LEGACY_TEXT = "text"
        private const val K_LEGACY_SUBJECT = "subject"
        private const val K_LEGACY_WORKSPACE = "workspace"
        private const val K_LEGACY_BASE_URL = "base_url"
        private const val LEGACY_ACTION_PREFIX = "legacy_reply_"

        internal suspend fun awaitStartupGate() = ReplyStartupGate.awaitReset()

        internal fun legacyReplyInput(data: androidx.work.Data): LegacyReplyInput? {
            val connectionId = data.getString(K_LEGACY_CONN) ?: return null
            val threadId = data.getString(K_LEGACY_THREAD) ?: return null
            val replyText = data.getString(K_LEGACY_TEXT)?.takeIf { it.isNotBlank() } ?: return null
            return LegacyReplyInput(
                connectionId,
                threadId,
                replyText,
                data.getString(K_LEGACY_SUBJECT).orEmpty(),
                data.getString(K_LEGACY_WORKSPACE).orEmpty(),
                data.getString(K_LEGACY_BASE_URL).orEmpty(),
            )
        }

        internal fun legacyWorkData(
            connectionId: String,
            threadId: String,
            replyText: String,
            subject: String,
            workspace: String,
            baseUrl: String,
        ) = workDataOf(
            K_LEGACY_CONN to connectionId,
            K_LEGACY_THREAD to threadId,
            K_LEGACY_TEXT to replyText,
            K_LEGACY_SUBJECT to subject,
            K_LEGACY_WORKSPACE to workspace,
            K_LEGACY_BASE_URL to baseUrl,
        )

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
