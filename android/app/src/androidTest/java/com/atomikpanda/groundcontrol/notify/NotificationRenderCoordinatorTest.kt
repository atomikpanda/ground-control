package com.atomikpanda.groundcontrol.notify

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
    override fun renderCurrent(event: NeedsYouEvent, capability: ReplyCapability?, errorLine: String?): Boolean {
        renders += capability
        if (throws) throw IllegalStateException("notifier failure")
        return succeeds
    }
}

class NotificationRenderCoordinatorTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<android.content.Context>(), NotifiedDatabase::class.java,
    ).build()

    private fun row(state: ReplyOutboxState, version: String = "source#1", key: String = "cap") = ReplyOutboxRecord(
        key, "c", "t", version, state, null, null, null, null, "reply", ReplyInputKind.FREE_TEXT,
        "subject", "workspace", "https://example", null, 0, 1,
    )

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
}
