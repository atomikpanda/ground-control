package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.review.ReviewUiState
import com.atomikpanda.groundcontrol.ui.review.ReviewViewModel
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
        conn, "wi-1", testScope = scope,
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
}
