package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.ui.messages.CaptureKind
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
        NewThreadViewModel(ThreadsRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() })), connectionState(conns), scope)

    @Test fun no_connections_empty_state_blocks_create() = runTest {
        val vm = vm(backgroundScope, emptyList()); runCurrent(); vm.load()
        val s = vm.state.value
        assertTrue(s.connections.isEmpty())
        assertNull(s.selectedConnectionId)
        assertFalse(canCreate(s.copy(text = "hello")))
    }

    @Test fun single_connection_auto_selected() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1"))); runCurrent(); vm.load()
        assertEquals("1", vm.state.value.selectedConnectionId)
    }

    @Test fun multi_connection_requires_explicit_pick() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1"), conn("2"))); runCurrent(); vm.load()
        assertNull(vm.state.value.selectedConnectionId)
    }

    @Test fun blank_text_blocks_create() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1"))); runCurrent(); vm.load()
        assertFalse(canCreate(vm.state.value))
        assertNull(vm.create())
    }

    @Test fun create_success_exposes_created_thread_id() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1")), {
            respond(
                """{"id":"thread-abc","subject":"Hello","awaiting_reply":false,"messages":[]}""",
                HttpStatusCode.OK, jsonHdr,
            )
        })
        runCurrent(); vm.load()
        vm.onTextChange("Hello there")
        vm.onSubjectChange("Hello")
        vm.create()?.join()
        val s = vm.state.value
        val msg = s.message as NewThreadMessage.Created
        assertEquals("thread-abc", msg.threadId)
        assertFalse(s.inFlight)
    }

    @Test fun same_id_replacement_drops_a_created_message_before_navigation_consumes_it() = runTest {
        val first = conn("1")
        val replacement = first.copy(baseUrl = "http://new:47100", token = "new-token")
        val connections = connectionState(listOf(first))
        val vm = NewThreadViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine {
                respond(
                    """{"id":"thread-abc","subject":"Hello","awaiting_reply":false,"messages":[]}""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            }) { mshipDefaults() })),
            connections,
            backgroundScope,
        )
        runCurrent()
        vm.onTextChange("Hello there")
        vm.create()?.join()

        connections.value = ConnectionState.Ready(listOf(replacement))
        runCurrent()

        assertNull(vm.state.value.message)
    }

    @Test fun same_id_endpoint_replacement_drops_the_retired_create_completion() = runTest {
        val retired = conn("workspace")
        val replacement = retired.copy(baseUrl = "http://new:47100", token = "new-token")
        val dispatched = CompletableDeferred<Unit>()
        val respondToRetiredRequest = CompletableDeferred<Unit>()
        val connections = connectionState(listOf(retired))
        val vm = NewThreadViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine {
                dispatched.complete(Unit)
                respondToRetiredRequest.await()
                respond(
                    """{"id":"thread-abc","subject":"Hello","awaiting_reply":false,"messages":[]}""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            }) { mshipDefaults() })),
            connections,
            backgroundScope,
        )
        runCurrent()
        vm.onTextChange("Hello there")
        val create = vm.create()
        runCurrent()
        dispatched.await()

        connections.value = ConnectionState.Ready(listOf(replacement))
        runCurrent()
        respondToRetiredRequest.complete(Unit)
        create?.join()

        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.inFlight)
    }

    @Test fun create_completion_adopts_canonical_connection_after_retired_snapshot() = runTest {
        val retired = conn("retired")
        val canonical = conn("canonical").copy(legacyConnectionIds = listOf(retired.id))
        val dispatched = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        val connections = connectionState(listOf(retired))
        val vm = NewThreadViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine {
                dispatched.complete(Unit)
                respond.await()
                respond(
                    """{"id":"thread-abc","subject":"Hello","awaiting_reply":false,"messages":[]}""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            }) { mshipDefaults() })),
            connections,
            backgroundScope,
        )
        runCurrent()
        vm.onTextChange("Hello there")
        val create = vm.create()
        runCurrent()
        dispatched.await()

        connections.value = com.atomikpanda.groundcontrol.data.ConnectionState.Ready(listOf(canonical))
        runCurrent()
        respond.complete(Unit)
        create?.join()

        assertEquals(NewThreadMessage.Created(canonical.id, "thread-abc"), vm.state.value.message)
        assertFalse(vm.state.value.inFlight)
    }

    @Test fun create_completion_does_not_navigate_an_unrelated_replacement() = runTest {
        val retired = conn("retired")
        val unrelated = conn("unrelated")
        val dispatched = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        val connections = connectionState(listOf(retired))
        val vm = NewThreadViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine {
                dispatched.complete(Unit)
                respond.await()
                respond(
                    """{"id":"thread-abc","subject":"Hello","awaiting_reply":false,"messages":[]}""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            }) { mshipDefaults() })),
            connections,
            backgroundScope,
        )
        runCurrent()
        vm.onTextChange("Hello there")
        val create = vm.create()
        runCurrent()
        dispatched.await()

        connections.value = com.atomikpanda.groundcontrol.data.ConnectionState.Ready(listOf(unrelated))
        runCurrent()
        respond.complete(Unit)
        create?.join()

        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.inFlight)
    }

    @Test fun create_success_exposes_created_thread_id_no_subject() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1")), {
            respond(
                """{"id":"thread-xyz","subject":"","awaiting_reply":false,"messages":[]}""",
                HttpStatusCode.OK, jsonHdr,
            )
        })
        runCurrent(); vm.load()
        vm.onTextChange("A message with no subject")
        // subject left blank
        vm.create()?.join()
        val msg = vm.state.value.message as NewThreadMessage.Created
        assertEquals("thread-xyz", msg.threadId)
    }

    @Test fun create_401_surfaces_settings_hint() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1")), { respond("""{"detail":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHdr) })
        runCurrent(); vm.load()
        vm.onTextChange("hi")
        vm.create()?.join()
        val m = vm.state.value.message as NewThreadMessage.Error
        assertTrue(m.text.contains("Settings"))
    }

    @Test fun create_error_clears_inFlight() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1")), { respond("""{"detail":"server error"}""", HttpStatusCode.InternalServerError, jsonHdr) })
        runCurrent(); vm.load()
        vm.onTextChange("Test message")
        vm.create()?.join()
        assertFalse(vm.state.value.inFlight)
        assertNotNull(vm.state.value.message)
        assertTrue(vm.state.value.message is NewThreadMessage.Error)
    }

    @Test fun connection_error_during_failed_create_clears_in_flight() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val connection = conn("1")
        val connections = connectionState(listOf(connection))
        val vm = NewThreadViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine {
                requestStarted.complete(Unit)
                releaseRequest.await()
                respond("""{"detail":"server error"}""", HttpStatusCode.InternalServerError, jsonHdr)
            }) { mshipDefaults() })),
            connections,
            backgroundScope,
        )
        runCurrent()
        vm.onTextChange("Test message")
        val create = vm.create()
        runCurrent()
        requestStarted.await()

        connections.value = ConnectionState.Error(IllegalStateException("disk"))
        runCurrent()
        releaseRequest.complete(Unit)
        create?.join()

        assertFalse(vm.state.value.inFlight)
        assertNull(vm.state.value.message)
    }

    @Test fun replacement_before_create_dispatch_is_not_reported_as_a_workspace_error() = runTest {
        val retired = conn("workspace")
        val replacement = retired.copy(baseUrl = "http://new:47100", token = "new-token")
        val connections = connectionState(listOf(retired))
        var requests = 0
        val vm = NewThreadViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine {
                requests += 1
                respond(
                    """{"id":"thread-abc","subject":"Hello","awaiting_reply":false,"messages":[]}""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            }) { mshipDefaults() })),
            connections,
            backgroundScope,
        )
        runCurrent()
        vm.onTextChange("Test message")
        val create = vm.create()
        connections.value = ConnectionState.Ready(listOf(replacement))
        runCurrent()
        create?.join()

        assertEquals(0, requests)
        assertFalse(vm.state.value.inFlight)
        assertNull(vm.state.value.message)
    }

    @Test fun stale_selection_scrubbed_on_reload() = runTest {
        val connections = connectionState(listOf(conn("1"), conn("2"), conn("3")))
        val vm = NewThreadViewModel(ThreadsRepository(SpecApi(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, jsonHdr) }) { mshipDefaults() })), connections, backgroundScope)
        runCurrent(); vm.load(); vm.onSelectConnection("1")
        assertEquals("1", vm.state.value.selectedConnectionId)
        connections.value = com.atomikpanda.groundcontrol.data.ConnectionState.Ready(listOf(conn("2"), conn("3")))
        runCurrent()
        assertNull(vm.state.value.selectedConnectionId)  // stale dropped; >1 remain → no auto-default
    }

    @Test fun create_resolves_a_retired_selection_against_the_action_time_connections() = runTest {
        val retired = WorkspaceConnection("retired", "http://old:47100", null, "ws")
        val canonical = WorkspaceConnection(
            "canonical",
            "http://new:47100",
            null,
            "ws",
            legacyConnectionIds = listOf(retired.id),
        )
        val connections = connectionState(listOf(retired))
        var postedHost: String? = null
        val vm = NewThreadViewModel(ThreadsRepository(
            SpecApi(
                HttpClient(MockEngine { req ->
                    postedHost = req.url.host
                    respond(
                        """{"id":"thread-1","subject":"Subject","awaiting_reply":false,"messages":[]}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                }) { mshipDefaults() },
            ),
        ), connections, backgroundScope)
        runCurrent(); vm.load()

        connections.value = com.atomikpanda.groundcontrol.data.ConnectionState.Ready(listOf(canonical))
        runCurrent()
        vm.onTextChange("hello")
        vm.create()?.join()

        assertEquals(canonical.id, vm.state.value.selectedConnectionId)
        assertEquals("new", postedHost)
        assertEquals(canonical.id, (vm.state.value.message as NewThreadMessage.Created).connectionId)
    }

    @Test fun initial_state_is_loading_then_cleared() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1")))
        assertTrue(vm.state.value.isLoading)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
    }

    @Test fun load_clears_previous_message() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1")), { respond("""{"detail":"error"}""", HttpStatusCode.InternalServerError, jsonHdr) })
        runCurrent(); vm.load(); vm.onTextChange("hi"); vm.create()?.join()
        assertTrue(vm.state.value.message is NewThreadMessage.Error)
        runCurrent(); vm.load()
        assertNull(vm.state.value.message)
    }

    @Test fun can_create_requires_connection_and_non_blank_text() = runTest {
        val vm = vm(backgroundScope, listOf(conn("1"), conn("2"))); runCurrent(); vm.load()
        // no connection selected yet
        assertFalse(canCreate(vm.state.value.copy(text = "hi")))
        vm.onSelectConnection("1")
        assertTrue(canCreate(vm.state.value.copy(text = "hi")))
        assertFalse(canCreate(vm.state.value.copy(text = "  ")))
    }

    @Test fun quick_note_posts_to_threads() = runTest {
        var path: String? = null
        val vm = vm(backgroundScope, listOf(conn("1")), {
            path = it.url.encodedPath
            respond("""{"id":"t1","subject":"s","messages":[]}""", HttpStatusCode.OK, jsonHdr)
        })
        runCurrent(); vm.load(); vm.onSelectKind(CaptureKind.QUICK_NOTE); vm.onTextChange("hi")
        vm.create()?.join()
        assertTrue(path!!.endsWith("/threads"))
    }

    @Test fun brainstorm_spec_posts_to_capture() = runTest {
        var path: String? = null
        val vm = vm(backgroundScope, listOf(conn("1")), {
            path = it.url.encodedPath
            respond("""{"id":"t1","subject":"s","messages":[]}""", HttpStatusCode.OK, jsonHdr)
        })
        runCurrent(); vm.load(); vm.onSelectKind(CaptureKind.BRAINSTORM_SPEC); vm.onTextChange("an idea")
        vm.create()?.join()
        assertTrue(path!!.endsWith("/capture"))
    }
}
