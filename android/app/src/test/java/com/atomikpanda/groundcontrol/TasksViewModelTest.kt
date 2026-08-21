package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertTrue
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
        val vm = TasksViewModel(repo(), connectionState(listOf(WorkspaceConnection("1", "http://h:47100", null, "ws-a"))), backgroundScope)
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

    @Test fun connection_state_loading_error_empty_add_and_remove_reuse_one_owner() = runTest {
        val a = WorkspaceConnection("a", "http://a:47100", null, "ws-a")
        val b = WorkspaceConnection("b", "http://b:47100", null, "ws-b")
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
        val vm = TasksViewModel(repo(), source, backgroundScope)
        assertTrue(vm.state.value is TasksUiState.Loading)

        source.value = ConnectionState.Error(IllegalStateException("disk"))
        assertTrue(vm.state.first { it is TasksUiState.ConnectionsUnavailable } is TasksUiState.ConnectionsUnavailable)

        source.value = ConnectionState.Ready(emptyList())
        assertEquals(TasksUiState.EmptyConfig, vm.state.first { it is TasksUiState.EmptyConfig })

        source.value = ConnectionState.Ready(listOf(a))
        assertEquals(
            listOf("a"),
            (vm.state.first { (it as? TasksUiState.Content)?.sections?.map { section -> section.connectionId } == listOf("a") }
                as TasksUiState.Content).sections.map { it.connectionId },
        )

        source.value = ConnectionState.Ready(listOf(a, b))
        assertEquals(
            listOf("a", "b"),
            (vm.state.first { (it as? TasksUiState.Content)?.sections?.map { section -> section.connectionId } == listOf("a", "b") }
                as TasksUiState.Content).sections.map { it.connectionId },
        )

        source.value = ConnectionState.Ready(listOf(b))
        assertEquals(
            listOf("b"),
            (vm.state.first { (it as? TasksUiState.Content)?.sections?.map { section -> section.connectionId } == listOf("b") }
                as TasksUiState.Content).sections.map { it.connectionId },
        )
    }

    @Test fun same_id_route_and_token_replacement_starts_one_new_authoritative_tasks_load() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val requests = mutableListOf<Pair<String, String?>>()
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val vm = TasksViewModel(
            TasksRepository(SpecApi(HttpClient(MockEngine { request ->
                requests += request.url.host to request.headers[HttpHeaders.Authorization]
                respond("[]", HttpStatusCode.OK, jsonHdr)
            }) { install(ContentNegotiation) { json(buildJson()) } })),
            source,
            backgroundScope,
        )

        vm.state.first { it is TasksUiState.Content }
        source.value = ConnectionState.Ready(listOf(replacement))
        val content = vm.state.first {
            (it as? TasksUiState.Content)?.sections?.singleOrNull()?.workspaceName == replacement.workspaceName
        } as TasksUiState.Content

        assertEquals(
            listOf("old" to "Bearer old-token", "new" to "Bearer new-token"),
            requests,
        )
        assertEquals(listOf(replacement.id), content.sections.map { it.connectionId })
    }

    @Test fun two_repeated_manual_tasks_refreshes_publish_only_newest_after_delayed_old_completion() = runTest {
        val firstManualStarted = CompletableDeferred<Unit>()
        val firstManualCancelled = CompletableDeferred<Unit>()
        var taskLoads = 0
        val connection = WorkspaceConnection("a", "http://a:47100", null, "ws-a")
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(connection)))
        val vm = TasksViewModel(
            TasksRepository(SpecApi(HttpClient(MockEngine {
                taskLoads += 1
                when (taskLoads) {
                    2 -> {
                        firstManualStarted.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                            respond("""[{"slug":"old","phase":"dev","branch":"old"}]""", HttpStatusCode.OK, jsonHdr)
                        } finally {
                            firstManualCancelled.complete(Unit)
                        }
                    }
                    3 -> respond("""[{"slug":"new","phase":"dev","branch":"new"}]""", HttpStatusCode.OK, jsonHdr)
                    else -> respond("[]", HttpStatusCode.OK, jsonHdr)
                }
            }) { install(ContentNegotiation) { json(buildJson()) } })),
            source,
            backgroundScope,
        )

        vm.state.first { it is TasksUiState.Content }
        vm.refresh()
        firstManualStarted.await()
        val secondManual = vm.refresh()
        firstManualCancelled.await()
        secondManual?.join()
        assertEquals(listOf("new"), ((vm.state.value as TasksUiState.Content).sections.single().groups.getOrThrow().single().tasks.map { it.slug }))
    }
}
