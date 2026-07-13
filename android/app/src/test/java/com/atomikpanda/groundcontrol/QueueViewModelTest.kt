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
}
