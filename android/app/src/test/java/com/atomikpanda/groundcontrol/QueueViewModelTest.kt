// app/src/test/java/com/atomikpanda/groundcontrol/QueueViewModelTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.queue.QueueUiState
import com.atomikpanda.groundcontrol.ui.queue.QueueViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    // ws-a: one needs_approval item; ws-b: none
    private fun repo() = QueueRepository(SpecApi(HttpClient(MockEngine { req ->
        val body = if (req.url.host == "a")
            """[{"id":"wi1","kind":"feature","title":"A","phase":"ready","spec_id":"s1","attention":{"needs_approval":true}}]"""
        else "[]"
        respond(body, HttpStatusCode.OK, jsonHdr)
    }) { mshipDefaults() }))

    private val conns = listOf(
        WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
        WorkspaceConnection("b", "http://b:47100", null, "ws-b"),
    )

    @Test fun no_connections_yields_empty_config() = runTest {
        val vm = QueueViewModel(repo(), { emptyList() }, this)
        vm.refresh(); advanceUntilIdle()
        assertEquals(QueueUiState.EmptyConfig, vm.state.value)
    }

    @Test fun loads_one_card_with_position_1_of_1() = runTest {
        val vm = QueueViewModel(repo(), { conns }, this)
        vm.refresh()?.join()
        val c = vm.state.value as QueueUiState.Content
        assertEquals("wi1", c.current!!.workItemId)
        assertEquals(1, c.position)
        assertEquals(1, c.total)
        assertTrue(!c.caughtUp)
    }

    @Test fun empty_queue_is_caught_up() = runTest {
        val vm = QueueViewModel(repo(), { listOf(WorkspaceConnection("b", "http://b:47100", null, "ws-b")) }, this)
        vm.refresh()?.join()
        val c = vm.state.value as QueueUiState.Content
        assertTrue(c.caughtUp)
        assertNull(c.current)
    }

    // ws-a: two needs_approval items -> two cards. Approve POSTs return a valid SpecReview so the
    // (success-only) advance in approveCurrent fires; /items returns the two-item feed.
    private fun repo2() = QueueRepository(SpecApi(HttpClient(MockEngine { req ->
        val body = if (req.url.encodedPath.endsWith("/approve"))
            """{"id":"s1","status":"approved"}"""
        else """[
          {"id":"wi1","kind":"feature","title":"A","phase":"ready","spec_id":"s1","updated_at":"2026-01-01T00:00:00Z","attention":{"needs_approval":true}},
          {"id":"wi2","kind":"feature","title":"B","phase":"ready","spec_id":"s2","updated_at":"2026-02-01T00:00:00Z","attention":{"needs_approval":true}}
        ]"""
        respond(body, HttpStatusCode.OK, jsonHdr)
    }) { mshipDefaults() }))
    private val one = listOf(WorkspaceConnection("a", "http://a:47100", null, "ws-a"))

    @Test fun approve_removes_head_and_advances() = runTest {
        val vm = QueueViewModel(repo2(), { one }, this)
        vm.refresh()?.join()
        val firstKey = (vm.state.value as QueueUiState.Content).current!!.key
        vm.approveCurrent()?.join()
        val c = vm.state.value as QueueUiState.Content
        assertEquals("wi2", c.current!!.workItemId)   // advanced to the older-tier-equal next
        assertEquals(2, c.total)                       // resolved(1) + remaining(1)
        assertEquals(2, c.position)                    // now on 2 of 2
        assertEquals(firstKey, c.undo!!.key)           // undo armed with the acted card
    }

    @Test fun undo_restores_the_acted_card_at_head() = runTest {
        val vm = QueueViewModel(repo2(), { one }, this)
        vm.refresh()?.join()
        val firstKey = (vm.state.value as QueueUiState.Content).current!!.key
        vm.approveCurrent()?.join()
        vm.undo()
        val c = vm.state.value as QueueUiState.Content
        assertEquals(firstKey, c.current!!.key)
        assertEquals(1, c.position)
        assertNull(c.undo)
    }

    @Test fun defer_sends_head_to_back_without_resolving() = runTest {
        val vm = QueueViewModel(repo2(), { one }, this)
        vm.refresh()?.join()
        val firstKey = (vm.state.value as QueueUiState.Content).current!!.key
        vm.defer()
        val c = vm.state.value as QueueUiState.Content
        assertEquals("wi2", c.current!!.workItemId)          // next is now current
        assertEquals(firstKey, c.cards.last().key)            // deferred card moved to back
        assertEquals(2, c.total)                              // nothing resolved
    }

    @Test fun open_advances_without_arming_undo_or_resolving_key() = runTest {
        val vm = QueueViewModel(repo2(), { one }, this)
        vm.refresh()?.join()
        vm.openCurrent()
        val c = vm.state.value as QueueUiState.Content
        assertEquals("wi2", c.current!!.workItemId)
        assertNull(c.undo)
    }

    @Test fun live_refresh_keeps_current_head_and_inserts_new_behind() = runTest {
        var round = 0
        val engine = MockEngine { _ ->
            round++
            val body = if (round <= 1)  // round 1 = the (single) workspace's first load
                """[{"id":"wi1","kind":"feature","title":"A","phase":"ready","spec_id":"s1","updated_at":"2026-01-01T00:00:00Z","attention":{"needs_approval":true}}]"""
            else  // later: a new, more-urgent blocked item appears
                """[
                  {"id":"wi1","kind":"feature","title":"A","phase":"ready","spec_id":"s1","updated_at":"2026-01-01T00:00:00Z","attention":{"needs_approval":true}},
                  {"id":"wi9","kind":"feature","title":"Z","phase":"review","updated_at":"2026-03-01T00:00:00Z","attention":{"blocked":true}}
                ]"""
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = QueueViewModel(QueueRepository(SpecApi(HttpClient(engine) { mshipDefaults() })), { one }, this)
        vm.refresh()?.join()
        val headBefore = (vm.state.value as QueueUiState.Content).current!!.key
        vm.refresh()?.join()   // live refresh brings in the blocked card
        val c = vm.state.value as QueueUiState.Content
        assertEquals(headBefore, c.current!!.key)              // focus NOT yanked, even though blocked is more urgent
        assertEquals(2, c.cards.size)
        assertTrue(c.cards.any { it.workItemId == "wi9" })     // new card inserted behind
    }

    @Test fun focused_decision_is_loaded_for_a_decision_head() = runTest {
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/items") -> respond(
                    """[{"id":"wi1","kind":"feature","title":"Q","phase":"in_flight","thread_ids":["t1"],"attention":{"needs_decision":true}}]""",
                    HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1") -> respond(
                    """{"id":"t1","subject":"Q","messages":[
                       {"id":"m1","role":"agent","text":"Pick one","kind":"decision","decision":{"options":["X","Y"],"recommended":0}}]}""",
                    HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = QueueViewModel(QueueRepository(SpecApi(HttpClient(engine) { mshipDefaults() })), { one }, this)
        vm.refresh()?.join()
        // maybeLoadDecision is a fire-and-forget load whose Ktor MockEngine call runs off the
        // virtual clock, so advanceUntilIdle alone races it (GC MockEngine fire-and-forget flake).
        // Pump the scheduler, then yield real time to the engine's IO thread, until it lands.
        var loaded = (vm.state.value as? QueueUiState.Content)?.focusedDecision
        var tries = 0
        while (loaded == null && tries < 300) {
            advanceUntilIdle()
            loaded = (vm.state.value as? QueueUiState.Content)?.focusedDecision
            if (loaded == null) { Thread.sleep(10); tries++ }
        }
        val c = vm.state.value as QueueUiState.Content
        assertEquals("Pick one", c.focusedDecision!!.text)
        assertEquals(listOf("X", "Y"), c.focusedDecision!!.decision.options)
    }

    // C1: a FAILED approve must not silently hide the card — it stays put and surfaces actionError.
    @Test fun approve_failure_keeps_card_and_sets_action_error() = runTest {
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/items") -> respond(
                    """[{"id":"wi1","kind":"feature","title":"A","phase":"ready","spec_id":"s1","attention":{"needs_approval":true}}]""",
                    HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/specs/s1/approve") -> respond(
                    "{}", HttpStatusCode.InternalServerError, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = QueueViewModel(QueueRepository(SpecApi(HttpClient(engine) { mshipDefaults() })), { one }, this)
        vm.refresh()?.join()
        val before = vm.state.value as QueueUiState.Content
        val cardKey = before.current!!.key
        val posBefore = before.position
        vm.approveCurrent()?.join()
        val after = vm.state.value as QueueUiState.Content
        assertEquals(cardKey, after.current!!.key)   // same card still at the head (NOT advanced)
        assertNotNull(after.actionError)             // failure surfaced
        assertEquals(posBefore, after.position)      // position indicator unchanged
        assertNull(after.undo)                       // undo NOT armed on a failed action
    }

    // C2: a decision head whose thread carries no structured decision must settle
    // decisionLoaded=true with a null prompt (drives the UI fallback, not an infinite spinner).
    @Test fun decision_without_structured_payload_settles_loaded_with_null_prompt() = runTest {
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/items") -> respond(
                    """[{"id":"wi1","kind":"feature","title":"Q","phase":"in_flight","thread_ids":["t1"],"attention":{"needs_decision":true}}]""",
                    HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1") -> respond(
                    """{"id":"t1","subject":"Q","messages":[{"id":"m1","role":"agent","text":"just a note","kind":"note"}]}""",
                    HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = QueueViewModel(QueueRepository(SpecApi(HttpClient(engine) { mshipDefaults() })), { one }, this)
        vm.refresh()?.join()
        // The decision load is fire-and-forget; after refresh().join() decisionLoaded is still false.
        // Pump the scheduler + yield real time to the engine's IO thread until it settles true
        // (GC MockEngine fire-and-forget flake — advanceUntilIdle alone races the off-clock call).
        var settled = (vm.state.value as? QueueUiState.Content)?.decisionLoaded ?: false
        var tries = 0
        while (!settled && tries < 300) {
            advanceUntilIdle()
            settled = (vm.state.value as? QueueUiState.Content)?.decisionLoaded ?: false
            if (!settled) { Thread.sleep(10); tries++ }
        }
        val c = vm.state.value as QueueUiState.Content
        assertTrue(c.decisionLoaded)      // finished loading (no infinite spinner)
        assertNull(c.focusedDecision)     // …but there was no structured decision to show
    }

    // C3: a deferred card stays pinned to the back across a live refresh (doesn't pop back to front).
    @Test fun defer_persists_across_refresh() = runTest {
        val vm = QueueViewModel(repo2(), { one }, this)
        vm.refresh()?.join()
        val deferredKey = (vm.state.value as QueueUiState.Content).current!!.key
        vm.defer()                         // send the head to the back
        vm.refresh()?.join()               // same MockEngine returns both items again
        val c = vm.state.value as QueueUiState.Content
        assertEquals(deferredKey, c.cards.last().key)     // deferred card kept at the back
        assertTrue(c.current!!.key != deferredKey)        // NOT yanked back to the front
    }
}
