package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.capture.CaptureMessage
import com.atomikpanda.groundcontrol.ui.capture.CaptureViewModel
import com.atomikpanda.groundcontrol.ui.capture.canCreate
import com.atomikpanda.groundcontrol.ui.capture.parseRepos
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun conn(id: String) = WorkspaceConnection(id, "http://h-$id:47100", "tok", "ws-$id")

    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        conns: List<WorkspaceConnection>,
        handler: io.ktor.client.engine.mock.MockRequestHandler = { respond("""{"id":"x","title":"X","status":"drafting","body":""}""", HttpStatusCode.OK, jsonHdr) },
    ): CaptureViewModel =
        CaptureViewModel({ conns }, SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }), scope)

    @Test fun parse_repos_splits_trims_drops_empties() {
        assertEquals(listOf("a", "b"), parseRepos(" a , b , "))
        assertEquals(emptyList<String>(), parseRepos("   "))
    }

    @Test fun no_connections_blocks_create() = runTest {
        val vm = vm(this, emptyList()); vm.load()
        val s = vm.state.value
        assertTrue(s.connections.isEmpty())
        assertNull(s.selectedConnectionId)
        assertFalse(canCreate(s.copy(title = "hi")))   // no connection selected
    }

    @Test fun single_connection_auto_selected() = runTest {
        val vm = vm(this, listOf(conn("1"))); vm.load()
        assertEquals("1", vm.state.value.selectedConnectionId)
    }

    @Test fun multi_connection_requires_explicit_pick() = runTest {
        val vm = vm(this, listOf(conn("1"), conn("2"))); vm.load()
        assertNull(vm.state.value.selectedConnectionId)
    }

    @Test fun blank_title_blocks_create() = runTest {
        val vm = vm(this, listOf(conn("1"))); vm.load()
        assertFalse(canCreate(vm.state.value))                 // title blank
        assertNull(vm.create())                                // no-op
    }

    @Test fun create_success_clears_and_messages() = runTest {
        val vm = vm(this, listOf(conn("1")),
            { respond("""{"id":"my-idea","title":"My idea","status":"drafting","body":""}""", HttpStatusCode.OK, jsonHdr) })
        vm.load(); vm.onTitleChange("My idea"); vm.onReposChange("ground-control")
        vm.create()?.join()
        val s = vm.state.value
        assertEquals("", s.title)
        assertEquals("", s.repos)
        assertEquals(CaptureMessage.Created("my-idea"), s.message)
        assertFalse(s.inFlight)
    }

    @Test fun create_409_surfaces_collision_message() = runTest {
        val vm = vm(this, listOf(conn("1")),
            { respond("""{"detail":"spec 'my-idea' already exists"}""", HttpStatusCode.Conflict, jsonHdr) })
        vm.load(); vm.onTitleChange("My idea")
        vm.create()?.join()
        val m = vm.state.value.message as CaptureMessage.Error
        assertTrue(m.text.contains("already exists"))
    }
}
