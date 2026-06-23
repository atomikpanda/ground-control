package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.ui.messages.MessagesUiState
import com.atomikpanda.groundcontrol.ui.messages.MessagesViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun repo() = ThreadsRepository(SpecApi(HttpClient(MockEngine {
        respond(
            """[{"id":"t1","subject":"Hello","awaiting_reply":true,"last_message":"Hi there","updated_at":"2026-06-22T10:00:00Z"},
               {"id":"t2","subject":"World","awaiting_reply":false,"last_message":"Done","updated_at":"2026-06-22T11:00:00Z"}]""",
            HttpStatusCode.OK, jsonHdr
        )
    }) { install(ContentNegotiation) { json(buildJson()) } }))

    @Test fun sections_carry_workspace_name_and_connection_id_and_threads() = runTest {
        val vm = MessagesViewModel(repo(), {
            listOf(WorkspaceConnection("42", "http://h:47100", null, "ws-alpha"))
        }, this)
        vm.refresh()?.join()
        val content = vm.state.value as MessagesUiState.Content
        val sec = content.sections[0]
        assertEquals("ws-alpha", sec.workspaceName)
        assertEquals("42", sec.connectionId)
        val threads = sec.threads.getOrThrow()
        assertEquals(2, threads.size)
        assertEquals("t1", threads[0].id)
        assertEquals("t2", threads[1].id)
    }

    @Test fun empty_connections_yields_empty_config() = runTest {
        val vm = MessagesViewModel(repo(), { emptyList() }, this)
        vm.refresh()
        assertEquals(MessagesUiState.EmptyConfig, vm.state.value)
    }
}
