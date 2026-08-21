package com.atomikpanda.groundcontrol.notify

import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.content.Context
import androidx.room.withTransaction
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.Decision
import java.util.UUID
import com.atomikpanda.groundcontrol.data.buildJson
import kotlinx.serialization.encodeToString

/** Complete bounded context accepted from a notification action. */
internal data class ReplySubmission(
    val actionKey: String,
    val connectionId: String,
    val threadId: String,
    val notificationVersion: String,
    val replyText: String,
    val inputKind: ReplyInputKind,
    val subject: String,
    val workspace: String,
    val baseUrl: String,
    val decision: Decision?,
    val decisionJson: String? = null,
    val retryAttempt: Int = 0,
)

internal interface ReplyWorkScheduler {
    fun enqueue(actionKey: String)
    fun isExecutionActive(executionId: String): Boolean = false
}

internal class WorkManagerReplyScheduler(private val context: Context) : ReplyWorkScheduler {
    override fun enqueue(actionKey: String) = ReplyWorker.enqueue(context, actionKey)

    override fun isExecutionActive(executionId: String): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(executionId)).get()?.state in setOf(
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED,
        )
    }.getOrDefault(false)
}

/**
 * Room is the queue authority. WorkManager receives only [ReplySubmission.actionKey] as a wake-up
 * token, so neither a work request nor its name can disclose the reply or decision context.
 */
internal class ReplyOutbox(
    private val database: NotifiedDatabase,
    private val scheduler: ReplyWorkScheduler,
    private val currentConnections: suspend () -> List<WorkspaceConnection>,
    private val notificationActionHandler: suspend (ReplySubmission) -> Unit,
) {
    suspend fun submit(submission: ReplySubmission): Boolean {
        val canonical = currentConnections().findByConnectionId(submission.connectionId)
        val persisted = submission.copy(connectionId = canonical?.id ?: submission.connectionId)
        val accepted = database.withTransaction {
            if (database.replyActionTombstoneDao().get(persisted.actionKey) != null ||
                database.replyOutboxDao().get(persisted.actionKey) != null
            ) return@withTransaction false
            if (canonical != null) {
                val current = database.replyNotificationVersionDao().get(persisted.connectionId, persisted.threadId)
                val capability = if (submission.connectionId == persisted.connectionId || current == null) {
                    database.replyNotificationVersionDao().get(submission.connectionId, persisted.threadId)
                } else {
                    current
                }
                if (
                    capability == null ||
                    !capability.active ||
                    capability.version != persisted.notificationVersion ||
                    capability.capabilityKey != persisted.actionKey
                ) return@withTransaction false
            }
            database.replyOutboxDao().insert(
                ReplyOutboxRecord(
                    actionKey = persisted.actionKey,
                    connectionId = persisted.connectionId,
                    threadId = persisted.threadId,
                    notificationVersion = persisted.notificationVersion,
                    state = if (canonical == null) ReplyOutboxState.WAITING_FOR_CONNECTION else ReplyOutboxState.READY,
                    executionId = null,
                    claimedAtMillis = null,
                    renderVersion = null,
                    renderCapabilityKey = null,
                    replyText = persisted.replyText,
                    inputKind = persisted.inputKind,
                    subject = persisted.subject,
                    workspace = persisted.workspace,
                    baseUrl = persisted.baseUrl,
                    decisionJson = persisted.decisionJson ?: persisted.decision?.let { buildJson().encodeToString(it) },
                    retryAttempt = persisted.retryAttempt,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            ) != -1L
        }
        notificationActionHandler(submission)
        if (accepted && canonical != null) scheduler.enqueue(persisted.actionKey)
        return accepted
    }

    suspend fun reconcileEligible() {
        database.replyOutboxDao().inFlight()
            .filter { row -> row.executionId == null || !scheduler.isExecutionActive(row.executionId) }
            .forEach { row -> database.replyOutboxDao().terminalizeAbandonedClaim(row.actionKey, row.executionId) }
        val connections = currentConnections()
        val resumable = database.withTransaction {
            database.replyOutboxDao().waitingForConnection().mapNotNull { row ->
                if (connections.findByConnectionId(row.connectionId) == null) return@mapNotNull null
                val current = database.replyNotificationVersionDao().get(row.connectionId, row.threadId)
                if (current?.active == true && current.version == row.notificationVersion && current.capabilityKey == row.actionKey) {
                    row.takeIf { database.replyOutboxDao().resumeWaiting(row.actionKey, row.notificationVersion) == 1 }
                } else {
                    database.replyOutboxDao().terminalizeWaiting(row.actionKey, row.notificationVersion)
                    null
                }
            }
        }
        (database.replyOutboxDao().ready() + resumable).distinctBy { it.actionKey }
            .forEach { scheduler.enqueue(it.actionKey) }
    }
}

/** Fixed policy matches WorkManager's historical input boundary without putting input in WorkData. */
internal const val MAX_REPLY_CONTEXT_BYTES = 10_240

internal fun validReplyContext(vararg fields: String): Boolean =
    fields.sumOf { it.toByteArray(Charsets.UTF_8).size } <= MAX_REPLY_CONTEXT_BYTES
