package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.workspace.WorkspaceUiState
import com.atomikpanda.groundcontrol.ui.workspace.WorkspaceViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("c1", "http://h:47100", null, "ws-a")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(scope: kotlinx.coroutines.CoroutineScope, failTasks: Boolean = false): WorkspaceViewModel {
        val api = SpecApi(HttpClient(MockEngine { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/threads") -> respond("""[{"id":"t1","subject":"Q"}]""", HttpStatusCode.OK, jsonHdr)
                p.endsWith("/specs") -> respond("""[{"id":"s1","title":"A","status":"drafting"}]""", HttpStatusCode.OK, jsonHdr)
                p.endsWith("/tasks") ->
                    if (failTasks) respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
                    else respond("""[{"slug":"k1","phase":"dev","branch":"b"}]""", HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }) { mshipDefaults() })
        return WorkspaceViewModel(api, conn, testScope = scope)
    }

    @Test fun loads_all_three_lists_for_one_workspace() = runTest {
        val vm = vm(this)
        vm.refresh()?.join()
        val c = vm.state.value as WorkspaceUiState.Content
        assertEquals(listOf("t1"), c.threads.map { it.id })
        assertEquals(listOf("s1"), c.specs.map { it.id })
        assertEquals(listOf("k1"), c.tasks.map { it.slug })
        assertTrue(!c.errored)
    }

    @Test fun partial_failure_sets_errored_but_keeps_other_lists() = runTest {
        val vm = vm(this, failTasks = true)
        vm.refresh()?.join()
        val c = vm.state.value as WorkspaceUiState.Content
        assertEquals(listOf("t1"), c.threads.map { it.id })
        assertTrue(c.tasks.isEmpty())
        assertTrue(c.errored)
    }
}
