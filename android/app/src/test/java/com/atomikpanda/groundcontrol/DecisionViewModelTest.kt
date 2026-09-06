package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.messages.DecisionUiState
import com.atomikpanda.groundcontrol.ui.messages.DecisionViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DecisionViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(
        scope: CoroutineScope,
        connections: MutableStateFlow<ConnectionState>,
        handler: MockRequestHandler,
    ) = DecisionViewModel(
        repo = ThreadsRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() })),
        connectionId = conn.id,
        threadId = "t1",
        connectionState = connections,
        testScope = scope,
    )

    private val openDecision = """
        {"id":"t1","subject":"Choose deploy plan","needs_decision":true,
         "messages":[
           {"id":"d1","thread_id":"t1","role":"agent","kind":"decision","text":"Which plan?",
            "decision":{"options":["Ship","Hold"],"recommended":0,"allow_free_text":true,"multi":false}}
         ]}
    """.trimIndent()

    private val resolvedDecision = """
        {"id":"t1","subject":"Choose deploy plan","needs_decision":false,
         "resolved_through_message_id":"d1","messages":[
           {"id":"d1","thread_id":"t1","role":"agent","kind":"decision","text":"Which plan?",
            "decision":{"options":["Ship","Hold"]}}
         ]}
    """.trimIndent()

    private val postedDecision = """
        {"id":"t1","subject":"Choose deploy plan","needs_decision":false,
         "messages":[
           {"id":"d1","thread_id":"t1","role":"agent","kind":"decision","text":"Which plan?"},
           {"id":"r1","thread_id":"t1","role":"human","text":"Ship"}
         ]}
    """.trimIndent()

    @Test fun submit_refuses_a_prompt_replaced_during_refresh() = runTest {
        var gets = 0
        var posts = 0
        val connections = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(conn)))
        val viewModel = vm(this, connections) { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/threads/t1") -> {
                    gets++
                    respond(if (gets == 1) openDecision else resolvedDecision, HttpStatusCode.OK, jsonHdr)
                }
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/threads/t1/messages") -> {
                    posts++
                    respond(postedDecision, HttpStatusCode.OK, jsonHdr)
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHdr)
            }
        }

        viewModel.load().join()
        viewModel.submit("Ship")?.join()

        val state = viewModel.state.value as DecisionUiState.NoLongerActionable
        assertEquals("This question changed before your response was sent.", state.message)
        assertEquals(0, posts)
    }

    @Test fun submit_allows_only_one_post_while_receipt_is_pending() = runTest {
        val postStarted = CompletableDeferred<Unit>()
        val releasePost = CompletableDeferred<Unit>()
        var posts = 0
        val connections = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(conn)))
        val viewModel = vm(this, connections) { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/threads/t1") ->
                    respond(openDecision, HttpStatusCode.OK, jsonHdr)
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/threads/t1/messages") -> {
                    posts++
                    postStarted.complete(Unit)
                    releasePost.await()
                    respond(postedDecision, HttpStatusCode.OK, jsonHdr)
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHdr)
            }
        }

        viewModel.load().join()
        val first = viewModel.submit("Ship")
        assertNotNull(first)
        postStarted.await()
        assertNull(viewModel.submit("Hold"))
        releasePost.complete(Unit)
        first?.join()

        assertEquals(1, posts)
        assertTrue(viewModel.state.value is DecisionUiState.ResponseRecorded)
    }

    @Test fun connection_replacement_cancels_old_preflight_before_posting() = runTest {
        val old = conn.copy(baseUrl = "http://old:47100", token = "old-token")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token")
        val connections = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val oldPreflightStarted = CompletableDeferred<Unit>()
        val releaseOldPreflight = CompletableDeferred<Unit>()
        var oldGets = 0
        var oldPosts = 0
        val replacementDecision = openDecision.replace("Choose deploy plan", "Replacement decision")
        val viewModel = DecisionViewModel(
            repo = ThreadsRepository(SpecApi(HttpClient(MockEngine { request ->
                when {
                    request.url.host == "old" && request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/threads/t1") -> {
                        oldGets++
                        if (oldGets == 1) {
                            respond(openDecision, HttpStatusCode.OK, jsonHdr)
                        } else {
                            oldPreflightStarted.complete(Unit)
                            releaseOldPreflight.await()
                            respond(openDecision, HttpStatusCode.OK, jsonHdr)
                        }
                    }
                    request.url.host == "old" && request.method == HttpMethod.Post -> {
                        oldPosts++
                        respond(postedDecision, HttpStatusCode.OK, jsonHdr)
                    }
                    request.url.host == "new" && request.method == HttpMethod.Get ->
                        respond(replacementDecision, HttpStatusCode.OK, jsonHdr)
                    else -> respond("{}", HttpStatusCode.NotFound, jsonHdr)
                }
            }) { mshipDefaults() })),
            connectionId = old.id,
            threadId = "t1",
            connectionState = connections,
            testScope = this,
        )

        viewModel.load().join()
        val submission = viewModel.submit("Ship")
        oldPreflightStarted.await()
        connections.value = ConnectionState.Ready(listOf(replacement))
        val replacementContent = viewModel.state.first {
            it is DecisionUiState.Content && it.thread.subject == "Replacement decision"
        } as DecisionUiState.Content
        releaseOldPreflight.complete(Unit)
        submission?.join()

        assertEquals("Replacement decision", replacementContent.thread.subject)
        assertEquals(0, oldPosts)
    }
}
