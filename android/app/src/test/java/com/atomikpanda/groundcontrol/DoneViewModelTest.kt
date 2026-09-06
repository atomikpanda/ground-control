package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.EvidenceLockedException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
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
        conn.id, "wi-1",
        kotlinx.coroutines.flow.MutableStateFlow(com.atomikpanda.groundcontrol.data.ConnectionState.Ready(listOf(conn))),
        testScope = scope,
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

    @Test fun locked_evidence_remains_a_distinct_failure_state() = runTest {
        val vm = vm(backgroundScope) { request ->
            if (request.url.encodedPath.endsWith("/items/wi-1")) {
                respond(itemWithSpecJson, HttpStatusCode.OK, jsonHdr)
            } else {
                respond("""{"detail":"artifact locked"}""", HttpStatusCode.Conflict, jsonHdr)
            }
        }
        vm.load().join()
        val content = (vm.state.value as DoneUiState.Content).c
        val failure = runCatching { vm.loadEvidence(content, "image.png") }.exceptionOrNull()
        assertTrue(failure is EvidenceLockedException)
    }

    @Test fun old_content_cannot_start_evidence_reads_through_a_replacement_connection() = runTest {
        val replacementLoading = CompletableDeferred<Unit>()
        val connections = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(conn)))
        val api = SpecApi(HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/items/wi-1") -> {
                    if (request.headers[HttpHeaders.Authorization] == "Bearer replacement") {
                        replacementLoading.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    }
                    respond(itemWithSpecJson, HttpStatusCode.OK, jsonHdr)
                }
                request.url.encodedPath.endsWith("/evidence/image.png/blob") ->
                    respond(byteArrayOf(9), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                else -> respondError(HttpStatusCode.NotFound)
            }
        }) { mshipDefaults() })
        val vm = DoneViewModel(api, conn.id, "wi-1", connections, testScope = backgroundScope)
        vm.load().join()
        val oldContent = (vm.state.value as DoneUiState.Content).c
        val loadFromOldRow = suspend { vm.loadEvidence(oldContent, "image.png") }
        connections.value = ConnectionState.Ready(listOf(conn.copy(token = "replacement")))
        replacementLoading.await()
        assertTrue(runCatching { loadFromOldRow() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test fun evidence_from_a_replaced_connection_is_not_published() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connections = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(conn)))
        val api = SpecApi(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/evidence/image.png/blob")) {
                entered.complete(Unit)
                release.await()
                respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            } else if (req.url.encodedPath.endsWith("/items/wi-1")) {
                respond(itemWithSpecJson, HttpStatusCode.OK, jsonHdr)
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }) { mshipDefaults() })
        val vm = DoneViewModel(api, conn.id, "wi-1", connections, testScope = backgroundScope)
        vm.load().join()
        val content = (vm.state.value as DoneUiState.Content).c
        val result = async { runCatching { vm.loadEvidence(content, "image.png") } }
        entered.await()
        connections.value = ConnectionState.Ready(listOf(conn.copy(token = "replacement")))
        runCurrent()
        release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
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

    private val taskJsonNoFinishedAt = """
        {"slug":"a","description":"do the thing","phase":"done","branch":"feat/a",
         "pr_urls":{},"test_results":{"mothership":"pass"},
         "affected_repos":["mothership"],"finished_at":null}
    """.trimIndent()

    @Test fun completedAt_falls_back_to_item_updatedAt_when_no_task_finished_at() = runTest {
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respond(taskJsonNoFinishedAt, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        val c = vm.state.value as DoneUiState.Content
        assertEquals("2026-07-02T00:00:00Z", c.c.completedAt)
    }

    private val itemWithSpecJson = """
        {"id":"wi-1","kind":"feature","title":"T","phase":"done",
         "task_slugs":["a"],"spec_id":"spec-1","updated_at":"2026-07-02T00:00:00Z"}
    """.trimIndent()

    private val taskWithPrJson = """
        {"slug":"a","description":"do the thing","phase":"done","branch":"feat/a",
         "pr_urls":{"mothership":"http://pr/1"},"test_results":{"mothership":"pass"},
         "affected_repos":["mothership"],"finished_at":"2026-07-01T12:00:00Z"}
    """.trimIndent()

    private val reviewJson = """
        {"id":"spec-1","status":"dispatched",
         "acceptance_criteria":[
           {"id":"ac1","text":"does the thing","verdict":"approved",
            "evidence":[{"kind":"commit","ref":"abc123","note":null}]}
         ],
         "summary":{"criteria_total":1,"approved":1}}
    """.trimIndent()

    @Test fun load_surfaces_acceptance_criteria_and_pr_urls() = runTest {
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemWithSpecJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respond(taskWithPrJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/specs/spec-1/review") && req.method == HttpMethod.Get ->
                    respond(reviewJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        val c = (vm.state.value as DoneUiState.Content).c
        assertEquals(1, c.criteria.size)
        assertEquals("commit", c.criteria[0].evidence[0].kind)
        assertEquals(listOf("http://pr/1"), c.prUrls)
    }

    @Test fun load_retains_summary_metadata_when_task_detail_is_unavailable() = runTest {
        val summaryItemJson = """
            {"id":"wi-1","kind":"feature","title":"T","phase":"done",
             "task_slugs":["archived-task"],"updated_at":"2026-07-02T00:00:00Z",
             "affected_repos":["mothership","ground-control"],
             "pr_urls":["https://github.example/mship/pull/1","https://github.example/mship/pull/2"]}
        """.trimIndent()
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(summaryItemJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val vm = vm(this, handler)
        vm.load().join()
        val c = (vm.state.value as DoneUiState.Content).c

        assertEquals(0, c.tasks.size)
        assertEquals(listOf("mothership", "ground-control"), c.reposTouched)
        assertEquals(
            listOf(
                "https://github.example/mship/pull/1",
                "https://github.example/mship/pull/2",
            ),
            c.prUrls,
        )
        assertEquals(c.prUrls, c.summaryPrUrls)
    }
}
