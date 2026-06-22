package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.ui.tasks.TaskGroup
import com.atomikpanda.groundcontrol.ui.tasks.TasksUiState
import com.atomikpanda.groundcontrol.ui.tasks.TasksViewModel
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
class TasksViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun repo() = TasksRepository(SpecApi(HttpClient(MockEngine {
        respond("""[{"slug":"act","phase":"dev","branch":"b","finished_at":null},
                    {"slug":"done","phase":"review","branch":"b","finished_at":"2026-06-22T00:00:00Z"}]""",
            HttpStatusCode.OK, jsonHdr)
    }) { install(ContentNegotiation) { json(buildJson()) } }))

    @Test fun groups_workspace_then_active_finished() = runTest {
        val vm = TasksViewModel(repo(), {
            listOf(WorkspaceConnection("1", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        val content = vm.state.value as TasksUiState.Content
        val sec = content.sections[0]
        assertEquals("ws-a", sec.workspaceName)
        assertEquals("1", sec.connectionId)
        val groups = sec.groups.getOrThrow()
        assertEquals(listOf(TaskGroup.ACTIVE, TaskGroup.FINISHED), groups.map { it.group })
        assertEquals(listOf("act"), groups[0].tasks.map { it.slug })
        assertEquals(listOf("done"), groups[1].tasks.map { it.slug })
    }
}
