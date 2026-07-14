// app/src/test/java/com/atomikpanda/groundcontrol/QueueViewModelTest.kt
//
// PR2 smoke coverage for the Queue v2 card stack: the generic head-stable machinery
// (load / position / defer-to-back / approve-advance) against the specs+threads
// sourcing. The full v2 transitions — approve-all, reject-with-comment, per-item
// verdicts, auto-approve — and their exhaustive tests land in PR3 (Task 11).
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.queue.ProseCard
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    // ws-a: one needs_review spec (s1) whose body has two prose sections → two prose cards,
    // and no decision threads. ws-b: empty. Approve POSTs return a valid SpecReview so the
    // success-only advance in approveCurrent fires.
    private fun repo() = QueueRepository(SpecApi(HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val body = when {
            path.endsWith("/approve") -> """{"id":"s1","status":"approved"}"""
            path.endsWith("/specs") -> if (req.url.host == "a")
                """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]"
            path.endsWith("/threads") -> "[]"
            path.contains("/specs/") ->
                """{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","updated_at":"2026-01-01T00:00:00Z"}"""
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, jsonHdr)
    }) { mshipDefaults() }))

    private val conns = listOf(
        WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
        WorkspaceConnection("b", "http://b:47100", null, "ws-b"),
    )
    private val onlyEmpty = listOf(WorkspaceConnection("b", "http://b:47100", null, "ws-b"))

    @Test fun no_connections_yields_empty_config() = runTest {
        val vm = QueueViewModel(repo(), { emptyList() }, this)
        vm.refresh()
        assertEquals(QueueUiState.EmptyConfig, vm.state.value)
    }

    @Test fun loads_prose_cards_head_first_with_position() = runTest {
        val vm = QueueViewModel(repo(), { conns }, this)
        vm.refresh()?.join()
        val c = vm.state.value as QueueUiState.Content
        val head = c.current as ProseCard
        assertEquals("s1", head.specId)
        assertEquals("problem", head.sectionId)
        assertEquals(1, c.position)
        assertEquals(2, c.total)
        assertTrue(!c.caughtUp)
    }

    @Test fun empty_queue_is_caught_up() = runTest {
        val vm = QueueViewModel(repo(), { onlyEmpty }, this)
        vm.refresh()?.join()
        val c = vm.state.value as QueueUiState.Content
        assertTrue(c.caughtUp)
        assertNull(c.current)
    }

    @Test fun defer_sends_head_to_the_back() = runTest {
        val vm = QueueViewModel(repo(), { conns }, this)
        vm.refresh()?.join()
        vm.defer()
        val c = vm.state.value as QueueUiState.Content
        assertEquals("approach", (c.current as ProseCard).sectionId)   // advanced to the next section
        assertTrue(c.cards.filterIsInstance<ProseCard>().any { it.sectionId == "problem" })  // deferred to back
    }

    @Test fun approve_advances_past_the_head() = runTest {
        val vm = QueueViewModel(repo(), { conns }, this)
        vm.refresh()?.join()
        vm.approveCurrent()?.join()
        val c = vm.state.value as QueueUiState.Content
        assertEquals(1, c.resolved)
        assertEquals("approach", (c.current as ProseCard).sectionId)
    }
}
