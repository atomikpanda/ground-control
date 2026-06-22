package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecGroup
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.ui.specs.InboxUiState
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxViewModel
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpecInboxViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun repo() = SpecRepository(SpecApi(HttpClient(MockEngine {
        respond(
            """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]},
                {"id":"b","title":"B","status":"needs_review","task_slug":null,"affected_repos":[]}]""",
            HttpStatusCode.OK, jsonHdr)
    }) { install(ContentNegotiation) { json(buildJson()) } }))

    @Test fun no_connections_yields_empty_config_state() = runTest {
        val vm = SpecInboxViewModel(repo(), { emptyList() }, this)
        vm.refresh(); advanceUntilIdle()
        assertEquals(InboxUiState.EmptyConfig, vm.state.value)
    }

    @Test fun loads_and_groups_workspace_then_status() = runTest {
        val vm = SpecInboxViewModel(repo(), {
            listOf(WorkspaceConnection("1", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        val content = vm.state.value as InboxUiState.Content
        assertEquals(1, content.sections.size)
        val sec = content.sections[0]
        assertEquals("ws-a", sec.workspaceName)
        // ordered groups: NEEDS_REVIEW before READY_TO_DISPATCH
        assertEquals(
            listOf(SpecGroup.NEEDS_REVIEW, SpecGroup.READY_TO_DISPATCH),
            sec.groups.getOrThrow().map { it.group },
        )
        assertEquals(listOf("b"), sec.groups.getOrThrow()[0].specs.map { it.id })
    }

    @Test fun section_carries_connection_id_for_navigation() = runTest {
        val vm = SpecInboxViewModel(repo(), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        val content = vm.state.value as InboxUiState.Content
        assertEquals("conn-7", content.sections[0].connectionId)
    }
}
