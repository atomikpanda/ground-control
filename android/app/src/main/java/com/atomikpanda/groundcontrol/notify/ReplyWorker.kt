package com.atomikpanda.groundcontrol.notify

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.atomikpanda.groundcontrol.data.ApiResponseException
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.appHttpClient
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.Decision
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
    forceNewReplyActionVersion: Boolean = false,
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
        forceNewReplyActionVersion = forceNewReplyActionVersion,
    )
}

internal fun buildFailedReplyNotificationEvent(
    persistedConnectionId: String,
    conn: WorkspaceConnection,
    threadId: String,
    fallbackSubject: String,
    preview: String,
    notificationVersion: String = "",
    fallbackDecision: Decision? = null,
): NeedsYouEvent = buildReplyNotificationEvent(
    conn = conn,
    connectionId = persistedConnectionId,
    threadId = threadId,
    fallbackSubject = fallbackSubject,
    preview = preview,
    thread = null,
).copy(
    updatedAt = notificationVersion,
    decision = fallbackDecision,
    replyActionVersion = notificationVersion,
)

/**
 * Delivers one notification action capability. Work is named by the resolved connection identity,
 * thread, notification version, and retry generation; aliases consequently share a request while a
 * safe retry gets one fresh capability. A Room INSERT claim outlives WorkManager's terminal state,
 * so delayed PendingIntents cannot re-post.
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
        val notificationVersion = inputData.getString(K_NOTIFICATION_VERSION) ?: ""
        val retryAttempt = inputData.getInt(K_RETRY_ATTEMPT, 0)
        val decision = inputDecision()
        val notifier = AndroidNotifier(applicationContext)
        val conn = runCatching {
            ConnectionsRepository(applicationContext).snapshot().findByConnectionId(connId)
        }.getOrNull()
        val canonicalConnId = conn?.id ?: connId
        val database = NotifiedDatabase.get(applicationContext)
        val versionStore = RoomReplyNotificationVersionStore(database.replyNotificationVersionDao())
        val actionStore = RoomReplyActionStore(database.replyActionDao())
        val executionId = id.toString()
        val actionKey = actionKey(canonicalConnId, threadId, notificationVersion)
        val actionStep = database.withTransaction {
            if (versionStore.isCurrent(canonicalConnId, threadId, notificationVersion)) {
                actionStore.claim(actionKey, executionId)
            } else {
                ReplyActionStep.Ignore
            }
        }
        val errorEvent = if (conn == null) {
            NeedsYouEvent(
                connId, baseUrl, workspace, threadId, subject, "", notificationVersion,
                decision = decision,
                replyActionVersion = notificationVersion,
            )
        } else {
            buildFailedReplyNotificationEvent(
                persistedConnectionId = connId,
                conn = conn,
                threadId = threadId,
                fallbackSubject = subject,
                preview = "",
                notificationVersion = notificationVersion,
                fallbackDecision = decision,
            )
        }
        return when (actionStep) {
            ReplyActionStep.Ignore -> Result.success()
            ReplyActionStep.WaitForOwner -> Result.retry()
            ReplyActionStep.RenderSafeFailure -> {
                val ready = actionStore.transition(
                    actionKey, ReplyActionState.SAFE_FAILURE_PENDING_RENDER,
                    ReplyActionState.READY, executionId,
                ) || actionStore.transition(
                    actionKey, ReplyActionState.READY, ReplyActionState.READY, executionId,
                )
                if (!ready) Result.success() else runCatching {
                    notifier.notifyReplyError(errorEvent, text, retryAttempt + 1, allowActions = true)
                }.fold({ Result.failure() }, { Result.retry() })
            }
            ReplyActionStep.RenderUncertain -> runCatching {
                notifier.notifyReplyError(errorEvent, text, retryAttempt, allowActions = false)
            }.fold(
                onSuccess = {
                    if (actionStore.transition(
                            actionKey, ReplyActionState.UNCERTAIN_PENDING_RENDER,
                            ReplyActionState.UNCERTAIN, executionId,
                        )
                    ) Result.failure() else Result.success()
                },
                onFailure = { Result.retry() },
            )
            ReplyActionStep.RenderDelivered -> {
                if (conn == null) {
                    if (!versionStore.isCurrent(canonicalConnId, threadId, notificationVersion)) {
                        actionStore.transition(
                            actionKey, ReplyActionState.DELIVERED_PENDING_RENDER,
                            ReplyActionState.DELIVERED, executionId,
                        )
                        Result.success()
                    } else if (runAttemptCount < MAX_DELIVERED_RENDER_RETRIES) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                } else {
                    runCatching {
                        notifier.notifyDeliveredIfCurrent(
                            buildReplyNotificationEvent(
                                conn = conn, threadId = threadId, fallbackSubject = subject,
                                preview = text, thread = null, forceNewReplyActionVersion = true,
                            ),
                            notificationVersion,
                        )
                    }.fold(
                        onSuccess = {
                            actionStore.transition(
                                actionKey, ReplyActionState.DELIVERED_PENDING_RENDER,
                                ReplyActionState.DELIVERED, executionId,
                            )
                            Result.success()
                        },
                        onFailure = { Result.retry() },
                    )
                }
            }
            ReplyActionStep.Post -> {
                if (conn == null) {
                    if (!actionStore.transition(
                            actionKey, ReplyActionState.IN_FLIGHT,
                            ReplyActionState.SAFE_FAILURE_PENDING_RENDER, executionId,
                        ) || !actionStore.transition(
                            actionKey, ReplyActionState.SAFE_FAILURE_PENDING_RENDER,
                            ReplyActionState.READY, executionId,
                        )
                    ) return Result.success()
                    return runCatching {
                        notifier.notifyReplyError(errorEvent, text, retryAttempt + 1, allowActions = true)
                    }.fold({ Result.failure() }, { Result.retry() })
                }

                val posted = runCatching {
                    ThreadsRepository(SpecApi(appHttpClient(applicationContext).client))
                        .postMessage(conn, threadId, text)
                }
                if (posted.isSuccess) {
                    if (!actionStore.transition(
                            actionKey, ReplyActionState.IN_FLIGHT,
                            ReplyActionState.DELIVERED_PENDING_RENDER, executionId,
                        )
                    ) return Result.success()
                    retiredReplyConnectionIds(conn, replySucceeded = true).forEach { retiredId ->
                        AndroidNeedsYouCanceller(applicationContext).cancel(retiredId, threadId)
                    }
                    runCatching {
                        notifier.notify(
                            buildReplyNotificationEvent(
                                conn = conn, threadId = threadId, fallbackSubject = subject,
                                preview = text, thread = posted.getOrNull(),
                                forceNewReplyActionVersion = true,
                            ),
                        )
                    }.fold(
                        onSuccess = {
                            actionStore.transition(
                                actionKey, ReplyActionState.DELIVERED_PENDING_RENDER,
                                ReplyActionState.DELIVERED, executionId,
                            )
                            Result.success()
                        },
                        onFailure = { Result.retry() },
                    )
                } else {
                    val safe = replyFailureState(
                        (posted.exceptionOrNull() as? ApiResponseException)?.status?.value,
                    ) == ReplyActionState.READY
                    val pending = if (safe) ReplyActionState.SAFE_FAILURE_PENDING_RENDER
                    else ReplyActionState.UNCERTAIN_PENDING_RENDER
                    if (!actionStore.transition(
                            actionKey, ReplyActionState.IN_FLIGHT, pending, executionId,
                        )
                    ) return Result.success()
                    if (safe && !actionStore.transition(
                            actionKey, pending, ReplyActionState.READY, executionId,
                        )
                    ) return Result.success()
                    runCatching {
                        notifier.notifyReplyError(errorEvent, text, retryAttempt + 1, allowActions = safe)
                    }.fold(
                        onSuccess = {
                            if (!safe) actionStore.transition(
                                actionKey, pending, ReplyActionState.UNCERTAIN, executionId,
                            )
                            Result.failure()
                        },
                        onFailure = { Result.retry() },
                    )
                }
            }
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


    private fun inputDecision(): Decision? {
        val options = inputData.getStringArray(K_DECISION_OPTIONS)?.toList().orEmpty()
        if (options.isEmpty()) return null
        return Decision(
            options = options,
            recommended = inputData.getInt(K_DECISION_RECOMMENDED, -1).takeIf { it in options.indices },
            allowFreeText = inputData.getBoolean(K_DECISION_ALLOW_FREE_TEXT, true),
            multi = inputData.getBoolean(K_DECISION_MULTI, false),
        )
    }

    companion object {
        private const val MAX_DELIVERED_RENDER_RETRIES = 3
        private const val FG_ID = 43
        private const val K_CONN = "conn_id"
        private const val K_THREAD = "thread_id"
        private const val K_TEXT = "text"
        private const val K_SUBJECT = "subject"
        private const val K_WORKSPACE = "workspace"
        private const val K_BASE_URL = "base_url"
        private const val K_NOTIFICATION_VERSION = "notification_version"
        private const val K_RETRY_ATTEMPT = "retry_attempt"
        private const val K_DECISION_OPTIONS = "decision_options"
        private const val K_DECISION_RECOMMENDED = "decision_recommended"
        private const val K_DECISION_ALLOW_FREE_TEXT = "decision_allow_free_text"
        private const val K_DECISION_MULTI = "decision_multi"


        /** Durable capability identity. Retry generations contend for this same key. */
        fun actionKey(
            canonicalConnId: String,
            threadId: String,
            notificationVersion: String,
        ): String = "reply_${canonicalConnId}_${threadId}_$notificationVersion"

        /** Receiver-side intake identity. It needs no DataStore read and makes a safe retry runnable
         * while the terminal predecessor is still finishing. */
        fun intakeWorkName(
            connId: String,
            threadId: String,
            notificationVersion: String,
            retryAttempt: Int,
        ): String = "reply_intake_${connId}_${threadId}_${notificationVersion}_$retryAttempt"

        internal fun boundedDecision(decision: Decision?): Decision? =
            actionableDecision(decision, AndroidNotifier.MAX_OPTION_ACTIONS)
        internal val enqueuePolicy = ExistingWorkPolicy.KEEP

        fun enqueue(
            context: Context,
            connId: String,
            threadId: String,
            text: String,
            subject: String,
            workspace: String,
            baseUrl: String,
            notificationVersion: String,
            retryAttempt: Int,
            decision: Decision?,
        ) = WorkManager.getInstance(context).enqueueUniqueWork(
            intakeWorkName(connId, threadId, notificationVersion, retryAttempt),
            enqueuePolicy,
            OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(
                    workDataOf(
                        K_CONN to connId,
                        K_THREAD to threadId,
                        K_TEXT to text,
                        K_SUBJECT to subject,
                        K_WORKSPACE to workspace,
                        K_BASE_URL to baseUrl,
                        K_NOTIFICATION_VERSION to notificationVersion,
                        K_RETRY_ATTEMPT to retryAttempt,
                        K_DECISION_OPTIONS to (boundedDecision(decision)?.options?.toTypedArray()
                            ?: emptyArray<String>()),
                        K_DECISION_RECOMMENDED to (boundedDecision(decision)?.recommended ?: -1),
                        K_DECISION_ALLOW_FREE_TEXT to (boundedDecision(decision)?.allowFreeText ?: true),
                        K_DECISION_MULTI to (boundedDecision(decision)?.multi ?: false),
                    ),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}
