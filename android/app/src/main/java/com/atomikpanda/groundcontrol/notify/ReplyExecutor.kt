package com.atomikpanda.groundcontrol.notify

import androidx.room.withTransaction
import androidx.work.ListenableWorker.Result
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.ApiResponseException
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import io.ktor.client.network.sockets.ConnectTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.util.UUID

internal fun classifyReplyFailure(error: Throwable): ReplyDeliveryOutcome = when (error) {
    is UnresolvedAddressException,
    is ConnectTimeoutException,
    is AuthException,
    is NotFoundException,
    is ApiConflictException -> ReplyDeliveryOutcome.SafePreTransmissionFailure
    is ApiResponseException -> if (error.status.value in 400..499) ReplyDeliveryOutcome.SafePreTransmissionFailure else ReplyDeliveryOutcome.Uncertain
    else -> ReplyDeliveryOutcome.Uncertain
}

/** Atomic store seam shared by Room production and deterministic lifecycle tests. */
internal interface ReplyOutboxStore {
    suspend fun get(actionKey: String): ReplyOutboxRecord?
    suspend fun moveReadyToWaiting(row: ReplyOutboxRecord): Boolean
    suspend fun claim(row: ReplyOutboxRecord, executionId: String): Boolean
    suspend fun complete(row: ReplyOutboxRecord, executionId: String, next: ReplyOutboxState): Boolean
}

internal class RoomReplyOutboxStore(private val database: NotifiedDatabase) : ReplyOutboxStore {
    override suspend fun get(actionKey: String) = database.replyOutboxDao().get(actionKey)

    override suspend fun moveReadyToWaiting(row: ReplyOutboxRecord): Boolean = database.withTransaction {
        val current = database.replyOutboxDao().get(row.actionKey) ?: return@withTransaction false
        val version = database.replyNotificationVersionDao().get(current.connectionId, current.threadId)
        val currentGeneration = version?.active == true && version.version == current.notificationVersion &&
            version.capabilityKey == current.actionKey
        if (!currentGeneration) {
            if (current.state == ReplyOutboxState.READY) {
                database.replyOutboxDao().transition(
                    current.actionKey, current.notificationVersion, ReplyOutboxState.READY, ReplyOutboxState.STALE,
                )
            }
            return@withTransaction false
        }
        database.replyOutboxDao().transition(
            current.actionKey, current.notificationVersion, ReplyOutboxState.READY, ReplyOutboxState.WAITING_FOR_CONNECTION,
        ) == 1
    }

    override suspend fun claim(row: ReplyOutboxRecord, executionId: String): Boolean = database.withTransaction {
        val current = database.replyOutboxDao().get(row.actionKey) ?: return@withTransaction false
        val version = database.replyNotificationVersionDao().get(current.connectionId, current.threadId)
        val currentGeneration = version?.active == true && version.version == current.notificationVersion &&
            version.capabilityKey == current.actionKey
        if (!currentGeneration) {
            if (current.state == ReplyOutboxState.READY) {
                database.replyOutboxDao().transition(
                    current.actionKey, current.notificationVersion, ReplyOutboxState.READY, ReplyOutboxState.STALE,
                )
            }
            return@withTransaction false
        }
        database.replyActionTombstoneDao().get(current.actionKey) == null &&
            database.replyOutboxDao().claim(current.actionKey, current.notificationVersion, executionId, System.currentTimeMillis()) == 1
    }

    override suspend fun complete(row: ReplyOutboxRecord, executionId: String, next: ReplyOutboxState): Boolean =
        database.replyOutboxDao().completeClaim(row.actionKey, row.notificationVersion, executionId, next) == 1
}

/** Claims an outbox item once, then makes every post-delivery state durable before rendering. */
internal class ReplyExecutor(
    private val store: ReplyOutboxStore,
    private val currentConnections: suspend () -> List<WorkspaceConnection>,
    private val post: suspend (ReplyOutboxRecord, WorkspaceConnection) -> Unit,
    private val renderPending: suspend (String) -> Unit,
) {
    suspend fun execute(actionKey: String, executionId: String = UUID.randomUUID().toString()): Result {
        val initial = store.get(actionKey) ?: return Result.success()
        if (currentConnections().findByConnectionId(initial.connectionId) == null) {
            store.moveReadyToWaiting(initial)
            return Result.success()
        }
        if (!store.claim(initial, executionId)) return Result.success()
        val current = currentConnections().findByConnectionId(initial.connectionId)
        if (current == null) {
            store.complete(initial, executionId, ReplyOutboxState.WAITING_FOR_CONNECTION)
            return Result.success()
        }
        val next = try {
            post(initial, current)
            ReplyOutboxState.DELIVERED_PENDING_RENDER
        } catch (error: Throwable) {
            when (classifyReplyFailure(error)) {
                ReplyDeliveryOutcome.SafePreTransmissionFailure -> ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER
                ReplyDeliveryOutcome.Uncertain -> ReplyOutboxState.UNCERTAIN_PENDING_RENDER
                ReplyDeliveryOutcome.Delivered -> ReplyOutboxState.DELIVERED_PENDING_RENDER
            }
        }
        if (store.complete(initial, executionId, next)) renderPending(actionKey)
        return Result.success()
    }
}
