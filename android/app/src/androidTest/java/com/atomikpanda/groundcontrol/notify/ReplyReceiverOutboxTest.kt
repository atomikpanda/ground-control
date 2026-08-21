package com.atomikpanda.groundcontrol.notify

import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplyReceiverOutboxTest {
    @Test fun option_intake_preserves_exact_value_and_decision_context() {
        val intent = validIntent().putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "  exact Δ option  ")
            .putExtra(ReplyReceiver.EXTRA_DECISION, "{\"options\":[\"  exact Δ option  \"],\"allow_free_text\":false}")
        val submission = intent.toReplySubmission()!!
        assertEquals("  exact Δ option  ", submission.replyText)
        assertEquals(ReplyInputKind.OPTION, submission.inputKind)
        assertEquals("{\"options\":[\"  exact Δ option  \"],\"allow_free_text\":false}", submission.decisionJson)
    }

    @Test fun free_text_is_trimmed_and_forbidden_policy_rejects_it() {
        val allowed = validIntent().putExtra(ReplyReceiver.EXTRA_DECISION, "{\"options\":[\"A\"],\"allow_free_text\":true}")
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(ReplyReceiver.KEY_REPLY_TEXT).build()),
            allowed,
            Bundle().apply { putCharSequence(ReplyReceiver.KEY_REPLY_TEXT, "  exact Δ reply  ") },
        )
        assertEquals("exact Δ reply", allowed.toReplySubmission()!!.replyText)

        val forbidden = validIntent().putExtra(ReplyReceiver.EXTRA_DECISION, "{\"options\":[\"A\"],\"allow_free_text\":false}")
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(ReplyReceiver.KEY_REPLY_TEXT).build()),
            forbidden,
            Bundle().apply { putCharSequence(ReplyReceiver.KEY_REPLY_TEXT, "reply") },
        )
        assertNull(forbidden.toReplySubmission())
    }

    @Test fun missing_capability_or_invalid_option_is_rejected_before_persistence() {
        assertNull(Intent().putExtra(ReplyReceiver.EXTRA_CONN_ID, "c").toReplySubmission())
        val invalid = validIntent()
            .putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "B")
            .putExtra(ReplyReceiver.EXTRA_DECISION, "{\"options\":[\"A\"],\"allow_free_text\":false}")
        assertNull(invalid.toReplySubmission())
    }

    @Test fun one_byte_over_bounded_context_is_rejected() {
        val huge = "x".repeat(MAX_REPLY_CONTEXT_BYTES + 1)
        assertNull(validIntent().putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, huge).toReplySubmission())
    }

    @Test fun alias_intake_persists_canonical_identity_and_cancels_original_alias() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        val scheduled = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("canonical", "thread", "source#1", 1, true, "opaque-key"),
        )
        val outbox = ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace", legacyConnectionIds = listOf("alias"))) },
            { cancelled += it.connectionId },
        )
        val submission = validIntent().putExtra(ReplyReceiver.EXTRA_CONN_ID, "alias")
            .putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "A").toReplySubmission()!!
        assertEquals(true, outbox.submit(submission))
        assertEquals("canonical", database.replyOutboxDao().get("opaque-key")!!.connectionId)
        assertEquals(listOf("opaque-key"), scheduled)
        assertEquals(listOf("alias"), cancelled)
        assertEquals(false, outbox.submit(submission))
        database.close()
    }

    @Test fun missing_connection_persists_exact_reply_and_resumes_when_connection_returns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        val scheduled = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var connections = emptyList<WorkspaceConnection>()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("restored", "thread", "source#1", 1, true, "opaque-key"),
        )
        val outbox = ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { connections },
            { cancelled += it.connectionId },
        )
        val submission = validIntent().putExtra(ReplyReceiver.EXTRA_CONN_ID, "restored")
            .putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "exact reply").toReplySubmission()!!

        assertEquals(true, outbox.submit(submission))
        val waiting = database.replyOutboxDao().get("opaque-key")!!
        assertEquals(ReplyOutboxState.WAITING_FOR_CONNECTION, waiting.state)
        assertEquals("exact reply", waiting.replyText)
        assertEquals(listOf("restored"), cancelled)
        assertTrue(scheduled.isEmpty())

        connections = listOf(WorkspaceConnection("restored", "https://example", "token", "workspace"))
        outbox.reconcileEligible()

        assertEquals(ReplyOutboxState.READY, database.replyOutboxDao().get("opaque-key")!!.state)
        assertEquals(listOf("opaque-key"), scheduled)
        database.close()
    }

    @Test fun snapshot_failure_persists_valid_reply_as_waiting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("canonical", "thread", "source#1", 1, true, "opaque-key"),
        )
        val scheduled = mutableListOf<String>()
        val outbox = ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { throw java.io.IOException("connections unavailable") },
            {},
        )

        assertTrue(outbox.submit(validIntent().putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "exact reply").toReplySubmission()!!))
        assertEquals(ReplyOutboxState.WAITING_FOR_CONNECTION, database.replyOutboxDao().get("opaque-key")!!.state)
        assertEquals("exact reply", database.replyOutboxDao().get("opaque-key")!!.replyText)
        assertTrue(scheduled.isEmpty())
        database.close()
    }

    @Test fun stale_waiting_reply_terminalizes_after_connection_restores_a_newer_generation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        var connections = emptyList<WorkspaceConnection>()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("canonical", "thread", "source#1", 1, true, "opaque-key"),
        )
        val outbox = ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) = error("stale reply must not run") },
            { connections },
            {},
        )
        val submission = validIntent().putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "exact reply").toReplySubmission()!!
        assertTrue(outbox.submit(submission))

        connections = listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace"))
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("canonical", "thread", "source#2", 2, true, "new-cap"),
        )
        outbox.reconcileEligible()

        assertEquals(ReplyOutboxState.STALE, database.replyOutboxDao().get("opaque-key")!!.state)
        database.close()
    }

    @Test fun reconciliation_enqueues_waiting_current_generation_once_after_connection_returns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("canonical", "thread", "source#1", 1, true, "opaque-key"))
        database.replyOutboxDao().insert(
            ReplyOutboxRecord("opaque-key", "canonical", "thread", "source#1", ReplyOutboxState.WAITING_FOR_CONNECTION,
                null, null, null, null, "reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1),
        )
        val scheduled = mutableListOf<String>()
        ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) },
            {},
        ).reconcileEligible()
        assertEquals(ReplyOutboxState.READY, database.replyOutboxDao().get("opaque-key")!!.state)
        assertEquals(listOf("opaque-key"), scheduled)
        database.close()
    }

    @Test fun committed_ready_row_is_scheduled_after_process_death_before_enqueue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("canonical", "thread", "source#1", 1, true, "opaque-key"))
        database.replyOutboxDao().insert(
            ReplyOutboxRecord("opaque-key", "canonical", "thread", "source#1", ReplyOutboxState.READY,
                null, null, null, null, "reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1),
        )
        val scheduled = mutableListOf<String>()
        ReplyOutbox(database, object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) }, {}).reconcileEligible()
        assertEquals(listOf("opaque-key"), scheduled)
        database.close()
    }

    @Test fun startup_backstop_terminalizes_abandoned_in_flight_without_posting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyOutboxDao().insert(
            ReplyOutboxRecord("opaque-key", "canonical", "thread", "source#1", ReplyOutboxState.IN_FLIGHT,
                "dead-execution", 1, null, null, "reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1),
        )
        ReplyOutbox(database, object : ReplyWorkScheduler { override fun enqueue(actionKey: String) = Unit }, { emptyList() }, {})
            .reconcileEligible()
        assertEquals(ReplyOutboxState.UNCERTAIN_PENDING_RENDER, database.replyOutboxDao().get("opaque-key")!!.state)
        database.close()
    }

    @Test fun stale_canonical_generation_is_rejected_without_cancelling_current_notification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("canonical", "thread", "source#2", 2, true, "fresh-cap"))
        val cancelled = mutableListOf<String>()
        val outbox = ReplyOutbox(database, object : ReplyWorkScheduler { override fun enqueue(actionKey: String) = Unit },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) }, { cancelled += it.actionKey })
        val stale = validIntent().putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "A").toReplySubmission()!!
        assertEquals(false, outbox.submit(stale))
        assertTrue(cancelled.isEmpty())
        assertNull(database.replyOutboxDao().get("opaque-key"))
        database.close()
    }

    @Test fun retired_notification_capability_rejects_old_tap_and_cancels_visible_notification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("canonical", "thread", "source#1", 1, true, "opaque-key"))
        val cancelled = mutableListOf<String>()
        NotificationRenderCoordinator(database, object : ReplyNotificationRenderer {
            override fun renderCurrent(event: NeedsYouEvent, capability: ReplyCapability?, errorLine: String?) = true
        }) { c, t -> cancelled += "$c|$t" }.retire("canonical", "thread", "source")
        val outbox = ReplyOutbox(database, object : ReplyWorkScheduler { override fun enqueue(actionKey: String) = Unit },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) }, {})
        assertEquals(false, outbox.submit(validIntent().putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "A").toReplySubmission()!!))
        assertEquals(listOf("canonical|thread"), cancelled)
        database.close()
    }

    @Test fun alias_waiting_reply_does_not_resume_against_newer_canonical_generation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "thread", "source#1", 1, true, "old-cap"),
        )
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("canonical", "thread", "source#2", 2, true, "new-cap"),
        )
        database.replyOutboxDao().insert(
            ReplyOutboxRecord("old-cap", "alias", "thread", "source#1", ReplyOutboxState.WAITING_FOR_CONNECTION,
                null, null, null, null, "reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1),
        )
        val scheduled = mutableListOf<String>()
        ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace", legacyConnectionIds = listOf("alias"))) },
            {},
        ).reconcileEligible()
        assertEquals(ReplyOutboxState.STALE, database.replyOutboxDao().get("old-cap")!!.state)
        assertTrue(scheduled.isEmpty())
        database.close()
    }

    @Test fun alias_waiting_reply_survives_reconciliation_before_capability_adoption() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "thread", "source#1", 1, true, "opaque-key"),
        )
        database.replyOutboxDao().insert(
            ReplyOutboxRecord(
                "opaque-key", "alias", "thread", "source#1", ReplyOutboxState.WAITING_FOR_CONNECTION,
                null, null, null, null, "exact reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1,
            ),
        )
        val scheduled = mutableListOf<String>()
        val outbox = ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace", legacyConnectionIds = listOf("alias"))) },
            {},
        )

        outbox.reconcileEligible()
        assertEquals(ReplyOutboxState.WAITING_FOR_CONNECTION, database.replyOutboxDao().get("opaque-key")!!.state)
        assertTrue(scheduled.isEmpty())

        NotificationRenderCoordinator(database, object : ReplyNotificationRenderer {
            override fun renderCurrent(event: NeedsYouEvent, capability: ReplyCapability?, errorLine: String?) = true
        }) { _, _ -> }.adopt(
            "alias",
            "canonical",
            NeedsYouEvent("canonical", "https://example", "workspace", "thread", "subject", "", "source"),
        )
        outbox.reconcileEligible()

        assertEquals(ReplyOutboxState.READY, database.replyOutboxDao().get("opaque-key")!!.state)
        assertEquals(listOf("opaque-key"), scheduled)
        database.close()
    }

    @Test fun alias_version_adoption_accepts_old_visible_action_as_one_canonical_submission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("alias", "thread", "source#1", 1, true, "opaque-key"))
        val renderer = object : ReplyNotificationRenderer {
            override fun renderCurrent(event: NeedsYouEvent, capability: ReplyCapability?, errorLine: String?) = true
        }
        NotificationRenderCoordinator(database, renderer) { _, _ -> }
            .adopt("alias", "canonical", NeedsYouEvent("canonical", "https://example", "workspace", "thread", "subject", "", "source"))
        val scheduled = mutableListOf<String>()
        val outbox = ReplyOutbox(database, object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace", legacyConnectionIds = listOf("alias"))) }, {})
        val tap = validIntent().putExtra(ReplyReceiver.EXTRA_CONN_ID, "alias").putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "A").toReplySubmission()!!
        assertEquals(true, outbox.submit(tap))
        assertEquals("canonical", database.replyOutboxDao().get("opaque-key")!!.connectionId)
        assertEquals(listOf("opaque-key"), scheduled)
        database.close()
    }

    @Test fun alias_tap_before_capability_adoption_persists_one_canonical_submission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "thread", "source#1", 1, true, "opaque-key"),
        )
        val scheduled = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val outbox = ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace", legacyConnectionIds = listOf("alias"))) },
            { cancelled += it.connectionId },
        )
        val tap = validIntent().putExtra(ReplyReceiver.EXTRA_CONN_ID, "alias")
            .putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "A").toReplySubmission()!!

        assertTrue(outbox.submit(tap))
        assertEquals("canonical", database.replyOutboxDao().get("opaque-key")!!.connectionId)
        assertEquals(listOf("opaque-key"), scheduled)
        assertEquals(listOf("alias"), cancelled)
        database.close()
    }


    @Test fun adopted_waiting_reply_matches_canonical_generation_and_resumes_once() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("alias", "thread", "source#1", 1, true, "opaque-key"),
        )
        database.replyOutboxDao().insert(
            ReplyOutboxRecord(
                "opaque-key", "alias", "thread", "source#1", ReplyOutboxState.WAITING_FOR_CONNECTION,
                null, null, null, null, "exact reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1,
            ),
        )
        NotificationRenderCoordinator(database, object : ReplyNotificationRenderer {
            override fun renderCurrent(event: NeedsYouEvent, capability: ReplyCapability?, errorLine: String?) = true
        }) { _, _ -> }.adopt(
            "alias",
            "canonical",
            NeedsYouEvent("canonical", "https://example", "workspace", "thread", "subject", "", "source"),
        )
        val scheduled = mutableListOf<String>()
        val outbox = ReplyOutbox(
            database,
            object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) },
            {},
        )

        outbox.reconcileEligible()
        outbox.reconcileEligible()

        val row = database.replyOutboxDao().get("opaque-key")!!
        assertEquals("canonical", row.connectionId)
        assertEquals(ReplyOutboxState.READY, row.state)
        assertEquals(listOf("opaque-key", "opaque-key"), scheduled)
        database.close()
    }

    @Test fun restarted_same_execution_terminalizes_uncertain_without_reposting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(
            ReplyNotificationVersionRecord("canonical", "thread", "source#1", 1, true, "opaque-key"),
        )
        database.replyOutboxDao().insert(
            ReplyOutboxRecord(
                "opaque-key", "canonical", "thread", "source#1", ReplyOutboxState.IN_FLIGHT,
                "same-execution", 1, null, null, "exact reply", ReplyInputKind.FREE_TEXT,
                "", "", "", null, 0, 1,
            ),
        )
        var posts = 1 // The first process may have posted before dying.
        val rendered = mutableListOf<String>()
        ReplyExecutor(
            RoomReplyOutboxStore(database),
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) },
            { _, _ ->
                posts += 1
                error("restart must not repeat the side effect")
            },
            { rendered += it },
        ).execute("opaque-key", "same-execution")

        assertEquals(1, posts)
        assertEquals(ReplyOutboxState.UNCERTAIN_PENDING_RENDER, database.replyOutboxDao().get("opaque-key")!!.state)
        assertEquals(listOf("opaque-key"), rendered)
        database.close()
    }
    @Test fun stale_ready_generation_is_terminalized_and_not_reenqueued() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("canonical", "thread", "source#2", 2, true, "new-cap"))
        database.replyOutboxDao().insert(ReplyOutboxRecord("old-cap", "canonical", "thread", "source#1", ReplyOutboxState.READY,
            null, null, null, null, "reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1))
        ReplyExecutor(RoomReplyOutboxStore(database), { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) }, { _, _ -> error("must not post") }, {})
            .execute("old-cap", "execution")
        val scheduled = mutableListOf<String>()
        val outbox = ReplyOutbox(database, object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) }, {})
        outbox.reconcileEligible(); outbox.reconcileEligible()
        assertEquals(ReplyOutboxState.STALE, database.replyOutboxDao().get("old-cap")!!.state)
        assertTrue(scheduled.isEmpty())
        database.close()
    }

    @Test fun stale_ready_without_connection_becomes_stale_and_two_reconciles_do_not_enqueue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyNotificationVersionDao().insert(ReplyNotificationVersionRecord("canonical", "thread", "source#2", 2, true, "new-cap"))
        database.replyOutboxDao().insert(ReplyOutboxRecord("old-cap", "canonical", "thread", "source#1", ReplyOutboxState.READY,
            null, null, null, null, "reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1))
        ReplyExecutor(RoomReplyOutboxStore(database), { emptyList() }, { _, _ -> error("must not post") }, {}).execute("old-cap", "execution")
        val scheduled = mutableListOf<String>()
        val outbox = ReplyOutbox(database, object : ReplyWorkScheduler { override fun enqueue(actionKey: String) { scheduled += actionKey } },
            { emptyList() }, {})
        outbox.reconcileEligible(); outbox.reconcileEligible()
        assertEquals(ReplyOutboxState.STALE, database.replyOutboxDao().get("old-cap")!!.state)
        assertTrue(scheduled.isEmpty())
        database.close()
    }

    @Test fun reconciliation_does_not_terminalize_a_live_workmanager_claim() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        database.replyOutboxDao().insert(
            ReplyOutboxRecord("opaque-key", "canonical", "thread", "source#1", ReplyOutboxState.IN_FLIGHT,
                "live-execution", 1, null, null, "reply", ReplyInputKind.FREE_TEXT, "", "", "", null, 0, 1),
        )
        ReplyOutbox(database, object : ReplyWorkScheduler {
            override fun enqueue(actionKey: String) = Unit
            override fun isExecutionActive(executionId: String) = executionId == "live-execution"
        }, { emptyList() }, {}).reconcileEligible()
        assertEquals(ReplyOutboxState.IN_FLIGHT, database.replyOutboxDao().get("opaque-key")!!.state)
        database.close()
    }

    @Test fun receiver_returns_from_main_thread_while_room_commit_and_enqueue_are_gated() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NotifiedDatabase::class.java).build()
        runBlocking {
            database.replyNotificationVersionDao().insert(
                ReplyNotificationVersionRecord("canonical", "thread", "source#1", 1, true, "opaque-key"),
            )
        }
        val enqueueReached = CountDownLatch(1)
        val releaseEnqueue = CountDownLatch(1)

        val outbox = ReplyOutbox(
            database,
            object : ReplyWorkScheduler {
                override fun enqueue(actionKey: String) {
                    enqueueReached.countDown()
                    releaseEnqueue.await(5, TimeUnit.SECONDS)
                }
            },
            { listOf(WorkspaceConnection("canonical", "https://example", "token", "workspace")) },
            {},
        )
        val receiver = ReplyReceiver({ ReplyOutboxIntake(outbox) }, CoroutineScope(Dispatchers.IO))
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            receiver.onReceive(context, validIntent().putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "A"))
        }
        assertTrue(enqueueReached.await(5, TimeUnit.SECONDS))
        assertEquals(1L, releaseEnqueue.count)
        releaseEnqueue.countDown()
        database.close()
    }
    @Test fun receiver_waits_for_migration_reset_before_submitting_an_action() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val submitted = CompletableDeferred<Unit>()
        val receiver = ReplyReceiver(
            intakeFactory = {
                object : ReplyIntake {
                    override suspend fun submit(intent: Intent): Boolean {
                        submitted.complete(Unit)
                        return true
                    }
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        ReplyStartupGate.beginReset()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            receiver.onReceive(context, validIntent().putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, "A"))
        }

        assertFalse(submitted.isCompleted)
        ReplyStartupGate.finishReset()
        submitted.await()
    }

    private fun validIntent() = Intent().apply {
        putExtra(ReplyReceiver.EXTRA_CONN_ID, "canonical")
        putExtra(ReplyReceiver.EXTRA_THREAD_ID, "thread")
        putExtra(ReplyReceiver.EXTRA_NOTIFICATION_VERSION, "source#1")
        putExtra(ReplyReceiver.EXTRA_REPLY_CAPABILITY, "opaque-key")
    }
}
