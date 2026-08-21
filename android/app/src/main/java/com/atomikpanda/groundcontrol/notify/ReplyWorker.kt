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
import androidx.work.Data
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


/** Durable first hop for notification actions received while the reply database reset is pending. */
class ReplyIntakeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            ReplyStartupGate.awaitReset()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            return if (runAttemptCount < MAX_RESET_RETRIES) Result.retry() else Result.failure()
        }
        val submission = replySubmission(inputData) ?: return Result.failure()
        try {
            ReplyOutboxIntake(applicationContext).submit(submission)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A transient database error must not terminally fail the only durable copy of the
            // validated reply; WorkManager backoff retries until the attempt budget is spent.
            return if (runAttemptCount < MAX_RESET_RETRIES) Result.retry() else Result.failure()
        }
        return Result.success()
    }

    companion object {
        private const val MAX_RESET_RETRIES = 3
        private const val K_CONNECTION_ID = "connection_id"
        private const val K_THREAD_ID = "thread_id"
        private const val K_NOTIFICATION_VERSION = "notification_version"
        private const val K_CAPABILITY = "capability"
        private const val K_REPLY_TEXT = "reply_text"
        private const val K_INPUT_KIND = "input_kind"
        private const val K_SUBJECT = "subject"
        private const val K_WORKSPACE = "workspace"
        private const val K_BASE_URL = "base_url"
        private const val K_DECISION = "decision"
        private const val K_RETRY_ATTEMPT = "retry_attempt"

        internal fun workData(submission: ReplySubmission): Data = workDataOf(
            K_CONNECTION_ID to submission.connectionId,
            K_THREAD_ID to submission.threadId,
            K_NOTIFICATION_VERSION to submission.notificationVersion,
            K_CAPABILITY to submission.actionKey,
            K_REPLY_TEXT to submission.replyText,
            K_INPUT_KIND to submission.inputKind.name,
            K_SUBJECT to submission.subject,
            K_WORKSPACE to submission.workspace,
            K_BASE_URL to submission.baseUrl,
            K_DECISION to submission.decisionJson,
            K_RETRY_ATTEMPT to submission.retryAttempt,
        )

        internal fun replySubmission(data: Data): ReplySubmission? {
            val inputKind = data.getString(K_INPUT_KIND)
                ?.let { runCatching { ReplyInputKind.valueOf(it) }.getOrNull() }
                ?: return null
            return ReplySubmission(
                actionKey = data.getString(K_CAPABILITY) ?: return null,
                connectionId = data.getString(K_CONNECTION_ID) ?: return null,
                threadId = data.getString(K_THREAD_ID) ?: return null,
                notificationVersion = data.getString(K_NOTIFICATION_VERSION) ?: return null,
                replyText = data.getString(K_REPLY_TEXT) ?: return null,
                inputKind = inputKind,
                subject = data.getString(K_SUBJECT).orEmpty(),
                workspace = data.getString(K_WORKSPACE).orEmpty(),
                baseUrl = data.getString(K_BASE_URL).orEmpty(),
                decision = null,
                decisionJson = data.getString(K_DECISION),
                retryAttempt = data.getInt(K_RETRY_ATTEMPT, 0),
            )
        }

        internal fun enqueue(context: Context, submission: ReplySubmission) =
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reply_intake_${submission.actionKey}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReplyIntakeWorker>()
                    .setInputData(workData(submission))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
    }
}
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
