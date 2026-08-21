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
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.Thread

internal fun retiredReplyConnectionIds(
    conn: WorkspaceConnection,
    replySucceeded: Boolean,
): List<String> =
    if (replySucceeded) conn.legacyConnectionIds.filter { it != conn.id } else emptyList()

internal fun buildReplyNotificationEvent(
    conn: WorkspaceConnection,
    connectionId: String = conn.id,
    threadId: String,
    fallbackSubject: String,
    preview: String,
    thread: Thread?,
): NeedsYouEvent {
    val messages = thread?.messages ?: emptyList()
    return NeedsYouEvent(
        connectionId = connectionId,
        baseUrl = conn.baseUrl,
        workspaceName = conn.workspaceName,
        threadId = threadId,
        subject = thread?.subject?.ifBlank { fallbackSubject } ?: fallbackSubject,
        preview = preview,
        updatedAt = thread?.updatedAt ?: "",
        messages = messages,
        decision = activeDecision(messages),
    )
}

internal fun buildFailedReplyNotificationEvent(
    persistedConnectionId: String,
    conn: WorkspaceConnection,
    threadId: String,
    fallbackSubject: String,
    preview: String,
): NeedsYouEvent = buildReplyNotificationEvent(
    conn = conn,
    connectionId = persistedConnectionId,
    threadId = threadId,
    fallbackSubject = fallbackSubject,
    preview = preview,
    thread = null,
)

/**
 * Delivers a notification direct-reply / option post reliably: resolves the [WorkspaceConnection]
 * by connId (survives process-death because the payload is persisted in WorkManager), posts via the
 * existing `POST /threads/{id}/messages`, and re-notifies:
 *  - on success: the returned thread (the sent message is appended, clearing the reply spinner);
 *  - on failure: an error line + the attempted text preserved, keeping the reply action for retry.
 *
 * [enqueue] uses unique work keyed by thread so a rapid double-tap or duplicate broadcast for the
 * same thread can't post twice while the first attempt is still in flight.
 */
class ReplyWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val connId = inputData.getString(K_CONN) ?: return Result.failure()
        val threadId = inputData.getString(K_THREAD) ?: return Result.failure()
        val text = inputData.getString(K_TEXT)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val subject = inputData.getString(K_SUBJECT) ?: ""
        val workspace = inputData.getString(K_WORKSPACE) ?: ""
        val baseUrl = inputData.getString(K_BASE_URL) ?: ""
        val retryAttempt = inputData.getInt(K_RETRY_ATTEMPT, 0)

        val notifier = AndroidNotifier(applicationContext)
        val conn = ConnectionsRepository(applicationContext).snapshot().findByConnectionId(connId)
        if (conn == null) {
            notifier.notifyReplyError(
                NeedsYouEvent(connId, baseUrl, workspace, threadId, subject, "", ""),
                text,
                retryAttempt + 1,
            )
            return Result.failure()
        }

        val repo = ThreadsRepository(SpecApi(appHttpClient(applicationContext).client))
        val posted = runCatching { repo.postMessage(conn, threadId, text) }
        return if (posted.isSuccess) {
            val retiredIds = retiredReplyConnectionIds(conn, replySucceeded = true)
            if (retiredIds.isNotEmpty()) {
                val canceller = AndroidNeedsYouCanceller(applicationContext)
                retiredIds.forEach { retiredId -> canceller.cancel(retiredId, threadId) }
            }
            val thread = posted.getOrNull()
            notifier.notify(
                buildReplyNotificationEvent(
                    conn = conn,
                    threadId = threadId,
                    fallbackSubject = subject,
                    preview = text,
                    thread = thread,
                ),
            )
            Result.success()
        } else {
            // Terminal (not retry): auto-retry could double-post. The still-present reply action
            // lets the operator retry manually, and the attempted text is shown so it isn't lost.
            notifier.notifyReplyError(
                buildFailedReplyNotificationEvent(
                    persistedConnectionId = connId,
                    conn = conn,
                    threadId = threadId,
                    fallbackSubject = subject,
                    preview = "",
                ),
                text,
                retryAttempt + 1,
            )
            Result.failure()
        }
    }

    /** Only used when this runs as expedited foreground work on API < 31. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, NotificationChannels.WATCHING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sending reply…")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FG_ID, n)
        }
    }

    companion object {
        private const val FG_ID = 43
        private const val K_CONN = "conn_id"
        private const val K_THREAD = "thread_id"
        private const val K_TEXT = "text"
        private const val K_SUBJECT = "subject"
        private const val K_WORKSPACE = "workspace"
        private const val K_BASE_URL = "base_url"
        private const val K_RETRY_ATTEMPT = "retry_attempt"

        /** Unique work name for a thread's in-flight reply. A failure notification's action uses
         * the next name before the failed worker completes; repeated taps share that next name and
         * KEEP prevents duplicate posts. */
        fun uniqueWorkName(
            connId: String,
            threadId: String,
            retryAttempt: Int = 0,
        ): String = "reply_${connId}_$threadId" +
            if (retryAttempt == 0) "" else "_retry_$retryAttempt"

        internal val enqueuePolicy = ExistingWorkPolicy.KEEP

        fun enqueue(
            context: Context,
            connId: String,
            threadId: String,
            text: String,
            subject: String,
            workspace: String,
            baseUrl: String,
            retryAttempt: Int = 0,
        ) {
            val data = workDataOf(
                K_CONN to connId,
                K_THREAD to threadId,
                K_TEXT to text,
                K_SUBJECT to subject,
                K_WORKSPACE to workspace,
                K_BASE_URL to baseUrl,
                K_RETRY_ATTEMPT to retryAttempt,
            )
            val req = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(connId, threadId, retryAttempt),
                enqueuePolicy,
                req,
            )
        }
    }
}
