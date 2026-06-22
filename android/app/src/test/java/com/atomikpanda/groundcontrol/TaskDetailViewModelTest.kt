package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import com.atomikpanda.groundcontrol.ui.tasks.TaskDetailUiState
import com.atomikpanda.groundcontrol.ui.tasks.TaskDetailViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(scope: CoroutineScope, handler: MockRequestHandler) =
        TaskDetailViewModel(
            TasksRepository(
                SpecApi(
                    HttpClient(MockEngine(handler)) { mshipDefaults() }
                )
            ),
            conn, "t1", testScope = scope,
        )

    @Test fun load_success_builds_content_with_task_and_journal() = runTest {
        val vm = vm(this) { req ->
            when {
                req.url.encodedPath.endsWith("/tasks/t1") ->
                    respond(
                        """{"slug":"t1","phase":"dev","branch":"feat/t1","description":"do the thing",
                           "affected_repos":["gc"],"pr_urls":{},"test_results":{},"depends_on":[]}""",
                        HttpStatusCode.OK, jsonHdr
                    )
                req.url.encodedPath.endsWith("/journal/t1") ->
                    respond(
                        """[{"timestamp":"2026-06-22T10:00:00Z","message":"task started"},
                            {"timestamp":"2026-06-22T11:00:00Z","message":"tests pass","test_state":"green","repo":"gc"}]""",
                        HttpStatusCode.OK, jsonHdr
                    )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals("t1", c.task.slug)
        assertEquals("do the thing", c.task.description)
        assertEquals(2, c.journal.size)
        assertEquals("task started", c.journal[0].message)
        assertEquals("green", c.journal[1].testState)
    }

    @Test fun load_404_maps_to_not_found() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.NotFound) }
        vm.load()?.join()
        assertEquals(ErrorKind.NOT_FOUND, (vm.state.value as TaskDetailUiState.Error).kind)
    }

    @Test fun load_401_maps_to_auth() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.Unauthorized) }
        vm.load()?.join()
        assertEquals(ErrorKind.AUTH, (vm.state.value as TaskDetailUiState.Error).kind)
    }

    @Test fun load_network_error_maps_to_network() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.InternalServerError) }
        vm.load()?.join()
        val s = vm.state.value as TaskDetailUiState.Error
        assertEquals(ErrorKind.NETWORK, s.kind)
        assertTrue(s.message.isNotBlank())
    }
}
