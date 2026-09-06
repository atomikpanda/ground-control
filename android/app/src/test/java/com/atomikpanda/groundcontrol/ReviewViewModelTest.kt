package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.EvidenceLockedException
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.review.ReviewUiState
import com.atomikpanda.groundcontrol.ui.review.ReviewViewModel
import com.atomikpanda.groundcontrol.ui.review.evidenceOpenUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private val itemJson = """
        {"id":"wi-1","kind":"feature","title":"T","phase":"in_flight",
         "task_slugs":["a"],"thread_ids":["t1"],"spec_id":null}
    """.trimIndent()

    private val taskJson = """
        {"slug":"a","description":"do the thing","phase":"in_progress","branch":"feat/a",
         "pr_urls":{"mothership":"http://pr/1"},"test_results":{"mothership":"pass"}}
    """.trimIndent()

    private val threadJson = """
        {"id":"t1","subject":"s","messages":[
          {"id":"m1","thread_id":"t1","role":"human","text":"hi","created_at":"2026-06-30T10:00:00Z"}
        ]}
    """.trimIndent()

    private val itemWithSpecJson = """
        {"id":"wi-1","kind":"feature","title":"T","phase":"in_flight",
         "task_slugs":["a"],"thread_ids":["t1"],"spec_id":"spec-1"}
    """.trimIndent()

    private val specJson = """
        {"id":"spec-1","title":"T","status":"dispatched",
         "acceptance_criteria":[
           {"id":"ac1","text":"does the thing","verdict":"approved",
            "evidence":[{"kind":"commit","ref":"abc123","note":null}]}
         ]}
    """.trimIndent()

    private fun vm(scope: CoroutineScope, handler: MockRequestHandler) = ReviewViewModel(
        SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }),
        conn.id, "wi-1",
        kotlinx.coroutines.flow.MutableStateFlow(com.atomikpanda.groundcontrol.data.ConnectionState.Ready(listOf(conn))),
        testScope = scope,
    )

    /** Routes the item/task/thread fan-out GETs plus the requestChanges POST; records POSTed bodies when given a sink. */
    private fun defaultHandler(postedTexts: MutableList<String>? = null): MockRequestHandler = { req ->
        when {
            req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                respond(itemJson, HttpStatusCode.OK, jsonHdr)
            req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                respond(taskJson, HttpStatusCode.OK, jsonHdr)
            req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                respond(threadJson, HttpStatusCode.OK, jsonHdr)
            req.url.encodedPath.endsWith("/threads/t1/messages") && req.method == HttpMethod.Post -> {
                postedTexts?.add((req.body as TextContent).text)
                respond(threadJson, HttpStatusCode.OK, jsonHdr)
            }
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
        val content = (vm.state.value as ReviewUiState.Content).c
        val failure = runCatching { vm.loadEvidence(content, "image.png") }.exceptionOrNull()
        assertTrue(failure is EvidenceLockedException)
    }

    @Test fun item_level_pr_resolves_commit_evidence_when_task_is_unavailable() = runTest {
        val vm = vm(backgroundScope) { request ->
            when {
                request.url.encodedPath.endsWith("/items/wi-1") -> respond(
                    """{"id":"wi-1","title":"T","kind":"chore","phase":"review",
                       "task_slugs":["missing"],"spec_id":"spec-1",
                       "pr_urls":["https://github.com/owner/repo/pull/7"]}""",
                    HttpStatusCode.OK, jsonHdr,
                )
                request.url.encodedPath.endsWith("/specs/spec-1") -> respond(
                    """{"id":"spec-1","title":"T","status":"dispatched",
                       "acceptance_criteria":[{"id":"ac1","text":"Implemented","verdict":"approved",
                       "evidence":[{"kind":"commit","ref":"abcdef1"}]}]}""",
                    HttpStatusCode.OK, jsonHdr,
                )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load().join()
        val content = (vm.state.value as ReviewUiState.Content).c
        val evidence = content.criteria.single().evidence.single()
        assertEquals(
            "https://github.com/owner/repo/commit/abcdef1",
            evidenceOpenUrl(evidence.kind, evidence.ref, content.prUrls),
        )
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
        val vm = ReviewViewModel(api, conn.id, "wi-1", connections, testScope = backgroundScope)
        vm.load().join()
        val oldContent = (vm.state.value as ReviewUiState.Content).c
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
        val vm = ReviewViewModel(api, conn.id, "wi-1", connections, testScope = backgroundScope)
        vm.load().join()
        val content = (vm.state.value as ReviewUiState.Content).c
        val result = async { runCatching { vm.loadEvidence(content, "image.png") } }
        entered.await()
        connections.value = ConnectionState.Ready(listOf(conn.copy(token = "replacement")))
        runCurrent()
        release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test fun load_fans_out_and_aggregates_pr_rows() = runTest {
        val vm = vm(this, defaultHandler())
        vm.load().join()
        val c = vm.state.value as ReviewUiState.Content
        assertEquals(1, c.c.prs.size)
        val row = c.c.prs[0]
        assertEquals("a", row.taskSlug)
        assertEquals("mothership", row.repo)
        assertEquals("http://pr/1", row.url)
        assertEquals("pass", row.testStatus)
        assertEquals("t1", c.c.threadId)
    }

    @Test fun requestChanges_posts_structured_comment_to_work_item_thread() = runTest {
        val postedTexts = mutableListOf<String>()
        val vm = vm(this, defaultHandler(postedTexts))
        vm.load().join()
        vm.requestChanges("please fix X").join()
        assertEquals(1, postedTexts.size)
        assertTrue(postedTexts[0].contains("please fix X"))
    }

    @Test fun load_drops_a_task_that_404s_but_still_yields_content() = runTest {
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respondError(HttpStatusCode.NotFound)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        val c = vm.state.value as ReviewUiState.Content
        assertTrue(c.c.prs.isEmpty())
        assertEquals("t1", c.c.threadId)
    }

    @Test fun requestChanges_sets_sendError_when_post_fails() = runTest {
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respond(taskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1/messages") && req.method == HttpMethod.Post ->
                    respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        vm.requestChanges("please fix X").join()
        assertEquals("Couldn't send — check your connection and try again.", vm.sendError.value)
        assertEquals(false, vm.sending.value)
    }

    @Test fun load_fetches_bound_spec_criteria_and_pr_urls() = runTest {
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemWithSpecJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respond(taskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/specs/spec-1") && req.method == HttpMethod.Get ->
                    respond(specJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        val c = (vm.state.value as ReviewUiState.Content).c
        assertEquals(1, c.criteria.size)
        assertEquals("commit", c.criteria[0].evidence[0].kind)
        assertEquals("abc123", c.criteria[0].evidence[0].ref)
        assertTrue(c.prUrls.contains("http://pr/1"))
    }

    @Test fun load_without_spec_has_no_criteria() = runTest {
        // itemJson has spec_id null -> no /specs fetch, empty criteria.
        val vm = vm(this, defaultHandler())
        vm.load().join()
        val c = (vm.state.value as ReviewUiState.Content).c
        assertTrue(c.criteria.isEmpty())
    }

    @Test fun load_survives_spec_fetch_error_with_empty_criteria() = runTest {
        // Best-effort boundary: a spec 500 must NOT degrade the page to Failed —
        // it stays Content with empty criteria (Greptile #57).
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemWithSpecJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respond(taskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/specs/spec-1") && req.method == HttpMethod.Get ->
                    respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        val c = (vm.state.value as ReviewUiState.Content).c   // Content, not Failed
        assertTrue(c.criteria.isEmpty())
    }

    @Test fun connection_replacement_resets_sending_and_fences_old_request_changes() = runTest {
        val old = conn.copy(baseUrl = "http://old:47100", token = "old-token")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token")
        val connections = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val oldRequestStarted = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        var replacementLoads = 0
        val handler: MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/threads/t1/messages") &&
                    request.method == HttpMethod.Post -> {
                    oldRequestStarted.complete(Unit)
                    releaseOldRequest.await()
                    respond("{}", HttpStatusCode.OK, jsonHdr)
                }
                request.url.encodedPath.endsWith("/items/wi-1") &&
                    request.method == HttpMethod.Get -> {
                    if (request.url.host == "new") {
                        replacementLoads += 1
                        respond(itemJson.replace("\"title\":\"T\"", "\"title\":\"Replacement\""), HttpStatusCode.OK, jsonHdr)
                    } else {
                        respond(itemJson, HttpStatusCode.OK, jsonHdr)
                    }
                }
                request.url.encodedPath.endsWith("/tasks/a") ->
                    respond(taskJson, HttpStatusCode.OK, jsonHdr)
                request.url.encodedPath.endsWith("/threads/t1") ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = ReviewViewModel(
            SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }),
            old.id,
            "wi-1",
            connections,
            testScope = backgroundScope,
        )
        vm.load().join()
        val staleRequest = vm.requestChanges("held")
        oldRequestStarted.await()
        assertTrue(vm.sending.value)

        connections.value = ConnectionState.Ready(listOf(replacement))
        runCurrent()
        vm.state.first {
            (it as? ReviewUiState.Content)?.c?.item?.title == "Replacement"
        }
        assertEquals(1, replacementLoads)

        releaseOldRequest.complete(Unit)
        staleRequest.join()
        assertEquals(false, vm.sending.value)
    }
}
