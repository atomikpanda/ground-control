package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.messages.MessageConnectionOwner
import com.atomikpanda.groundcontrol.ui.messages.MessageFullLoad
import com.atomikpanda.groundcontrol.ui.messages.MessageConnectionSnapshot
import com.atomikpanda.groundcontrol.ui.messages.MessagePollDelta
import com.atomikpanda.groundcontrol.ui.messages.MessageRequestToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun newer_failure_still_fences_older_completion() = runTest {
        val owner = owner(backgroundScope)
        val first = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        val second = owner.beginForTest(MessageRequestToken.Kind.REFRESH)
        owner.completeForTest(second, Result.failure(IOException("offline")))
        owner.completeForTest(first, Result.success(MessageFullLoad(listOf(thread("stale")), emptyList())))
        assertFalse(owner.snapshot.value.threads.getOrThrow().any { it.id == "stale" })
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

    @Test fun handoff_preserves_accepted_state_and_rejects_old_poll() = runTest {
        val owner = owner(backgroundScope)
        val initial = owner.beginForTest(MessageRequestToken.Kind.INITIAL)
        owner.completeForTest(initial, Result.success(MessageFullLoad(listOf(thread("accepted")), emptyList())))
        val cursor = owner.beginForTest(MessageRequestToken.Kind.POLL)
        owner.completeForTest(cursor, Result.success(MessagePollDelta(emptyList(), "cursor-7")))
        val stale = owner.beginForTest(MessageRequestToken.Kind.POLL)
        val receipt = requireNotNull(owner.handoffTo(replacement))
        owner.completeForTest(stale, Result.success(MessagePollDelta(listOf(thread("stale")), "cursor-8")))
        assertEquals("cursor-7", owner.snapshot.value.cursor)
        assertEquals(listOf("accepted"), owner.snapshot.value.threads.getOrThrow().map { it.id })
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
}
