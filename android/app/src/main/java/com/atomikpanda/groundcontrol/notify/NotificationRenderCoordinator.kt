package com.atomikpanda.groundcontrol.notify

import androidx.room.withTransaction
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.Decision
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString

internal interface ReplyNotificationRenderer {
    fun renderCurrent(event: NeedsYouEvent, capability: ReplyCapability?, errorLine: String? = null): Boolean
}

data class ReplyCapability(val version: String, val key: String)

/**
 * Serializes every render for a thread with generation state. The final eligibility check and the
 * notifier side effect share one thread lock, so an old completion cannot acknowledge or publish
 * over a newly activated notification.
 */
internal class NotificationRenderCoordinator(
    private val database: NotifiedDatabase,
    private val notifier: ReplyNotificationRenderer,
    private val cancel: (String, String) -> Unit,
) {
    suspend fun publish(event: NeedsYouEvent): Boolean = withThreadLock(event.connectionId, event.threadId) {
        val capability = database.withTransaction {
            val versions = database.replyNotificationVersionDao()
            val current = versions.get(event.connectionId, event.threadId)
            val currentSource = current?.version?.substringBeforeLast('#')
            if (
                current != null &&
                !current.active &&
                (event.updatedAt.isBlank() || currentSource.isNullOrBlank() || event.updatedAt <= currentSource)
            ) {
                // Retirement is authoritative for that generation: a stale poll must not resurrect it.
                return@withTransaction null
            }
            if (current?.active == true) {
                when {
                    currentSource == event.updatedAt -> return@withTransaction ReplyCapability(current.version, requireNotNull(current.capabilityKey))
                    event.updatedAt.isBlank() || (!currentSource.isNullOrBlank() && event.updatedAt < currentSource) ->
                        return@withTransaction null
                }
            }
            val generation = (current?.generation ?: 0L) + 1L
            current?.takeIf { it.active }?.let { versions.deactivate(event.connectionId, event.threadId, it.version) }
            val next = ReplyCapability("${event.updatedAt}#$generation", UUID.randomUUID().toString())
            versions.insert(ReplyNotificationVersionRecord(event.connectionId, event.threadId, next.version, generation, true, next.key))
            next
        } ?: return@withThreadLock false
        val rendered = runCatching { notifier.renderCurrent(event, capability) }.getOrDefault(false)
        rendered
    }

    suspend fun renderPending(actionKey: String) {
        val candidate = database.replyOutboxDao().get(actionKey) ?: return
        withThreadLock(candidate.connectionId, candidate.threadId) {
            val row = database.replyOutboxDao().get(actionKey) ?: return@withThreadLock
            when (row.state) {
                ReplyOutboxState.DELIVERED_PENDING_RENDER,
                ReplyOutboxState.UNCERTAIN_PENDING_RENDER -> renderTerminal(row)
                ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER -> renderSafeFailure(row)
                else -> Unit
            }
        }
    }

    suspend fun reconcilePending() {
        database.replyOutboxDao().pendingRender().forEach { renderPending(it.actionKey) }
    }

    suspend fun retire(connectionId: String, threadId: String, sourceVersion: String) = withThreadLock(connectionId, threadId) {
        val retired = database.withTransaction {
            val current = database.replyNotificationVersionDao().get(connectionId, threadId)
            val currentSource = current?.version?.substringBeforeLast('#')
            if (current?.active == true && (sourceVersion.isBlank() || currentSource == sourceVersion)) {
                database.replyNotificationVersionDao().deactivate(connectionId, threadId, current.version)
                true
            } else false
        }
        if (retired) cancel(connectionId, threadId)
    }

    suspend fun adopt(sourceConnectionId: String, targetConnectionId: String, threadId: String) {
        if (sourceConnectionId == targetConnectionId) return
        withThreadLock(sourceConnectionId, threadId) {
            database.withTransaction {
                val versions = database.replyNotificationVersionDao()
                val source = versions.get(sourceConnectionId, threadId) ?: return@withTransaction
                val target = versions.get(targetConnectionId, threadId)
                if (target != null) {
                    // Any canonical record is authoritative, including an inactive retirement
                    // tombstone. Never resurrect the alias capability over it.
                    database.replyOutboxDao().terminalizeConnectionActions(sourceConnectionId, threadId)
                } else {
                    versions.insert(source.copy(connId = targetConnectionId))
                    database.replyOutboxDao().adoptConnection(sourceConnectionId, targetConnectionId, threadId)
                }
                versions.deactivate(sourceConnectionId, threadId, source.version)
            }
            cancel(sourceConnectionId, threadId)
        }
    }

    private suspend fun renderTerminal(row: ReplyOutboxRecord) {
        val expected = row.state
        val version = database.replyNotificationVersionDao().get(row.connectionId, row.threadId)
        if (version == null || !version.active || version.version != row.notificationVersion || version.capabilityKey != row.actionKey) {
            database.replyOutboxDao().markStale(row.actionKey)
            return
        }
        // Lock is held: no newer activation can appear before cancellation/informational render.
        if (expected == ReplyOutboxState.DELIVERED_PENDING_RENDER) {
            cancel(row.connectionId, row.threadId)
        } else if (!runCatching { notifier.renderCurrent(row.toEvent(), capability = null, errorLine = row.replyText) }.getOrDefault(false)) {
            return
        }
        database.withTransaction {
            val versions = database.replyNotificationVersionDao()
            val current = versions.get(row.connectionId, row.threadId)
            if (current?.active == true && current.version == row.notificationVersion && current.capabilityKey == row.actionKey) {
                versions.deactivate(row.connectionId, row.threadId, row.notificationVersion)
                database.replyOutboxDao().transitionForRender(
                    row.actionKey, row.notificationVersion, expected, null,
                    if (expected == ReplyOutboxState.DELIVERED_PENDING_RENDER) ReplyOutboxState.DELIVERED else ReplyOutboxState.UNCERTAIN,
                )
            } else {
                database.replyOutboxDao().markStale(row.actionKey)
            }
        }
    }

    private suspend fun renderSafeFailure(row: ReplyOutboxRecord) {
        val target = database.withTransaction {
            val versions = database.replyNotificationVersionDao()
            val current = versions.get(row.connectionId, row.threadId)
            if (row.renderVersion != null && row.renderCapabilityKey != null) {
                if (current?.active == true && current.version == row.renderVersion &&
                    current.capabilityKey == row.renderCapabilityKey
                ) return@withTransaction ReplyCapability(row.renderVersion, row.renderCapabilityKey)
                database.replyOutboxDao().markStale(row.actionKey)
                return@withTransaction null
            }
            if (current == null || !current.active || current.version != row.notificationVersion || current.capabilityKey != row.actionKey) {
                database.replyOutboxDao().markStale(row.actionKey)
                return@withTransaction null
            }
            versions.deactivate(row.connectionId, row.threadId, row.notificationVersion)
            val next = ReplyCapability("${row.notificationVersion.substringBeforeLast('#')}#${current.generation + 1}", UUID.randomUUID().toString())
            versions.insert(ReplyNotificationVersionRecord(row.connectionId, row.threadId, next.version, current.generation + 1, true, next.key))
            database.replyOutboxDao().setRenderTarget(row.actionKey, next.version, next.key)
            next
        } ?: return
        if (!runCatching { notifier.renderCurrent(row.toEvent(), target) }.getOrDefault(false)) return
        database.replyOutboxDao().transitionForRender(
            row.actionKey, row.notificationVersion, ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER,
            target.version, ReplyOutboxState.SAFE_FAILURE,
        )
    }

    private suspend fun <T> withThreadLock(connectionId: String, threadId: String, block: suspend () -> T): T =
        coordinatorLock.withLock { block() }

    private fun ReplyOutboxRecord.toEvent(): NeedsYouEvent = NeedsYouEvent(
        connectionId = connectionId,
        baseUrl = baseUrl,
        workspaceName = workspace,
        threadId = threadId,
        subject = subject,
        preview = if (state == ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER) replyText else "",
        updatedAt = notificationVersion.substringBeforeLast('#'),
        decision = decisionJson?.let { runCatching { buildJson().decodeFromString<Decision>(it) }.getOrNull() },
    )

    private companion object {
        val coordinatorLock = Mutex()
    }
}
