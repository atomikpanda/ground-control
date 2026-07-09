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
import com.atomikpanda.groundcontrol.data.defaultHttpClient

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

        val notifier = AndroidNotifier(applicationContext)
        val conn = ConnectionsRepository(applicationContext).snapshot().firstOrNull { it.id == connId }
        if (conn == null) {
            notifier.notifyReplyError(
                NeedsYouEvent(connId, baseUrl, workspace, threadId, subject, "", ""), text,
            )
            return Result.failure()
        }

        val repo = ThreadsRepository(SpecApi(defaultHttpClient()))
        val posted = runCatching { repo.postMessage(conn, threadId, text) }
        return if (posted.isSuccess) {
            val thread = posted.getOrNull()
            val messages = thread?.messages ?: emptyList()
            notifier.notify(
                NeedsYouEvent(
                    connectionId = connId,
                    baseUrl = conn.baseUrl,
                    workspaceName = conn.workspaceName,
                    threadId = threadId,
                    subject = thread?.subject?.ifBlank { subject } ?: subject,
                    preview = text,
                    updatedAt = thread?.updatedAt ?: "",
                    messages = messages,
                    decision = activeDecision(messages),
                ),
            )
            Result.success()
        } else {
            // Terminal (not retry): auto-retry could double-post. The still-present reply action
            // lets the operator retry manually, and the attempted text is shown so it isn't lost.
            notifier.notifyReplyError(
                NeedsYouEvent(connId, conn.baseUrl, conn.workspaceName, threadId, subject, "", ""), text,
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

        /** Unique work name for a thread's in-flight reply, so a duplicate enqueue is a no-op. */
        fun uniqueWorkName(connId: String, threadId: String): String = "reply_${connId}_$threadId"

        fun enqueue(
            context: Context,
            connId: String,
            threadId: String,
            text: String,
            subject: String,
            workspace: String,
            baseUrl: String,
        ) {
            val data = workDataOf(
                K_CONN to connId,
                K_THREAD to threadId,
                K_TEXT to text,
                K_SUBJECT to subject,
                K_WORKSPACE to workspace,
                K_BASE_URL to baseUrl,
            )
            val req = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // KEEP: a rapid double-tap or duplicate broadcast for the same thread must not post
            // twice while the first reply is still in flight.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueWorkName(connId, threadId), ExistingWorkPolicy.KEEP, req)
        }
    }
}
