package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.messages.ConversationUiState
import com.atomikpanda.groundcontrol.ui.messages.ConversationViewModel
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(scope: CoroutineScope, handler: MockRequestHandler) =
        ConversationViewModel(
            ThreadsRepository(
                SpecApi(
                    HttpClient(MockEngine(handler)) { mshipDefaults() }
                )
            ),
            conn, "t1", testScope = scope,
        )

    // GET /threads/t1 response
    private val threadJson = """
        {
          "id": "t1",
          "subject": "Hello world",
          "awaiting_reply": true,
          "messages": [
            {"id":"m1","thread_id":"t1","role":"human","text":"Hey there","created_at":"2026-06-22T10:00:00Z"},
            {"id":"m2","thread_id":"t1","role":"agent","text":"Hi! How can I help?","created_at":"2026-06-22T10:01:00Z"}
          ]
        }
    """.trimIndent()

    // Thread returned after POST /threads/t1/messages
    private val afterSendJson = """
        {
          "id": "t1",
          "subject": "Hello world",
          "awaiting_reply": true,
          "messages": [
            {"id":"m1","thread_id":"t1","role":"human","text":"Hey there","created_at":"2026-06-22T10:00:00Z"},
            {"id":"m2","thread_id":"t1","role":"agent","text":"Hi! How can I help?","created_at":"2026-06-22T10:01:00Z"},
            {"id":"m3","thread_id":"t1","role":"human","text":"I need help","created_at":"2026-06-22T10:02:00Z"}
          ]
        }
    """.trimIndent()

    @Test fun load_success_yields_content_with_messages() = runTest {
        val vm = vm(this) { req ->
            if (req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get)
                respond(threadJson, HttpStatusCode.OK, jsonHdr)
            else respondError(HttpStatusCode.NotFound)
        }
        vm.load()?.join()
        val c = vm.state.value as ConversationUiState.Content
        assertEquals("t1", c.thread.id)
        assertEquals("Hello world", c.thread.subject)
        assertEquals(2, c.thread.messages.size)
        assertEquals("human", c.thread.messages[0].role)
        assertEquals("Hey there", c.thread.messages[0].text)
        assertFalse(c.inFlight)
    }

    @Test fun send_posts_and_updates_thread_from_returned_thread() = runTest {
        val vm = vm(this) { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1/messages") && req.method == HttpMethod.Post ->
                    respond(afterSendJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        vm.send("I need help")?.join()
        val c = vm.state.value as ConversationUiState.Content
        assertEquals(3, c.thread.messages.size)
        assertEquals("I need help", c.thread.messages[2].text)
        assertFalse(c.inFlight)
    }

    @Test fun send_failure_surfaces_error_keeps_thread_and_clears_inflight() = runTest {
        val vm = vm(this) { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1/messages") && req.method == HttpMethod.Post ->
                    respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        vm.send("I need help")?.join()
        val c = vm.state.value as ConversationUiState.Content
        assertNotNull(c.sendError)               // failure is surfaced, not silent
        assertFalse(c.inFlight)                  // compose bar re-enabled
        assertEquals(2, c.thread.messages.size)  // optimistic message not appended
    }

    @Test fun send_clears_prior_error_on_next_successful_attempt() = runTest {
        var firstPost = true
        val vm = vm(this) { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1/messages") && req.method == HttpMethod.Post ->
                    if (firstPost) { firstPost = false; respondError(HttpStatusCode.InternalServerError) }
                    else respond(afterSendJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        vm.send("I need help")?.join()
        assertNotNull((vm.state.value as ConversationUiState.Content).sendError)
        vm.send("I need help")?.join()
        val c = vm.state.value as ConversationUiState.Content
        assertEquals(null, c.sendError)          // prior error cleared on success
        assertEquals(3, c.thread.messages.size)
    }

    @Test fun send_is_noop_when_text_is_blank() = runTest {
        val vm = vm(this) { req ->
            if (req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get)
                respond(threadJson, HttpStatusCode.OK, jsonHdr)
            else respondError(HttpStatusCode.NotFound)
        }
        vm.load()?.join()
        val result = vm.send("   ")
        assertEquals(null, result)
        val c = vm.state.value as ConversationUiState.Content
        assertEquals(2, c.thread.messages.size)
    }

    @Test fun load_404_maps_to_not_found() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.NotFound) }
        vm.load()?.join()
        assertEquals(ErrorKind.NOT_FOUND, (vm.state.value as ConversationUiState.Error).kind)
    }

    @Test fun load_401_maps_to_auth() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.Unauthorized) }
        vm.load()?.join()
        assertEquals(ErrorKind.AUTH, (vm.state.value as ConversationUiState.Error).kind)
    }

    @Test fun load_500_maps_to_network() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.InternalServerError) }
        vm.load()?.join()
        val s = vm.state.value as ConversationUiState.Error
        assertEquals(ErrorKind.NETWORK, s.kind)
    }

    @Test fun request_spec_posts_canonical_message() = runTest {
        val postedTexts = mutableListOf<String>()
        val afterRequestJson = """
            {
              "id": "t1",
              "subject": "Hello world",
              "awaiting_reply": true,
              "messages": [
                {"id":"m1","thread_id":"t1","role":"human","text":"Hey there","created_at":"2026-06-22T10:00:00Z"},
                {"id":"m2","thread_id":"t1","role":"agent","text":"Hi! How can I help?","created_at":"2026-06-22T10:01:00Z"},
                {"id":"m3","thread_id":"t1","role":"human","text":"Please turn this thread into a spec.","created_at":"2026-06-22T10:02:00Z"}
              ]
            }
        """.trimIndent()
        val vm = vm(this) { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t1/messages") && req.method == HttpMethod.Post -> {
                    val body = (req.body as io.ktor.http.content.TextContent).text
                    postedTexts.add(body)
                    respond(afterRequestJson, HttpStatusCode.OK, jsonHdr)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        vm.requestSpec()?.join()
        val c = vm.state.value as ConversationUiState.Content
        assertEquals(3, c.thread.messages.size)
        assertEquals(1, postedTexts.size)
        assert(postedTexts[0].contains("Please turn this thread into a spec.")) {
            "Expected canonical spec request text in POST body, got: ${postedTexts[0]}"
        }
    }

    @Test
    fun draft_persists_across_a_reload() = runTest {
        val handler: MockRequestHandler = { respond(threadJson, HttpStatusCode.OK, jsonHdr) }
        val v = vm(this, handler)
        v.load(); advanceUntilIdle()
        v.onDraftChange("half-typed steering note")
        v.load(); advanceUntilIdle()                    // a reload flips state to Loading→Content
        assertEquals("half-typed steering note", v.draft.value)   // draft survives
    }

    @Test
    fun send_clears_draft_on_success() = runTest {
        var n = 0
        val handler: MockRequestHandler = {
            if (it.method == HttpMethod.Post) respond(afterSendJson, HttpStatusCode.OK, jsonHdr)
            else respond(threadJson, HttpStatusCode.OK, jsonHdr)
        }
        val v = vm(this, handler)
        v.load(); advanceUntilIdle()
        v.onDraftChange("ship it")
        v.send("ship it")?.join(); advanceUntilIdle()
        assertEquals("", v.draft.value)                 // cleared on success
    }

    @Test
    fun send_keeps_draft_on_failure() = runTest {
        val handler: MockRequestHandler = {
            if (it.method == HttpMethod.Post) respondError(HttpStatusCode.InternalServerError)
            else respond(threadJson, HttpStatusCode.OK, jsonHdr)
        }
        val v = vm(this, handler)
        v.load(); advanceUntilIdle()
        v.onDraftChange("don't lose me")
        v.send("don't lose me")?.join(); advanceUntilIdle()
        assertEquals("don't lose me", v.draft.value)     // kept on failure
    }
}
