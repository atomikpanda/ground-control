package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.messages.MessageConnectionOwner
import com.atomikpanda.groundcontrol.ui.messages.MessageFullLoad
import com.atomikpanda.groundcontrol.ui.messages.MessageConnectionSnapshot
import com.atomikpanda.groundcontrol.ui.messages.MessagePollDelta
import com.atomikpanda.groundcontrol.ui.messages.MessageRequestToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageConnectionOwnerTest {
    private val connection = WorkspaceConnection("one", "http://one", workspaceName = "one")
    private val replacement = WorkspaceConnection("one", "http://replacement", workspaceName = "one")

    private fun thread(id: String, updatedAt: String = "2026-08-21T00:00:00Z") =
        ThreadSummary(id = id, subject = id, updatedAt = updatedAt)

    private fun owner(scope: CoroutineScope) = MessageConnectionOwner(
        connection = connection,
        fullLoad = { awaitCancellation() },
        poll = { _, _ -> awaitCancellation() },
        scope = scope,
        cursorClock = { "clock-cursor" },
        retryDelay = { awaitCancellation() },
    )

    @Test fun newer_refresh_wins_when_completions_reverse() = runTest {
        val owner = owner(backgroundScope)
        val first = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        val second = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        owner.completeForTest(second, Result.success(MessageFullLoad(listOf(thread("new")), emptyList())))
        owner.completeForTest(first, Result.success(MessageFullLoad(listOf(thread("old")), emptyList())))
        assertEquals(listOf("new"), owner.snapshot.value.threads.getOrThrow().map { it.id })
    }

    @Test fun initial_load_does_not_supersede_an_active_refresh() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var loads = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                loads += 1
                if (loads == 1) {
                    refreshStarted.complete(Unit)
                    withContext(NonCancellable) { releaseRefresh.await() }
                    MessageFullLoad(listOf(thread("refresh")), emptyList())
                } else {
                    MessageFullLoad(listOf(thread("initial")), emptyList())
                }
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )

        val refresh = owner.refresh()
        refreshStarted.await()
        val initial = owner.initialLoad()
        runCurrent()
        releaseRefresh.complete(Unit)
        refresh.join()
        initial.join()

        assertEquals(1, loads)
        assertEquals(listOf("refresh"), owner.snapshot.value.threads.getOrThrow().map { it.id })
    }

    @Test fun newer_failure_still_fences_older_completion() = runTest {
        val owner = owner(backgroundScope)
        val first = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        val second = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        owner.completeForTest(second, Result.failure(IOException("offline")))
        owner.completeForTest(first, Result.success(MessageFullLoad(listOf(thread("stale")), emptyList())))
        assertEquals(MessageConnectionSnapshot.Phase.INITIAL_ERROR, owner.snapshot.value.phase)
        assertTrue(owner.snapshot.value.threads.isFailure)
    }

    @Test fun stale_generation_commits_neither_threads_nor_cursor() = runTest {
        val owner = owner(backgroundScope)
        val token = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.handoffTo(replacement)
        owner.completeForTest(token, Result.success(MessagePollDelta(listOf(thread("stale")), "stale-cursor")))
        assertEquals(emptyList<ThreadSummary>(), owner.snapshot.value.threads.getOrThrow())
        assertEquals("", owner.snapshot.value.cursor)
    }

    @Test fun accepted_poll_commits_changed_threads_and_cursor_together() = runTest {
        val owner = owner(backgroundScope)
        val initial = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(initial, Result.success(MessageFullLoad(listOf(thread("old")), emptyList())))
        val poll = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.completeForTest(poll, Result.success(MessagePollDelta(listOf(thread("new", "2026-08-22T00:00:00Z")), "next")))
        assertEquals(listOf("new", "old"), owner.snapshot.value.threads.getOrThrow().map { it.id })
        assertEquals("next", owner.snapshot.value.cursor)
    }

    @Test fun blank_poll_cursor_preserves_accepted_cursor_while_merging_changes() = runTest {
        val owner = owner(backgroundScope)
        val initial = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(initial, Result.success(MessageFullLoad(listOf(thread("old")), emptyList())))
        val poll = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.completeForTest(poll, Result.success(MessagePollDelta(listOf(thread("changed")), "cursor")))
        val timeout = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.completeForTest(timeout, Result.success(MessagePollDelta(listOf(thread("changed-again")), "")))
        assertEquals("cursor", owner.snapshot.value.cursor)
        assertEquals(setOf("old", "changed", "changed-again"), owner.snapshot.value.threads.getOrThrow().map { it.id }.toSet())
    }

    @Test fun handoff_clears_replaced_workspace_state_and_rejects_old_poll() = runTest {
        val owner = owner(backgroundScope)
        val initial = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(initial, Result.success(MessageFullLoad(listOf(thread("accepted")), emptyList())))
        val cursor = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.completeForTest(cursor, Result.success(MessagePollDelta(emptyList(), "cursor-7")))
        val stale = owner.beginForTest(MessageRequestToken.Kind.POLL)
        val receipt = requireNotNull(owner.handoffTo(replacement))
        owner.completeForTest(stale, Result.success(MessagePollDelta(listOf(thread("stale")), "cursor-8")))
        assertEquals("", owner.snapshot.value.cursor)
        assertEquals(emptyList<ThreadSummary>(), owner.snapshot.value.threads.getOrThrow())
        assertEquals(replacement, owner.snapshot.value.connection)
        owner.resumeAfterHandoff(receipt)
    }

    @Test fun authoritative_refresh_removes_threads_missing_from_the_server_snapshot() = runTest {
        val owner = owner(backgroundScope)
        val initial = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(initial, Result.success(MessageFullLoad(listOf(thread("one"), thread("deleted")), emptyList())))
        val refresh = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        owner.completeForTest(refresh, Result.success(MessageFullLoad(listOf(thread("one")), emptyList())))
        assertEquals(listOf("one"), owner.snapshot.value.threads.getOrThrow().map { it.id })
    }

    @Test fun failed_poll_preserves_the_last_accepted_snapshot() = runTest {
        val owner = owner(backgroundScope)
        val initial = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(initial, Result.success(MessageFullLoad(listOf(thread("accepted")), emptyList())))
        val poll = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.completeForTest(poll, Result.success(MessagePollDelta(emptyList(), "accepted-cursor")))
        val failed = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.completeForTest(failed, Result.failure(IOException("offline")))
        assertEquals(listOf("accepted"), owner.snapshot.value.threads.getOrThrow().map { it.id })
        assertEquals("accepted-cursor", owner.snapshot.value.cursor)
    }

    @Test fun one_shot_poll_failure_does_not_start_a_retry_loop() = runTest {
        var retryStarts = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = { awaitCancellation() },
            poll = { _, _ -> throw IOException("offline") },
            scope = backgroundScope,
            retryDelay = {
                retryStarts += 1
                awaitCancellation()
            },
        )

        owner.pollOnceForTest("cursor")
        runCurrent()

        assertEquals(0, retryStarts)
    }

    @Test fun one_shot_refresh_failure_does_not_start_a_retry_loop() = runTest {
        var retryStarts = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = { awaitCancellation() },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
            retryDelay = {
                retryStarts += 1
                awaitCancellation()
            },
        )
        val refresh = owner.beginForTest(MessageRequestToken.Kind.REFRESH)

        owner.completeForTest(refresh, Result.failure(IOException("offline")))
        runCurrent()

        assertEquals(0, retryStarts)
    }

    @Test fun initial_empty_snapshot_uses_the_injected_clock_cursor() = runTest {
        val owner = owner(backgroundScope)
        val initial = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(initial, Result.success(MessageFullLoad(emptyList(), emptyList())))
        assertEquals("clock-cursor", owner.snapshot.value.cursor)
    }

    @Test fun cancellation_fences_a_non_cooperative_completion() = runTest {
        val owner = owner(backgroundScope)
        val token = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        owner.cancel()
        owner.completeForTest(token, Result.success(MessageFullLoad(listOf(thread("stale")), emptyList())))
        assertFalse(owner.snapshot.value.threads.getOrThrow().any { it.id == "stale" })
    }

    @Test fun cancelling_retry_prevents_stale_restart_while_another_owner_progresses() = runTest {
        val retryGate = Channel<Unit>(Channel.UNLIMITED)
        var failedStarts = 0
        val failing = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                failedStarts += 1
                throw IOException("offline")
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
            retryDelay = { retryGate.receive() },
        )
        val healthy = MessageConnectionOwner(
            connection = WorkspaceConnection("two", "http://two"),
            fullLoad = { MessageFullLoad(listOf(thread("healthy")), emptyList()) },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )

        failing.initialLoad()
        runCurrent()
        healthy.initialLoad()
        runCurrent()
        assertEquals(MessageConnectionSnapshot.Phase.READY, healthy.snapshot.value.phase)

        failing.cancel()
        retryGate.trySend(Unit)
        runCurrent()
        assertEquals(1, failedStarts)
    }

    @Test fun same_id_replacement_clears_old_payload_and_reloads_the_new_connection() = runTest {
        var replacementLoads = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                replacementLoads += 1
                MessageFullLoad(listOf(thread("replacement")), emptyList())
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )
        val accepted = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(accepted, Result.success(MessageFullLoad(listOf(thread("old")), emptyList())))
        val receipt = requireNotNull(owner.handoffTo(replacement))
        assertEquals(emptyList<ThreadSummary>(), owner.snapshot.value.threads.getOrThrow())
        assertEquals("", owner.snapshot.value.cursor)
        assertEquals(MessageConnectionSnapshot.Phase.INITIAL_LOADING, owner.snapshot.value.phase)
        owner.resumeAfterHandoff(receipt)
        runCurrent()
        assertEquals(1, replacementLoads)
        assertEquals(listOf("replacement"), owner.snapshot.value.threads.getOrThrow().map { it.id })
    }

    @Test fun unchanged_poll_cursor_waits_for_retry_before_issuing_another_poll() = runTest {
        val retryGate = Channel<Unit>(Channel.UNLIMITED)
        var polls = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = { MessageFullLoad(emptyList(), emptyList()) },
            poll = { _, _ ->
                polls += 1
                MessagePollDelta(emptyList(), "")
            },
            scope = backgroundScope,
            retryDelay = { retryGate.receive() },
        )
        owner.initialLoad()
        runCurrent()
        owner.startPolling()
        runCurrent()
        assertEquals(1, polls)
        retryGate.trySend(Unit)
        runCurrent()
        assertEquals(2, polls)
    }

    @Test fun handoff_waits_for_a_cancelled_noncooperative_request_before_returning() = runTest {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                attempts += 1
                if (attempts == 1) withContext(NonCancellable) { release.await() }
                MessageFullLoad(emptyList(), emptyList())
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )
        owner.initialLoad()
        runCurrent()
        owner.refresh()
        runCurrent()
        val handoff = backgroundScope.async { owner.handoffTo(replacement) }
        runCurrent()
        assertFalse(handoff.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertTrue(handoff.isCompleted)
    }

    @Test fun superseded_refresh_waits_for_the_authoritative_replacement_load() = runTest {
        val replacementLoad = CompletableDeferred<Unit>()
        var loads = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                loads += 1
                if (loads == 1) awaitCancellation() else {
                    replacementLoad.await()
                    MessageFullLoad(listOf(thread("replacement")), emptyList())
                }
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )

        val superseded = owner.refresh()
        runCurrent()
        val authoritative = owner.refresh()
        runCurrent()

        assertFalse(superseded.isCompleted)
        replacementLoad.complete(Unit)
        runCurrent()
        superseded.join()
        authoritative.join()
        assertEquals(listOf("replacement"), owner.snapshot.value.threads.getOrThrow().map { it.id })
    }

    @Test fun refresh_queued_during_handoff_runs_as_the_replacement_full_load() = runTest {
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                loads += 1
                if (loads == 1) withContext(NonCancellable) { release.await() }
                MessageFullLoad(listOf(thread("load-$loads")), emptyList())
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )
        val accepted = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(accepted, Result.success(MessageFullLoad(listOf(thread("accepted")), emptyList())))
        owner.refresh()
        runCurrent()
        val handoff = backgroundScope.async { requireNotNull(owner.handoffTo(replacement)) }
        runCurrent()
        val queuedRefresh = owner.refresh()
        release.complete(Unit)
        runCurrent()
        owner.resumeAfterHandoff(handoff.await())
        runCurrent()
        queuedRefresh.join()
        assertEquals(2, loads)
        assertEquals(listOf("load-2"), owner.snapshot.value.threads.getOrThrow().map { it.id })

    }
    @Test fun queued_refresh_waits_through_every_superseding_refresh_until_authoritative_snapshot_publishes() = runTest {
        val handoffRelease = CompletableDeferred<Unit>()
        val replacementRefreshStarted = CompletableDeferred<Unit>()
        val authoritativeRelease = CompletableDeferred<Unit>()
        var loads = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                loads += 1
                when (loads) {
                    1 -> {
                        withContext(NonCancellable) { handoffRelease.await() }
                        MessageFullLoad(listOf(thread("obsolete")), emptyList())
                    }
                    2 -> {
                        replacementRefreshStarted.complete(Unit)
                        awaitCancellation()
                    }
                    else -> {
                        authoritativeRelease.await()
                        MessageFullLoad(listOf(thread("authoritative")), emptyList())
                    }
                }
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )

        owner.refresh()
        runCurrent()
        val handoff = backgroundScope.async { requireNotNull(owner.handoffTo(replacement)) }
        runCurrent()
        val queuedRefresh = owner.refresh()
        handoffRelease.complete(Unit)
        runCurrent()
        owner.resumeAfterHandoff(handoff.await())
        runCurrent()
        replacementRefreshStarted.await()

        val authoritativeRefresh = owner.refresh()
        runCurrent()

        assertFalse(queuedRefresh.isCompleted)
        assertFalse(authoritativeRefresh.isCompleted)
        authoritativeRelease.complete(Unit)
        runCurrent()
        queuedRefresh.join()
        authoritativeRefresh.join()
        assertEquals(listOf("authoritative"), owner.snapshot.value.threads.getOrThrow().map { it.id })
    }


    @Test fun idle_alias_handoff_retains_ready_state_without_starting_an_initial_load() = runTest {
        var fullLoads = 0
        val adopted = WorkspaceConnection("canonical", "http://canonical")
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                fullLoads += 1
                MessageFullLoad(emptyList(), emptyList())
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )
        val initial = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(initial, Result.success(MessageFullLoad(listOf(thread("accepted")), emptyList())))
        val cursor = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.completeForTest(cursor, Result.success(MessagePollDelta(emptyList(), "cursor-7")))
        val receipt = requireNotNull(owner.handoffTo(adopted))
        owner.resumeAfterHandoff(receipt)
        runCurrent()
        assertEquals(0, fullLoads)
        assertEquals(listOf("accepted"), owner.snapshot.value.threads.getOrThrow().map { it.id })
        assertEquals("cursor-7", owner.snapshot.value.cursor)
    }

    @Test fun refresh_failure_while_initial_loading_publishes_initial_error() = runTest {
        val owner = owner(backgroundScope)
        val refresh = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        owner.completeForTest(refresh, Result.failure(IOException("offline")))
        assertEquals(MessageConnectionSnapshot.Phase.INITIAL_ERROR, owner.snapshot.value.phase)
        assertTrue(owner.snapshot.value.threads.isFailure)
    }

    @Test fun queued_refresh_completes_when_removal_cancels_the_handoff() = runTest {
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val owner = MessageConnectionOwner(
            connection = connection,
            fullLoad = {
                loads += 1
                if (loads == 1) withContext(NonCancellable) { release.await() }
                MessageFullLoad(emptyList(), emptyList())
            },
            poll = { _, _ -> awaitCancellation() },
            scope = backgroundScope,
        )
        owner.refresh()
        runCurrent()
        backgroundScope.async { owner.handoffTo(replacement) }
        runCurrent()
        val queued = owner.refresh()
        val removal = backgroundScope.async { owner.cancel() }
        release.complete(Unit)
        runCurrent()
        removal.await()
        queued.join()
        assertEquals(1, loads)
    }

    @Test fun refresh_after_owner_cancellation_completes_without_waiting_for_an_unqueued_handoff() = runTest {
        val owner = owner(backgroundScope)
        owner.cancel()

        val refresh = owner.refresh()
        runCurrent()

        assertTrue(refresh.isCompleted)
        assertFalse(refresh.isCancelled)
    }
    }
