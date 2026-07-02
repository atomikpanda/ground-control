package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.done.DoneUiState
import com.atomikpanda.groundcontrol.ui.done.DoneViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DoneViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private val itemJson = """
        {"id":"wi-1","kind":"feature","title":"T","phase":"done",
         "task_slugs":["a"],"spec_id":null,"updated_at":"2026-07-02T00:00:00Z"}
    """.trimIndent()

    private val taskJson = """
        {"slug":"a","description":"do the thing","phase":"done","branch":"feat/a",
         "pr_urls":{},"test_results":{"mothership":"pass"},
         "affected_repos":["mothership"],"finished_at":"2026-07-01T12:00:00Z"}
    """.trimIndent()

    private fun vm(scope: CoroutineScope, handler: MockRequestHandler) = DoneViewModel(
        SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }),
        conn, "wi-1", testScope = scope,
    )

    private fun defaultHandler(): MockRequestHandler = { req ->
        when {
            req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                respond(itemJson, HttpStatusCode.OK, jsonHdr)
            req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                respond(taskJson, HttpStatusCode.OK, jsonHdr)
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    @Test fun load_fans_out_and_computes_completion_summary() = runTest {
        val vm = vm(this, defaultHandler())
        vm.load().join()
        val c = vm.state.value as DoneUiState.Content
        assertEquals(1, c.c.tasks.size)
        assertEquals(listOf("mothership"), c.c.reposTouched)
        assertEquals("2026-07-01T12:00:00Z", c.c.completedAt)
        assertNull(c.c.review)
    }
}
