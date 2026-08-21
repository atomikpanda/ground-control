package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.canonicalReplyConnectionId
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.notify.ReplyActionDao
import com.atomikpanda.groundcontrol.notify.ReplyActionRecord
import com.atomikpanda.groundcontrol.notify.ReplyActionState
import com.atomikpanda.groundcontrol.notify.ReplyActionStep
import com.atomikpanda.groundcontrol.notify.ReplyWorker
import com.atomikpanda.groundcontrol.notify.RoomReplyActionStore
import com.atomikpanda.groundcontrol.notify.ReplyNotificationVersionDao
import com.atomikpanda.groundcontrol.notify.ReplyNotificationVersionRecord
import com.atomikpanda.groundcontrol.notify.RoomReplyNotificationVersionStore
import com.atomikpanda.groundcontrol.notify.buildFailedReplyNotificationEvent
import com.atomikpanda.groundcontrol.notify.buildReplyNotificationEvent
import com.atomikpanda.groundcontrol.notify.needsYouNotificationId
import com.atomikpanda.groundcontrol.notify.notificationThreadUri
import com.atomikpanda.groundcontrol.notify.replyFailureState
import com.atomikpanda.groundcontrol.notify.retiredReplyConnectionIds
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ReplyWorkerIdentityTest {
    private class FakeReplyActionDao : ReplyActionDao {
        private val actions = mutableMapOf<String, ReplyActionRecord>()

        override suspend fun get(actionKey: String): ReplyActionRecord? = actions[actionKey]

        override suspend fun insert(record: ReplyActionRecord): Long =
            if (actions.putIfAbsent(record.actionKey, record) == null) 1L else -1L

        override suspend fun reclaim(
            actionKey: String,
            ready: ReplyActionState,
            inFlight: ReplyActionState,
            executionId: String,
        ): Int = if (actions[actionKey]?.state == ready) {
            actions[actionKey] = actions.getValue(actionKey).copy(
                state = inFlight,
                executionId = executionId,
            )
            1
        } else {
            0
        }

        override suspend fun transition(
            actionKey: String,
            expected: ReplyActionState,
            next: ReplyActionState,
            executionId: String,
        ): Int {
            val record = actions[actionKey] ?: return 0
            return if (record.state == expected && record.executionId == executionId) {
                actions[actionKey] = record.copy(state = next)
                1
            } else {
                0
            }
        }
    }

    private class FakeReplyNotificationVersionDao : ReplyNotificationVersionDao {
        private val versions = mutableMapOf<String, ReplyNotificationVersionRecord>()
        private fun key(connId: String, threadId: String) = "$connId|$threadId"

        override suspend fun get(connId: String, threadId: String): ReplyNotificationVersionRecord? =
            versions[key(connId, threadId)]

        override suspend fun save(record: ReplyNotificationVersionRecord) {
            versions[key(record.connId, record.threadId)] = record
        }

        override suspend fun clearIfGeneration(
            connId: String,
            threadId: String,
            generation: Long,
        ): Int {
            val entry = versions[key(connId, threadId)] ?: return 0
            return if (entry.generation == generation && entry.active) {
                versions[key(connId, threadId)] = entry.copy(active = false)
                1
            } else {
                0
            }
        }
    }

    private val canonical = WorkspaceConnection(
        id = "canonical",
        baseUrl = "https://relay.example/hosts/host-1/workspaces/ws-1",
        workspaceName = "workspace",
        legacyConnectionIds = listOf("retired"),
    )

    @Test fun success_notification_uses_the_resolved_canonical_connection_identity() {
        val returned = Thread(
            id = "thread-1",
            subject = "Returned subject",
            updatedAt = "2026-08-20T12:00:00Z",
            messages = listOf(Message("message-1", role = "human", text = "sent")),
        )

        val event = buildReplyNotificationEvent(
            conn = canonical,
            threadId = returned.id,
            fallbackSubject = "Fallback subject",
            preview = "sent",
            thread = returned,
        )

        assertEquals(canonical.id, event.connectionId)
        assertEquals(canonical.baseUrl, event.baseUrl)
        assertEquals("Returned subject", event.subject)
        assertEquals(returned.messages, event.messages)
        assertEquals(
            needsYouNotificationId(canonical.id, returned.id),
            needsYouNotificationId(event.connectionId, event.threadId),
        )
        assertNotEquals(
            needsYouNotificationId("retired", returned.id),
            needsYouNotificationId(event.connectionId, event.threadId),
        )
        assertTrue(
            notificationThreadUri(event.connectionId, event.baseUrl, event.threadId)
                .contains("connection=${canonical.id}"),
        )
    }

    @Test fun failed_retired_reply_keeps_its_notification_but_uses_canonical_work_identity() {
        val event = buildFailedReplyNotificationEvent(
            persistedConnectionId = "retired",
            conn = canonical,
            threadId = "thread-1",
            fallbackSubject = "Fallback subject",
            preview = "",
            fallbackDecision = Decision(options = listOf("Keep", "Discard"), recommended = 0),
        )

        assertEquals("retired", event.connectionId)
        assertEquals(canonical.baseUrl, event.baseUrl)
        assertEquals(canonical.workspaceName, event.workspaceName)
        assertEquals("Fallback subject", event.subject)
        assertEquals(emptyList<Message>(), event.messages)
        assertEquals(
            needsYouNotificationId("retired", event.threadId),
            needsYouNotificationId(event.connectionId, event.threadId),
        )
        assertNotEquals(
            needsYouNotificationId(canonical.id, event.threadId),
            needsYouNotificationId(event.connectionId, event.threadId),
        )
        assertEquals(
            listOf("Keep", "Discard"),
            event.decision!!.options,
        )
        assertTrue(
            notificationThreadUri(event.connectionId, event.baseUrl, event.threadId)
                .contains("connection=retired"),
        )
    }

    @Test fun legacy_worker_data_and_v2_taps_claim_the_same_canonical_capability() = runBlocking {
        val store = RoomReplyActionStore(FakeReplyActionDao())
        val canonicalAction = ReplyWorker.actionKey(canonical.id, "thread-1", "updated-at")
        val legacyAction = ReplyWorker.actionKey(
            listOf(canonical).canonicalReplyConnectionId("retired"),
            "thread-1",
            "updated-at",
        )

        assertEquals(canonicalAction, legacyAction)
        assertEquals(ReplyActionStep.Post, store.claim(legacyAction, "legacy-request"))
        assertEquals(ReplyActionStep.Ignore, store.claim(canonicalAction, "v2-request"))
        assertEquals(ExistingWorkPolicy.KEEP, ReplyWorker.enqueuePolicy)
    }

    @Test fun synchronous_intake_separates_running_retries_without_splitting_the_capability() {
        val initial = ReplyWorker.intakeWorkName("retired", "thread-1", "updated-at", 0)
        val retry = ReplyWorker.intakeWorkName("retired", "thread-1", "updated-at", 1)

        assertNotEquals(initial, retry)
        assertEquals(
            ReplyWorker.actionKey(canonical.id, "thread-1", "updated-at"),
            ReplyWorker.actionKey(canonical.id, "thread-1", "updated-at"),
        )
    }

    @Test fun safe_failure_reconciles_the_same_execution_and_only_new_work_retries() = runBlocking {
        val store = RoomReplyActionStore(FakeReplyActionDao())
        val action = ReplyWorker.actionKey(canonical.id, "thread-1", "updated-at")

        assertEquals(ReplyActionStep.Post, store.claim(action, "request-1"))
        assertTrue(store.transition(
            action, ReplyActionState.IN_FLIGHT, ReplyActionState.SAFE_FAILURE_PENDING_RENDER, "request-1",
        ))
        assertEquals(ReplyActionStep.WaitForOwner, store.claim(action, "request-2"))
        assertEquals(ReplyActionStep.RenderSafeFailure, store.claim(action, "request-1"))
        assertTrue(store.transition(
            action, ReplyActionState.SAFE_FAILURE_PENDING_RENDER, ReplyActionState.READY, "request-1",
        ))
        assertEquals(ReplyActionStep.RenderSafeFailure, store.claim(action, "request-1"))
        assertEquals(ReplyActionStep.Post, store.claim(action, "request-2"))
        assertTrue(store.transition(
            action, ReplyActionState.IN_FLIGHT, ReplyActionState.DELIVERED, "request-2",
        ))
        assertEquals(ReplyActionStep.Ignore, store.claim(action, "request-3"))
    }

    @Test fun stale_execution_cannot_overwrite_a_reclaimed_action() = runBlocking {
        val store = RoomReplyActionStore(FakeReplyActionDao())
        val action = ReplyWorker.actionKey(canonical.id, "thread-1", "updated-at")

        assertEquals(ReplyActionStep.Post, store.claim(action, "request-1"))
        assertTrue(store.transition(
            action, ReplyActionState.IN_FLIGHT, ReplyActionState.READY, "request-1",
        ))
        assertEquals(ReplyActionStep.Post, store.claim(action, "request-2"))

        assertFalse(store.transition(
            action, ReplyActionState.IN_FLIGHT, ReplyActionState.DELIVERED, "request-1",
        ))
    }

    @Test fun restarted_execution_reconciles_without_reposting_and_terminal_render_resumes() = runBlocking {
        val store = RoomReplyActionStore(FakeReplyActionDao())
        val action = ReplyWorker.actionKey(canonical.id, "thread-1", "updated-at")

        assertEquals(ReplyActionStep.Post, store.claim(action, "request-1"))
        assertEquals(ReplyActionStep.RenderUncertain, store.claim(action, "request-1"))
        assertTrue(store.transition(
            action, ReplyActionState.UNCERTAIN_PENDING_RENDER, ReplyActionState.UNCERTAIN, "request-1",
        ))
        assertEquals(ReplyActionStep.Ignore, store.claim(action, "request-2"))

        val delivered = "$action-delivered"
        assertEquals(ReplyActionStep.Post, store.claim(delivered, "request-3"))
        assertTrue(store.transition(
            delivered, ReplyActionState.IN_FLIGHT, ReplyActionState.DELIVERED_PENDING_RENDER, "request-3",
        ))
        assertEquals(ReplyActionStep.RenderDelivered, store.claim(delivered, "request-4"))
    }

    @Test fun newer_or_reactivated_notification_generation_invalidates_stale_actions() = runBlocking {
        val store = RoomReplyNotificationVersionStore(FakeReplyNotificationVersionDao())
        val first = store.activate(canonical.id, "thread-1", "")
        val second = store.activate(canonical.id, "thread-1", "updated-at")

        assertTrue(store.isCurrent(canonical.id, "thread-1", second))
        assertFalse(store.isCurrent(canonical.id, "thread-1", first))
        store.clear(canonical.id, "thread-1", store.activeGeneration(canonical.id, "thread-1"))
        val nextCycle = store.activate(canonical.id, "thread-1", "updated-at")
        assertNotEquals(second, nextCycle)
    }

    @Test fun delayed_clear_for_generation_n_does_not_deactivate_forced_successor() = runBlocking {
        val store = RoomReplyNotificationVersionStore(FakeReplyNotificationVersionDao())
        store.activate(canonical.id, "thread-1", "same-source")
        val observedGeneration = store.activeGeneration(canonical.id, "thread-1")
        val successor = store.activate(
            canonical.id, "thread-1", "same-source", forceNewGeneration = true,
        )

        store.clear(canonical.id, "thread-1", observedGeneration)

        assertTrue(store.isCurrent(canonical.id, "thread-1", successor))
    }

    @Test fun post_success_rotates_to_a_usable_successor_action_generation() = runBlocking {
        val store = RoomReplyNotificationVersionStore(FakeReplyNotificationVersionDao())
        val deliveredVersion = store.activate(canonical.id, "thread-1", "updated-at")
        val successorVersion = store.activate(
            canonical.id,
            "thread-1",
            "updated-at",
            forceNewGeneration = true,
        )

        assertNotEquals(deliveredVersion, successorVersion)
        assertFalse(store.isCurrent(canonical.id, "thread-1", deliveredVersion))
        assertTrue(store.isCurrent(canonical.id, "thread-1", successorVersion))
        assertNotEquals(
            ReplyWorker.actionKey(canonical.id, "thread-1", deliveredVersion),
            ReplyWorker.actionKey(canonical.id, "thread-1", successorVersion),
        )
    }

    @Test fun only_definitive_client_rejection_surfaces_a_retry_action() {
        assertEquals(ReplyActionState.READY, replyFailureState(400))
        assertEquals(ReplyActionState.READY, replyFailureState(404))
        assertEquals(ReplyActionState.UNCERTAIN, replyFailureState(500))
        assertEquals(ReplyActionState.UNCERTAIN, replyFailureState(null))
    }

    @Test fun successful_delivery_cleans_up_retired_notification_identities() {
        val canonicalWithMultipleRetiredIds = canonical.copy(
            legacyConnectionIds = listOf("retired", "older-retired", canonical.id),
        )

        assertEquals(
            listOf("retired", "older-retired"),
            retiredReplyConnectionIds(canonicalWithMultipleRetiredIds, replySucceeded = true),
        )
    }

    @Test fun legacy_action_without_a_notification_version_remains_deduplicated() {
        assertEquals(
            ReplyWorker.actionKey(canonical.id, "thread-1", ""),
            ReplyWorker.actionKey(canonical.id, "thread-1", ""),
        )
    }
}
