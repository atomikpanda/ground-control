package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.console.ConsoleUiState
import com.atomikpanda.groundcontrol.ui.console.ConsoleViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private val itemJson = """
        {"id":"wi-1","kind":"feature","title":"T","phase":"in_flight",
         "task_slugs":["a"],"thread_ids":["t1"],"spec_id":null}
    """.trimIndent()

    private val taskJson = """
        {"slug":"a","description":"do the thing","phase":"in_progress","branch":"feat/a"}
    """.trimIndent()

    private val journalJson = """
        [{"timestamp":"2026-06-30T10:00:00Z","message":"started"}]
    """.trimIndent()

    private val threadJson = """
        {"id":"t1","subject":"s","messages":[
          {"id":"m1","thread_id":"t1","role":"human","text":"hi","created_at":"2026-06-30T10:00:00Z"}
        ]}
    """.trimIndent()

    private fun vm(scope: CoroutineScope, handler: MockRequestHandler) = ConsoleViewModel(
        SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }),
        conn, "wi-1", testScope = scope,
    )

    /** Routes the four fan-out GETs plus the item-scoped steer POST; records POSTed bodies when given a sink. */
    private fun defaultHandler(postedTexts: MutableList<String>? = null): MockRequestHandler = { req ->
        when {
            req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                respond(itemJson, HttpStatusCode.OK, jsonHdr)
            req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                respond(taskJson, HttpStatusCode.OK, jsonHdr)
            req.url.encodedPath.endsWith("/journal/a") && req.method == HttpMethod.Get ->
                respond(journalJson, HttpStatusCode.OK, jsonHdr)
            req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                respond(threadJson, HttpStatusCode.OK, jsonHdr)
            req.url.encodedPath.endsWith("/items/wi-1/messages") && req.method == HttpMethod.Post -> {
                postedTexts?.add((req.body as TextContent).text)
                respond(threadJson, HttpStatusCode.OK, jsonHdr)
            }
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    @Test fun load_fans_out_and_builds_content_with_no_active_decision() = runTest {
        val vm = vm(this, defaultHandler())
        vm.load().join()
        val c = vm.state.value as ConsoleUiState.Content
        assertEquals(1, c.c.tasks.size)
        assertEquals("a", c.c.tasks[0].slug)
        assertNull(c.c.activeDecision)
    }

    @Test fun sendDraft_posts_message_to_work_item_thread() = runTest {
        val postedTexts = mutableListOf<String>()
        val vm = vm(this, defaultHandler(postedTexts))
        vm.load().join()
        vm.onDraftChange("go")
        vm.sendDraft().join()
        assertEquals(1, postedTexts.size)
        assertTrue(postedTexts[0].contains("go"))
    }

    /** Regression: an in-flight item created from a spec/task has no thread. The old
     *  thread-scoped send silently returned (no post, no error, draft kept). The item-scoped
     *  endpoint always has a target, so the message actually sends and the draft clears. */
    @Test fun sendDraft_posts_even_when_item_has_no_thread() = runTest {
        val itemNoThreadJson = """
            {"id":"wi-1","kind":"feature","title":"T","phase":"in_flight",
             "task_slugs":["a"],"thread_ids":[],"spec_id":null}
        """.trimIndent()
        val posted = mutableListOf<String>()
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemNoThreadJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respond(taskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/journal/a") && req.method == HttpMethod.Get ->
                    respond(journalJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/items/wi-1/messages") && req.method == HttpMethod.Post -> {
                    posted.add((req.body as TextContent).text)
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        vm.onDraftChange("steer me")
        vm.sendDraft().join()
        assertEquals(1, posted.size)
        assertTrue(posted[0].contains("steer me"))
        assertEquals("", vm.draft.value)          // cleared on success
        assertNull(vm.sendError.value)
    }

    @Test fun sendDraft_sets_sendError_when_post_fails() = runTest {
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respond(taskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/journal/a") && req.method == HttpMethod.Get ->
                    respond(journalJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/items/wi-1/messages") && req.method == HttpMethod.Post ->
                    respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        vm.onDraftChange("go")
        vm.sendDraft().join()
        assertEquals("Couldn't send — check your connection and try again.", vm.sendError.value)
        assertEquals(false, vm.sending.value)
    }

    @Test fun sendDraft_retains_draft_on_failed_send_but_clears_it_on_success() = runTest {
        var failNext = true
        val postedTexts = mutableListOf<String>()
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/items/wi-1") && req.method == HttpMethod.Get ->
                    respond(itemJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/tasks/a") && req.method == HttpMethod.Get ->
                    respond(taskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/journal/a") && req.method == HttpMethod.Get ->
                    respond(journalJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/items/wi-1/messages") && req.method == HttpMethod.Post ->
                    if (failNext) {
                        respondError(HttpStatusCode.InternalServerError)
                    } else {
                        postedTexts.add((req.body as TextContent).text)
                        respond(threadJson, HttpStatusCode.OK, jsonHdr)
                    }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val vm = vm(this, handler)
        vm.load().join()
        vm.onDraftChange("go")

        // Failed send: the draft the user typed must survive.
        vm.sendDraft().join()
        assertEquals("go", vm.draft.value)
        assertEquals("Couldn't send — check your connection and try again.", vm.sendError.value)

        // Retry, this time succeeding: the draft is cleared only now.
        failNext = false
        vm.sendDraft().join()
        assertEquals("", vm.draft.value)
        assertNull(vm.sendError.value)
        assertEquals(1, postedTexts.size)
        assertTrue(postedTexts[0].contains("go"))
    }
}
