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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    @Test fun load_marks_thread_seen() = runTest {
        val seenPosts = mutableListOf<String>()
        val vm = vm(this) { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t1/seen") && req.method == HttpMethod.Post -> {
                    seenPosts += req.url.encodedPath
                    respond("""{"id":"t1","subject":"s","messages":[]}""", HttpStatusCode.OK, jsonHdr)
                }
                req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        advanceUntilIdle()
        // mark-seen is a fire-and-forget launch whose POST settles on the MockEngine's IO
        // dispatcher (off the virtual clock); wait for the side effect in real time.
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 2_000
            while (seenPosts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
        assertEquals(1, seenPosts.size)
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

    @Test
    fun draft_persists_across_a_reload() = runTest {
        val handler: MockRequestHandler = { respond(threadJson, HttpStatusCode.OK, jsonHdr) }
        val v = vm(this, handler)
        v.load()?.join()
        v.onDraftChange("half-typed steering note")
        v.load()?.join()                                // a reload flips state to Loading→Content
        assertEquals("half-typed steering note", v.draft.value)   // draft survives
    }

    @Test
    fun send_clears_draft_on_success() = runTest {
        val handler: MockRequestHandler = {
            if (it.method == HttpMethod.Post) respond(afterSendJson, HttpStatusCode.OK, jsonHdr)
            else respond(threadJson, HttpStatusCode.OK, jsonHdr)
        }
        val v = vm(this, handler)
        v.load()?.join()
        v.onDraftChange("ship it")
        v.send("ship it")?.join()
        assertEquals("", v.draft.value)                 // cleared on success
    }

    @Test
    fun send_keeps_draft_on_failure() = runTest {
        val handler: MockRequestHandler = {
            if (it.method == HttpMethod.Post) respondError(HttpStatusCode.InternalServerError)
            else respond(threadJson, HttpStatusCode.OK, jsonHdr)
        }
        val v = vm(this, handler)
        v.load()?.join()
        v.onDraftChange("don't lose me")
        v.send("don't lose me")?.join()
        assertEquals("don't lose me", v.draft.value)     // kept on failure
    }

    private val waitHitJson = """{"threads":[{"id":"t1","subject":"s","updated_at":"2026-06-22T10:10:00Z"}],"cursor":"2026-06-22T10:10:00Z","timed_out":false}"""
    private val waitMissJson = """{"threads":[{"id":"other","subject":"s","updated_at":"2026-06-22T10:10:00Z"}],"cursor":"2026-06-22T10:10:00Z","timed_out":false}"""
    private val refreshedThreadJson = """
        {"id":"t1","subject":"Hello world","awaiting_reply":false,
         "messages":[
           {"id":"m1","thread_id":"t1","role":"human","text":"Hey there","created_at":"2026-06-22T10:00:00Z"},
           {"id":"m3","thread_id":"t1","role":"agent","text":"LIVE REPLY","created_at":"2026-06-22T10:10:00Z"}
         ]}
    """.trimIndent()

    @Test
    fun pollOnce_refreshes_open_thread_and_advances_cursor() = runTest {
        val handler: MockRequestHandler = { req ->
            if (req.url.parameters["wait"] == "1") respond(waitHitJson, HttpStatusCode.OK, jsonHdr)
            else respond(refreshedThreadJson, HttpStatusCode.OK, jsonHdr)   // GET /threads/t1
        }
        val v = vm(this, handler)
        v.load()?.join()
        val next = v.pollOnce("2026-06-22T10:00:00Z")                       // one iteration, terminates
        val content = v.state.value as ConversationUiState.Content
        assertEquals("LIVE REPLY", content.thread.messages.last().text)     // refreshed live, no manual load
        assertEquals("2026-06-22T10:10:00Z", next)                          // cursor advanced
    }

    @Test
    fun pollOnce_does_not_refresh_when_this_thread_unchanged() = runTest {
        val handler: MockRequestHandler = { req ->
            if (req.url.parameters["wait"] == "1") respond(waitMissJson, HttpStatusCode.OK, jsonHdr)
            else respond(threadJson, HttpStatusCode.OK, jsonHdr)
        }
        val v = vm(this, handler)
        v.load()?.join()
        val before = (v.state.value as ConversationUiState.Content).thread.messages.size
        v.pollOnce("2026-06-22T10:00:00Z")
        assertEquals(before, (v.state.value as ConversationUiState.Content).thread.messages.size)  // unchanged
    }

    @Test
    fun pollOnce_returns_same_cursor_on_network_error() = runTest {
        val handler: MockRequestHandler = { req ->
            if (req.url.parameters["wait"] == "1") respondError(HttpStatusCode.InternalServerError)
            else respond(threadJson, HttpStatusCode.OK, jsonHdr)
        }
        val v = vm(this, handler)
        v.load()?.join()
        val next = v.pollOnce("2026-06-22T10:00:00Z")
        assertEquals("2026-06-22T10:00:00Z", next)                          // no crash; cursor unchanged
        assertNotNull(v.state.value as? ConversationUiState.Content)        // conversation intact
    }

    @Test
    fun pollOnce_preserves_sendError_banner_on_refresh() = runTest {
        val handler: MockRequestHandler = { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t1/messages") && req.method == HttpMethod.Post ->
                    respondError(HttpStatusCode.InternalServerError)         // send fails -> sendError set
                req.url.parameters["wait"] == "1" ->
                    respond(waitHitJson, HttpStatusCode.OK, jsonHdr)         // agent reply arrives
                else -> respond(refreshedThreadJson, HttpStatusCode.OK, jsonHdr)  // GET /threads/t1 refresh
            }
        }
        val v = vm(this, handler)
        v.load()?.join()
        v.send("oops")?.join()                                              // fails -> Content(sendError != null)
        assertNotNull((v.state.value as ConversationUiState.Content).sendError)
        v.pollOnce("2026-06-22T10:00:00Z")                                  // refresh while the banner is up
        val content = v.state.value as ConversationUiState.Content
        assertEquals("LIVE REPLY", content.thread.messages.last().text)     // refreshed to the new reply
        assertNotNull(content.sendError)                                    // banner preserved across refresh
    }

    // --- MOS-224: in-thread activity strip (journal fetch/merge) ---

    // GET /threads/t2 response -- linked to a task (task_slug), unlike threadJson above.
    private val threadWithTaskJson = """
        {
          "id": "t2",
          "subject": "Ship the strip",
          "task_slug": "mos-224",
          "awaiting_reply": true,
          "messages": [
            {"id":"m1","thread_id":"t2","role":"human","text":"how's it going","created_at":"2026-06-22T10:00:00Z"}
          ]
        }
    """.trimIndent()

    private val journalJson3Entries = """
        [
          {"timestamp":"2026-06-22T09:57:00Z","message":"spawned","action":"spawned"},
          {"timestamp":"2026-06-22T09:58:00Z","message":"wrote parser","action":"wrote parser"},
          {"timestamp":"2026-06-22T09:59:00Z","message":"ran tests","action":"ran tests","test_state":"pass"}
        ]
    """.trimIndent()

    private fun vmFor(scope: CoroutineScope, threadId: String, handler: MockRequestHandler) =
        ConversationViewModel(
            ThreadsRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() })),
            conn, threadId, testScope = scope,
        )

    @Test fun load_fetches_last_two_journal_entries_when_thread_has_task_slug() = runTest {
        val v = vmFor(this, "t2") { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t2") && req.method == HttpMethod.Get ->
                    respond(threadWithTaskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/journal/mos-224") && req.method == HttpMethod.Get ->
                    respond(journalJson3Entries, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        v.load()?.join()
        val c = v.state.value as ConversationUiState.Content
        assertEquals(2, c.journal.size)                          // last 2 of 3, not all 3
        assertEquals("wrote parser", c.journal[0].message)
        assertEquals("ran tests", c.journal[1].message)
    }

    @Test fun load_skips_journal_fetch_when_task_slug_is_null() = runTest {
        var journalRequested = false
        val v = vmFor(this, "t1") { req ->
            when {
                req.url.encodedPath.endsWith("/journal") || req.url.encodedPath.contains("/journal/") -> {
                    journalRequested = true
                    respond(journalJson3Entries, HttpStatusCode.OK, jsonHdr)
                }
                req.url.encodedPath.endsWith("/threads/t1") && req.method == HttpMethod.Get ->
                    respond(threadJson, HttpStatusCode.OK, jsonHdr)   // no task_slug
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        v.load()?.join()
        val c = v.state.value as ConversationUiState.Content
        assertEquals(emptyList<Any>(), c.journal)
        assertFalse("journal endpoint must not be hit for a task-less thread", journalRequested)
    }

    @Test fun load_degrades_to_empty_journal_when_journal_fetch_fails() = runTest {
        val v = vmFor(this, "t2") { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t2") && req.method == HttpMethod.Get ->
                    respond(threadWithTaskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/journal/mos-224") ->
                    respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        v.load()?.join()
        val c = v.state.value as ConversationUiState.Content   // conversation still loads
        assertEquals("Ship the strip", c.thread.subject)
        assertEquals(emptyList<Any>(), c.journal)               // journal degrades to empty, not an Error state
    }

    @Test fun pollOnce_refreshes_journal_even_when_thread_itself_is_unchanged() = runTest {
        var journalCalls = 0
        val v = vmFor(this, "t2") { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t2") && req.method == HttpMethod.Get ->
                    respond(threadWithTaskJson, HttpStatusCode.OK, jsonHdr)
                req.url.parameters["wait"] == "1" ->
                    respond(waitMissJson, HttpStatusCode.OK, jsonHdr)   // "other" thread changed, not t2
                req.url.encodedPath.endsWith("/journal/mos-224") && req.method == HttpMethod.Get -> {
                    journalCalls++
                    // First call (during load()) sees one entry; the poll should pick up a second.
                    val body = if (journalCalls == 1) """[{"timestamp":"2026-06-22T09:58:00Z","message":"wrote parser"}]"""
                               else journalJson3Entries
                    respond(body, HttpStatusCode.OK, jsonHdr)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        v.load()?.join()
        assertEquals(1, (v.state.value as ConversationUiState.Content).journal.size)
        v.pollOnce("2026-06-22T10:00:00Z")
        val c = v.state.value as ConversationUiState.Content
        assertEquals(2, c.journal.size)                          // refreshed via the poll cadence
        assertEquals("ran tests", c.journal.last().message)
        assertEquals(2, journalCalls)                             // load() + one poll tick, no extra loop
    }

    @Test fun pollOnce_refreshes_journal_alongside_thread_when_thread_changes() = runTest {
        val v = vmFor(this, "t2") { req ->
            when {
                req.url.parameters["wait"] == "1" ->
                    respond("""{"threads":[{"id":"t2","subject":"s","updated_at":"2026-06-22T10:10:00Z"}],"cursor":"2026-06-22T10:10:00Z","timed_out":false}""", HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/journal/mos-224") && req.method == HttpMethod.Get ->
                    respond(journalJson3Entries, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t2") && req.method == HttpMethod.Get ->
                    respond(threadWithTaskJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        v.load()?.join()
        val next = v.pollOnce("2026-06-22T10:00:00Z")   // "wait" reports t2 changed -> full thread+journal refetch
        val c = v.state.value as ConversationUiState.Content
        assertEquals(2, c.journal.size)                 // journal came along with the refreshed thread
        assertEquals("2026-06-22T10:10:00Z", next)
    }

    // Greptile finding on PR #40: pollOnce used to await the /journal fetch before applying
    // the refreshed thread, so a slow (or hung) journal endpoint delayed the user seeing the
    // agent's reply by up to the OkHttp read timeout. Gate the *poll's* journal call (but not
    // load()'s) so it never resolves during this test, and prove pollOnce still returns and
    // applies the refreshed thread/messages -- carrying the last-known journal forward rather
    // than blocking on the new one. Then release the gate and confirm the journal refresh
    // does eventually land (fire-and-forget, not silently dropped).
    @Test fun pollOnce_delivers_thread_refresh_without_waiting_on_journal_fetch() = runTest {
        val journalGate = CompletableDeferred<Unit>()
        var journalCalls = 0
        val v = vmFor(this, "t2") { req ->
            when {
                req.url.parameters["wait"] == "1" ->
                    respond("""{"threads":[{"id":"t2","subject":"s","updated_at":"2026-06-22T10:10:00Z"}],"cursor":"2026-06-22T10:10:00Z","timed_out":false}""", HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/journal/mos-224") && req.method == HttpMethod.Get -> {
                    journalCalls++
                    if (journalCalls == 1) {
                        // load()'s journal fetch: resolves normally, 1 entry.
                        respond("""[{"timestamp":"2026-06-22T09:58:00Z","message":"wrote parser"}]""", HttpStatusCode.OK, jsonHdr)
                    } else {
                        // The poll's journal refresh: hangs until this test releases it below.
                        journalGate.await()
                        respond(journalJson3Entries, HttpStatusCode.OK, jsonHdr)
                    }
                }
                req.url.encodedPath.endsWith("/threads/t2") && req.method == HttpMethod.Get ->
                    respond(threadWithTaskJson, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        v.load()?.join()
        assertEquals(1, (v.state.value as ConversationUiState.Content).journal.size)   // sanity: load's journal landed

        // Run pollOnce on a real dispatcher under a real (wall-clock) timeout: if the fix
        // regresses to awaiting the journal fetch before applying the thread refresh, this
        // times out instead of hanging the suite -- the gate above isn't released until after
        // this call returns.
        val next = withContext(Dispatchers.IO) {
            withTimeoutOrNull(3_000) { v.pollOnce("2026-06-22T10:00:00Z") }
        }
        assertEquals("2026-06-22T10:10:00Z", next)
        val afterPoll = v.state.value as ConversationUiState.Content
        assertEquals("Ship the strip", afterPoll.thread.subject)   // reply delivered immediately...
        assertEquals(1, afterPoll.journal.size)                     // ...journal not yet refreshed (still hung)

        // Let the deferred journal refresh resolve and confirm it eventually lands (fire-and-
        // forget, not dropped) -- wait for it in real time since it settles on the MockEngine's
        // IO dispatcher, off the virtual clock (mirrors load_marks_thread_seen above).
        advanceUntilIdle()
        journalGate.complete(Unit)
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 2_000
            while ((v.state.value as ConversationUiState.Content).journal.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
        assertEquals(2, (v.state.value as ConversationUiState.Content).journal.size)
    }

    @Test fun send_preserves_journal_across_a_successful_send() = runTest {
        val v = vmFor(this, "t2") { req ->
            when {
                req.url.encodedPath.endsWith("/threads/t2/messages") && req.method == HttpMethod.Post ->
                    respond(threadWithTaskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/threads/t2") && req.method == HttpMethod.Get ->
                    respond(threadWithTaskJson, HttpStatusCode.OK, jsonHdr)
                req.url.encodedPath.endsWith("/journal/mos-224") && req.method == HttpMethod.Get ->
                    respond(journalJson3Entries, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        v.load()?.join()
        assertEquals(2, (v.state.value as ConversationUiState.Content).journal.size)
        v.send("go on")?.join()
        // send()'s success path doesn't refetch the journal -- it must carry the last-known
        // journal forward rather than resetting it to empty.
        assertEquals(2, (v.state.value as ConversationUiState.Content).journal.size)
    }
}
