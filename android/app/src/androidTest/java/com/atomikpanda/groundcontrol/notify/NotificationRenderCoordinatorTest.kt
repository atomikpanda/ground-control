package com.atomikpanda.groundcontrol.notify

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.messages.ConversationUiState
import com.atomikpanda.groundcontrol.ui.messages.ConversationViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeReplyRenderer : ReplyNotificationRenderer {
    var succeeds = true
    var throws = false
    val renders = mutableListOf<ReplyCapability?>()
    val errorLines = mutableListOf<String?>()
    override fun renderCurrent(event: NeedsYouEvent, capability: ReplyCapability?, errorLine: String?): Boolean {
        renders += capability
        errorLines += errorLine
        if (throws) throw IllegalStateException("notifier failure")
        return succeeds
    }
}

class NotificationRenderCoordinatorTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<android.content.Context>(), NotifiedDatabase::class.java,
    ).build()

    private fun row(
        state: ReplyOutboxState,
        version: String = "source#1",
        key: String = "cap",
        connectionId: String = "c",
        threadId: String = "t",
    ) = ReplyOutboxRecord(
        key, connectionId, threadId, version, state, null, null, null, null, "reply", ReplyInputKind.FREE_TEXT,
        "subject", "workspace", "https://example", null, 0, 1,
    )

    @Test fun conversation_done_retires_the_loaded_reply_capability() {
        assertConversationDoneRetirement(newerQuestion = false)
    }

    @Test fun conversation_done_preserves_a_newer_question_notification_and_capability() {
        assertConversationDoneRetirement(newerQuestion = true)
    }

    private fun assertConversationDoneRetirement(newerQuestion: Boolean) = runBlocking {
        val db = database()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = "2026-09-06T12:00:00Z"
        val activeSource = if (newerQuestion) "2026-09-06T12:01:00Z" else source
        val connection = WorkspaceConnection("c", "http://example", "secret", "workspace")
        var notificationVisible = false
        val canceller = object : NeedsYouCanceller {
            override fun cancel(connId: String, threadId: String) {
                notificationVisible = false
            }
        }
        val client = HttpClient(MockEngine { request ->
            val resolved = request.url.encodedPath.endsWith("/resolve")
            respond(
                """{
                    "id":"t","updated_at":"$source","needs_you":${!resolved},
                    "resolved_through_message_id":${if (resolved) "\"m\"" else "null"},
                    "messages":[{"id":"m","role":"agent","text":"Review this","kind":"needs_you"}]
                }""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { mshipDefaults() }
        try {
            val coordinator = NotificationRenderCoordinator(db, FakeReplyRenderer(), canceller::cancel)
            val vm = ConversationViewModel(
                ThreadsRepository(SpecApi(client)), connection.id, "t",
                MutableStateFlow(ConnectionState.Ready(listOf(connection))),
                testScope = scope,
                canceller = canceller,
                retireReplyCapability = coordinator::retire,
            )
            vm.load()?.join()
            db.replyNotificationVersionDao().insert(
                ReplyNotificationVersionRecord("c", "t", "$activeSource#1", 1, true, "cap"),
            )
            // A notification can arrive after opening the conversation, while Done is pending.
            notificationVisible = true
            vm.resolve()?.join()

            assertFalse((vm.state.value as ConversationUiState.Content).thread.needsYou)
            val capability = db.replyNotificationVersionDao().get("c", "t")!!
            assertEquals(newerQuestion, capability.active)
            assertEquals(newerQuestion, notificationVisible)
        } finally {
            scope.cancel()
            client.close()
            db.close()
        }
    }

    @Test fun delivered_ack_cancels_and_deactivates_only_matching_generation() = runBlocking {
        val db = database(); val cancelled = mutableListOf<String>(); val renderer = FakeReplyRenderer()
        db.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("c", "t", "source#1", 1, true, "cap"))
        db.replyOutboxDao().insert(row(ReplyOutboxState.DELIVERED_PENDING_RENDER))
        NotificationRenderCoordinator(db, renderer) { c, t -> cancelled += "$c|$t" }.renderPending("cap")
        assertEquals(ReplyOutboxState.DELIVERED, db.replyOutboxDao().get("cap")!!.state)
        assertFalse(db.replyNotificationVersionDao().get("c", "t")!!.active)
        assertEquals(listOf("c|t"), cancelled)
        db.close()
    }

    @Test fun restart_replays_cancellation_for_a_retired_capability() = runBlocking {
        val db = database(); val cancelled = mutableListOf<String>()
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("c", "t", "source#1", 1, false, null),
        )

        NotificationRenderCoordinator(db, FakeReplyRenderer()) { c, t -> cancelled += "$c|$t" }
            .reconcilePending()

        assertEquals(listOf("c|t"), cancelled)
        db.close()
    }

    @Test fun generation_advance_suppresses_stale_render_and_cannot_resurrect_old_actions() = runBlocking {
        val db = database(); val renderer = FakeReplyRenderer()
        db.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("c", "t", "source#2", 2, true, "new-cap"))
        db.replyOutboxDao().insert(row(ReplyOutboxState.DELIVERED_PENDING_RENDER))
        NotificationRenderCoordinator(db, renderer) { _, _ -> error("stale generation cancelled") }.renderPending("cap")
        assertEquals(ReplyOutboxState.STALE, db.replyOutboxDao().get("cap")!!.state)
        assertTrue(db.replyNotificationVersionDao().get("c", "t")!!.active)
        assertTrue(renderer.renders.isEmpty())
        db.close()
    }

    @Test fun publication_activates_one_opaque_capability_per_generation() = runBlocking {
        val db = database(); val renderer = FakeReplyRenderer()
        val coordinator = NotificationRenderCoordinator(db, renderer) { _, _ -> }
        val event = NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "source")
        coordinator.publish(event)
        val first = db.replyNotificationVersionDao().get("c", "t")!!
        coordinator.publish(event)
        val repeated = db.replyNotificationVersionDao().get("c", "t")!!
        assertTrue(first.active)
        assertEquals(1L, first.generation)
        assertEquals(first.capabilityKey, repeated.capabilityKey)
        assertEquals(2, renderer.renders.size)
        db.close()
    }

    @Test fun old_source_publishing_after_new_source_cannot_replace_new_actionable_generation() = runBlocking {
        val db = database(); val renderer = FakeReplyRenderer()
        val coordinator = NotificationRenderCoordinator(db, renderer) { _, _ -> }
        coordinator.publish(NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "2026-08-22T00:00:00Z"))
        val newer = db.replyNotificationVersionDao().get("c", "t")!!
        coordinator.publish(NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "2026-08-21T00:00:00Z"))
        val retained = db.replyNotificationVersionDao().get("c", "t")!!
        assertEquals(newer.version, retained.version)
        assertEquals(newer.capabilityKey, retained.capabilityKey)
        db.close()
    }

    @Test fun notifier_throw_leaves_uncertain_pending_for_restart_recovery() = runBlocking {
        val db = database(); val renderer = FakeReplyRenderer(); renderer.throws = true
        db.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("c", "t", "source#1", 1, true, "cap"))
        db.replyOutboxDao().insert(row(ReplyOutboxState.UNCERTAIN_PENDING_RENDER))
        val coordinator = NotificationRenderCoordinator(db, renderer) { _, _ -> }
        coordinator.renderPending("cap")
        assertEquals(ReplyOutboxState.UNCERTAIN_PENDING_RENDER, db.replyOutboxDao().get("cap")!!.state)
        renderer.throws = false
        coordinator.reconcilePending()
        assertEquals(ReplyOutboxState.UNCERTAIN, db.replyOutboxDao().get("cap")!!.state)
        db.close()
    }

    @Test fun safe_failure_notifier_failure_reuses_prepared_capability_on_restart() = runBlocking {
        val db = database(); val renderer = FakeReplyRenderer(); renderer.throws = true
        db.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("c", "t", "source#1", 1, true, "cap"))
        db.replyOutboxDao().insert(row(ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER))
        val coordinator = NotificationRenderCoordinator(db, renderer) { _, _ -> }
        coordinator.renderPending("cap")
        val pending = db.replyOutboxDao().get("cap")!!
        assertEquals(ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER, pending.state)
        val prepared = pending.renderCapabilityKey
        renderer.throws = false
        coordinator.reconcilePending()
        val completed = db.replyOutboxDao().get("cap")!!
        assertEquals(ReplyOutboxState.SAFE_FAILURE, completed.state)
        assertEquals(prepared, completed.renderCapabilityKey)
        assertEquals(prepared, db.replyNotificationVersionDao().get("c", "t")!!.capabilityKey)
        db.close()
    }

    @Test fun safe_failure_renders_once_with_a_fresh_capability() = runBlocking {
        val db = database(); val renderer = FakeReplyRenderer()
        db.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("c", "t", "source#1", 1, true, "cap"))
        db.replyOutboxDao().insert(row(ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER))
        NotificationRenderCoordinator(db, renderer) { _, _ -> }.renderPending("cap")
        val persisted = db.replyOutboxDao().get("cap")!!
        assertEquals(ReplyOutboxState.SAFE_FAILURE, persisted.state)
        assertNotNull(persisted.renderCapabilityKey)
        assertTrue(persisted.renderCapabilityKey != "cap")
        assertEquals(1, renderer.renders.size)
        assertEquals(persisted.renderCapabilityKey, renderer.renders.single()!!.key)
        db.close()
    }

    @Test fun safe_failure_renders_attempted_reply_as_error_line() = runBlocking {
        val db = database(); val renderer = FakeReplyRenderer()
        db.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("c", "t", "source#1", 1, true, "cap"))
        db.replyOutboxDao().insert(row(ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER))

        NotificationRenderCoordinator(db, renderer) { _, _ -> }.renderPending("cap")

        assertEquals(listOf("reply"), renderer.errorLines)
        db.close()
    }

    @Test fun failed_publication_remains_eligible_without_duplicate_capability() = runBlocking {
        val db = database()
        val renderer = FakeReplyRenderer().apply { succeeds = false }
        val coordinator = NotificationRenderCoordinator(db, renderer) { _, _ -> }
        val event = NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "source")

        assertFalse(coordinator.publish(event))
        val first = db.replyNotificationVersionDao().get("c", "t")!!
        renderer.succeeds = true
        assertTrue(coordinator.publish(event))
        val retried = db.replyNotificationVersionDao().get("c", "t")!!

        assertTrue(retried.active)
        assertEquals(first.version, retried.version)
        assertEquals(first.capabilityKey, retried.capabilityKey)
        assertEquals(2, renderer.renders.size)
        db.close()
    }

    @Test fun failed_newer_publication_restores_the_previous_actionable_generation() = runBlocking {
        val db = database()
        val renderer = FakeReplyRenderer().apply { succeeds = false }
        val coordinator = NotificationRenderCoordinator(db, renderer) { _, _ -> }
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("c", "t", "old#1", 1, true, "old-cap"),
        )

        assertFalse(
            coordinator.publish(
                NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "new"),
            ),
        )

        val restored = db.replyNotificationVersionDao().get("c", "t")!!
        assertTrue(restored.active)
        assertEquals("old#1", restored.version)
        assertEquals("old-cap", restored.capabilityKey)
        db.close()
    }

    @Test fun stale_resolution_cannot_retire_a_newer_publication() = runBlocking {
        val db = database()
        val renderer = FakeReplyRenderer()
        val cancelled = mutableListOf<String>()
        val coordinator = NotificationRenderCoordinator(db, renderer) { c, t -> cancelled += "$c|$t" }
        coordinator.publish(NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "2026-08-22T00:00:00Z"))

        coordinator.retire("c", "t", "2026-08-21T00:00:00Z")

        assertTrue(db.replyNotificationVersionDao().get("c", "t")!!.active)
        assertTrue(cancelled.isEmpty())
        db.close()
    }

    @Test fun newer_resolution_retires_an_older_active_publication() = runBlocking {
        val db = database()
        val cancelled = mutableListOf<String>()
        val coordinator = NotificationRenderCoordinator(db, FakeReplyRenderer()) { c, t -> cancelled += "$c|$t" }
        coordinator.publish(NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "2026-08-21T00:00:00Z"))

        coordinator.retire("c", "t", "2026-08-22T00:00:00Z")

        assertFalse(db.replyNotificationVersionDao().get("c", "t")!!.active)
        assertEquals(listOf("c|t"), cancelled)
        db.close()
    }

    @Test fun stale_publication_cannot_resurrect_a_retired_generation() = runBlocking {
        val db = database()
        val renderer = FakeReplyRenderer()
        val coordinator = NotificationRenderCoordinator(db, renderer) { _, _ -> }
        coordinator.publish(NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "2026-08-22T00:00:00Z"))
        coordinator.retire("c", "t", "2026-08-22T00:00:00Z")

        assertFalse(coordinator.publish(NeedsYouEvent("c", "https://example", "workspace", "t", "subject", "", "2026-08-21T00:00:00Z")))

        assertFalse(db.replyNotificationVersionDao().get("c", "t")!!.active)
        assertEquals(1, renderer.renders.size)
        db.close()
    }

    @Test fun adoption_moves_every_recoverable_pending_state_to_an_empty_target() = runBlocking {
        val states = listOf(
            ReplyOutboxState.READY,
            ReplyOutboxState.WAITING_FOR_CONNECTION,
            ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER,
            ReplyOutboxState.DELIVERED_PENDING_RENDER,
            ReplyOutboxState.UNCERTAIN_PENDING_RENDER,
        )
        states.forEachIndexed { index, state ->
            val db = database()
            val key = "cap-$index"
            val thread = "thread-$index"
            db.replyNotificationVersionDao().insert(
                ReplyNotificationVersionRecord("alias", thread, "source#1", 1, true, key),
            )
            db.replyOutboxDao().insert(row(state, key = key, connectionId = "alias", threadId = thread))
            val cancelled = mutableListOf<String>()

            NotificationRenderCoordinator(db, FakeReplyRenderer()) { c, t -> cancelled += "$c|$t" }
                .adopt("alias", "canonical", NeedsYouEvent("canonical", "https://example", "workspace", thread, "subject", "", "source"))

            assertEquals("canonical", db.replyOutboxDao().get(key)!!.connectionId)
            assertTrue(db.replyNotificationVersionDao().get("canonical", thread)!!.active)
            assertFalse(db.replyNotificationVersionDao().get("alias", thread)!!.active)
            assertEquals(listOf("alias|$thread"), cancelled)
            db.close()
        }
    }

    @Test fun adoption_moves_an_in_flight_reply_to_the_canonical_generation() = runBlocking {
        val db = database()
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "thread", "source#1", 1, true, "alias-cap"),
        )
        db.replyOutboxDao().insert(
            row(ReplyOutboxState.IN_FLIGHT, key = "alias-cap", connectionId = "alias", threadId = "thread"),
        )

        NotificationRenderCoordinator(db, FakeReplyRenderer()) { _, _ -> }
            .adopt("alias", "canonical", NeedsYouEvent("canonical", "https://example", "workspace", "thread", "subject", "", "source"))

        val adopted = db.replyOutboxDao().get("alias-cap")!!
        assertEquals("canonical", adopted.connectionId)
        assertEquals(ReplyOutboxState.IN_FLIGHT, adopted.state)
        db.close()
    }

    @Test fun adoption_renders_canonical_replacement_before_retiring_alias_notification() = runBlocking {
        val db = database()
        val renderer = FakeReplyRenderer()
        val cancelled = mutableListOf<String>()
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "thread", "source#1", 1, true, "alias-cap"),
        )

        assertEquals(
            ReplyCapabilityAdoption.ADOPTED,
            NotificationRenderCoordinator(db, renderer) { c, t -> cancelled += "$c|$t" }
                .adopt("alias", "canonical", NeedsYouEvent("canonical", "https://example", "workspace", "thread", "subject", "", "source")),
        )

        assertEquals(listOf("alias|thread"), cancelled)
        assertEquals(listOf("alias-cap"), renderer.renders.map { it?.key })
        assertTrue(db.replyNotificationVersionDao().get("canonical", "thread")!!.active)
        assertFalse(db.replyNotificationVersionDao().get("alias", "thread")!!.active)
        db.close()
    }

    @Test fun canonical_collision_terminalizes_every_alias_pending_state() = runBlocking {
        val states = listOf(
            ReplyOutboxState.READY,
            ReplyOutboxState.WAITING_FOR_CONNECTION,
            ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER,
            ReplyOutboxState.DELIVERED_PENDING_RENDER,
            ReplyOutboxState.UNCERTAIN_PENDING_RENDER,
        )
        states.forEachIndexed { index, state ->
            val db = database()
            val key = "alias-cap-$index"
            val thread = "thread-$index"
            db.replyNotificationVersionDao().insert(
                ReplyNotificationVersionRecord("alias", thread, "source#1", 1, true, key),
            )
            db.replyNotificationVersionDao().insert(
                ReplyNotificationVersionRecord("canonical", thread, "source#2", 2, true, "canonical-cap"),
            )
            db.replyOutboxDao().insert(row(state, key = key, connectionId = "alias", threadId = thread))

            NotificationRenderCoordinator(db, FakeReplyRenderer()) { _, _ -> }
                .adopt("alias", "canonical", NeedsYouEvent("canonical", "https://example", "workspace", thread, "subject", "", "source"))

            assertEquals(ReplyOutboxState.STALE, db.replyOutboxDao().get(key)!!.state)
            val canonical = db.replyNotificationVersionDao().get("canonical", thread)!!
            assertTrue(canonical.active)
            assertEquals("canonical-cap", canonical.capabilityKey)
            assertFalse(db.replyNotificationVersionDao().get("alias", thread)!!.active)
            db.close()
        }
    }

    @Test fun canonical_collision_retries_while_the_alias_reply_is_in_flight() = runBlocking {
        val db = database()
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "thread", "source#1", 1, true, "alias-cap"),
        )
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("canonical", "thread", "source#2", 2, true, "canonical-cap"),
        )
        db.replyOutboxDao().insert(
            row(ReplyOutboxState.IN_FLIGHT, key = "alias-cap", connectionId = "alias", threadId = "thread"),
        )

        assertEquals(
            ReplyCapabilityAdoption.RETRY,
            NotificationRenderCoordinator(db, FakeReplyRenderer()) { _, _ -> }
                .adopt(
                    "alias",
                    "canonical",
                    NeedsYouEvent("canonical", "https://example", "workspace", "thread", "subject", "", "source"),
                ),
        )

        assertEquals("alias", db.replyOutboxDao().get("alias-cap")!!.connectionId)
        assertTrue(db.replyNotificationVersionDao().get("alias", "thread")!!.active)
        db.close()
    }

    @Test fun adoption_cannot_resurrect_an_inactive_canonical_tombstone() = runBlocking {
        val db = database()
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "t", "old#1", 1, true, "alias-cap"),
        )
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("canonical", "t", "new#2", 2, false, null),
        )
        db.replyOutboxDao().insert(
            row(ReplyOutboxState.WAITING_FOR_CONNECTION, version = "old#1", key = "alias-cap", connectionId = "alias"),
        )

        NotificationRenderCoordinator(db, FakeReplyRenderer()) { _, _ -> }
            .adopt("alias", "canonical", NeedsYouEvent("canonical", "https://example", "workspace", "t", "subject", "", "old"))

        val canonical = db.replyNotificationVersionDao().get("canonical", "t")!!
        assertFalse(canonical.active)
        assertEquals("new#2", canonical.version)
        assertEquals(ReplyOutboxState.STALE, db.replyOutboxDao().get("alias-cap")!!.state)
        db.close()
    }

    @Test fun retired_alias_adoption_without_canonical_capability_does_not_crash() = runBlocking {
        val db = database()
        db.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "t", "source#1", 1, false, null),
        )

        NotificationRenderCoordinator(db, FakeReplyRenderer()) { _, _ -> }
            .adopt("alias", "canonical", NeedsYouEvent("canonical", "https://example", "workspace", "t", "subject", "", "source"))

        assertFalse(db.replyNotificationVersionDao().get("canonical", "t")?.active ?: false)
        db.close()
    }
}
