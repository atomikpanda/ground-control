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

    // ws-a: two needs_approval items -> two cards
    private fun repo2() = QueueRepository(SpecApi(HttpClient(MockEngine { _ ->
        respond("""[
          {"id":"wi1","kind":"feature","title":"A","phase":"ready","spec_id":"s1","updated_at":"2026-01-01T00:00:00Z","attention":{"needs_approval":true}},
          {"id":"wi2","kind":"feature","title":"B","phase":"ready","spec_id":"s2","updated_at":"2026-02-01T00:00:00Z","attention":{"needs_approval":true}}
        ]""", HttpStatusCode.OK, jsonHdr)
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
}
