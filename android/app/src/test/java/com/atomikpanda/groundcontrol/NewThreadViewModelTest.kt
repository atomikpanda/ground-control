package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.ui.messages.NewThreadViewModel
import com.atomikpanda.groundcontrol.ui.messages.NewThreadMessage
import com.atomikpanda.groundcontrol.ui.messages.canCreate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewThreadViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun conn(id: String) = WorkspaceConnection(id, "http://h-$id:47100", "tok", "ws-$id")

    private fun vm(
        scope: CoroutineScope,
        conns: List<WorkspaceConnection>,
        handler: MockRequestHandler = {
            respond(
                """{"id":"new-thread-1","subject":"Subject","awaiting_reply":false,"messages":[]}""",
                HttpStatusCode.OK, jsonHdr,
            )
        },
    ): NewThreadViewModel =
        NewThreadViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() })),
            connectionsProvider = { conns },
            testScope = scope,
        )

    @Test fun no_connections_empty_state_blocks_create() = runTest {
        val vm = vm(this, emptyList()); vm.load()
        val s = vm.state.value
        assertTrue(s.connections.isEmpty())
        assertNull(s.selectedConnectionId)
        assertFalse(canCreate(s.copy(text = "hello")))
    }

    @Test fun single_connection_auto_selected() = runTest {
        val vm = vm(this, listOf(conn("1"))); vm.load()
        assertEquals("1", vm.state.value.selectedConnectionId)
    }

    @Test fun multi_connection_requires_explicit_pick() = runTest {
        val vm = vm(this, listOf(conn("1"), conn("2"))); vm.load()
        assertNull(vm.state.value.selectedConnectionId)
    }

    @Test fun blank_text_blocks_create() = runTest {
        val vm = vm(this, listOf(conn("1"))); vm.load()
        assertFalse(canCreate(vm.state.value))
        assertNull(vm.create())
    }

    @Test fun create_success_exposes_created_thread_id() = runTest {
        val vm = vm(
            this, listOf(conn("1")),
            {
                respond(
                    """{"id":"thread-abc","subject":"Hello","awaiting_reply":false,"messages":[]}""",
                    HttpStatusCode.OK, jsonHdr,
                )
            },
        )
        vm.load()
        vm.onTextChange("Hello there")
        vm.onSubjectChange("Hello")
        vm.create()?.join()
        val s = vm.state.value
        val msg = s.message as NewThreadMessage.Created
        assertEquals("thread-abc", msg.threadId)
        assertFalse(s.inFlight)
    }

    @Test fun create_success_exposes_created_thread_id_no_subject() = runTest {
        val vm = vm(
            this, listOf(conn("1")),
            {
                respond(
                    """{"id":"thread-xyz","subject":"","awaiting_reply":false,"messages":[]}""",
                    HttpStatusCode.OK, jsonHdr,
                )
            },
        )
        vm.load()
        vm.onTextChange("A message with no subject")
        // subject left blank
        vm.create()?.join()
        val msg = vm.state.value.message as NewThreadMessage.Created
        assertEquals("thread-xyz", msg.threadId)
    }

    @Test fun create_401_surfaces_settings_hint() = runTest {
        val vm = vm(
            this, listOf(conn("1")),
            { respond("""{"detail":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHdr) },
        )
        vm.load()
        vm.onTextChange("hi")
        vm.create()?.join()
        val m = vm.state.value.message as NewThreadMessage.Error
        assertTrue(m.text.contains("Settings"))
    }

    @Test fun create_error_clears_inFlight() = runTest {
        val vm = vm(
            this, listOf(conn("1")),
            { respond("""{"detail":"server error"}""", HttpStatusCode.InternalServerError, jsonHdr) },
        )
        vm.load()
        vm.onTextChange("Test message")
        vm.create()?.join()
        assertFalse(vm.state.value.inFlight)
        assertNotNull(vm.state.value.message)
        assertTrue(vm.state.value.message is NewThreadMessage.Error)
    }

    @Test fun stale_selection_scrubbed_on_reload() = runTest {
        var conns = listOf(conn("1"), conn("2"), conn("3"))
        val vm = NewThreadViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, jsonHdr) }) { mshipDefaults() })),
            connectionsProvider = { conns },
            testScope = this,
        )
        vm.load(); vm.onSelectConnection("1")
        assertEquals("1", vm.state.value.selectedConnectionId)
        conns = listOf(conn("2"), conn("3"))  // "1" removed
        vm.load()
        assertNull(vm.state.value.selectedConnectionId)  // stale dropped; >1 remain → no auto-default
    }

    @Test fun initial_state_is_loading_then_cleared() = runTest {
        val vm = vm(this, listOf(conn("1")))
        assertTrue(vm.state.value.isLoading)
        vm.load()
        assertFalse(vm.state.value.isLoading)
    }

    @Test fun load_clears_previous_message() = runTest {
        val vm = vm(
            this, listOf(conn("1")),
            { respond("""{"detail":"error"}""", HttpStatusCode.InternalServerError, jsonHdr) },
        )
        vm.load(); vm.onTextChange("hi"); vm.create()?.join()
        assertTrue(vm.state.value.message is NewThreadMessage.Error)
        vm.load()
        assertNull(vm.state.value.message)
    }

    @Test fun can_create_requires_connection_and_non_blank_text() = runTest {
        val vm = vm(this, listOf(conn("1"), conn("2"))); vm.load()
        // no connection selected yet
        assertFalse(canCreate(vm.state.value.copy(text = "hi")))
        vm.onSelectConnection("1")
        assertTrue(canCreate(vm.state.value.copy(text = "hi")))
        assertFalse(canCreate(vm.state.value.copy(text = "  ")))
    }
}
