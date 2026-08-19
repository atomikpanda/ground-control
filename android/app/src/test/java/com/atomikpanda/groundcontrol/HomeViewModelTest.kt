package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.DIRECTORY_STALE_MS
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.home.HomeUiState
import com.atomikpanda.groundcontrol.ui.home.HomeViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
class HomeViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun repo() = HomeFeedRepository(SpecApi(HttpClient(MockEngine { req ->
        val p = req.url.encodedPath
        when {
            // ws-a (host a) has 1 needs_review spec; ws-b (host b) has none
            p.endsWith("/specs") -> respond(
                if (req.url.host == "a") """[{"id":"s1","title":"Dark","status":"needs_review"}]""" else "[]",
                HttpStatusCode.OK, jsonHdr)
            else -> respond("[]", HttpStatusCode.OK, jsonHdr)
        }
    }) { mshipDefaults() }))

    /** ws-a (host a) serves 1 needs_review spec; every call to host "bad" 500s. */
    private fun repoWithFailingHost() = HomeFeedRepository(SpecApi(HttpClient(MockEngine { req ->
        if (req.url.host == "bad") return@MockEngine respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
        when {
            req.url.encodedPath.endsWith("/specs") -> respond(
                """[{"id":"s1","title":"Dark","status":"needs_review"}]""", HttpStatusCode.OK, jsonHdr)
            else -> respond("[]", HttpStatusCode.OK, jsonHdr)
        }
    }) { mshipDefaults() }))

    private val conns = listOf(
        WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
        WorkspaceConnection("b", "http://b:47100", null, "ws-b"),
    )

    @Test fun no_connections_yields_empty_config() = runTest {
        val vm = HomeViewModel(repo(), { emptyList() }, this)
        vm.refresh(); advanceUntilIdle()
        assertEquals(HomeUiState.EmptyConfig, vm.state.value)
    }

    @Test fun rail_pins_all_first_then_workspaces_by_count_desc() = runTest {
        val vm = HomeViewModel(repo(), { conns }, this)
        vm.refresh()?.join()
        val c = vm.state.value as HomeUiState.Content
        assertEquals(listOf(null, "a", "b"), c.rail.map { it.connectionId })  // All, then ws-a (1 item) before ws-b (0)
        assertEquals(1, c.rail.first().count)                                  // "All" count == total items
        assertEquals(1, c.items.size)
    }

    @Test fun select_filters_items_to_one_workspace() = runTest {
        val vm = HomeViewModel(repo(), { conns }, this)
        vm.refresh()?.join()
        vm.select("b")
        val c = vm.state.value as HomeUiState.Content
        assertEquals("b", c.selectedConnectionId)
        assertEquals(0, c.items.size)                                         // ws-b has nothing
    }

    @Test fun all_count_reflects_only_successful_items_when_error_present() = runTest {
        val vm = HomeViewModel(repoWithFailingHost(), {
            listOf(
                WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
                WorkspaceConnection("bad", "http://bad:47100", null, "ws-bad"),
            )
        }, this)
        vm.refresh()?.join()
        val c = vm.state.value as HomeUiState.Content
        assertEquals(1, c.rail.first().count)                                 // "All" count excludes errored ws-bad
        assertEquals(listOf("ws-bad"), c.errors.map { it.workspaceName })     // failed workspace surfaced as error
    }

    @Test fun refresh_classifies_errors_with_post_request_host_freshness() = runTest {
        val connection = WorkspaceConnection(
            "a",
            "http://a:47100",
            workspaceName = "ws-a",
            hostId = "host-a",
            workspaceId = "ws-a",
        )
        val hosts = MutableStateFlow(
            listOf(
                HostConnection(
                    hostId = "host-a",
                    state = "online",
                    lastContactAtMillis = 0L,
                ),
            ),
        )
        val repository = HomeFeedRepository(
            SpecApi(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/threads")) {
                            respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
                        } else {
                            hosts.value = hosts.value.map {
                                it.copy(lastContactAtMillis = System.currentTimeMillis())
                            }
                            respond("[]", HttpStatusCode.OK, jsonHdr)
                        }
                    },
                ) { mshipDefaults() },
            ),
        )
        val vm = HomeViewModel(repository, { listOf(connection) }, backgroundScope, hosts)

        vm.refresh()?.join()

        val content = vm.state.value as HomeUiState.Content
        assertEquals(HostLadderState.WORKSPACE_DEGRADED, content.errors.single().ladderState)
    }

    @Test fun home_error_labels_recompute_at_the_stale_deadline() = runTest {
        val connection = WorkspaceConnection(
            id = "bad",
            baseUrl = "http://bad:47100",
            workspaceName = "ws-bad",
            hostId = "host-a",
            workspaceId = "ws-a",
        )
        val hosts = MutableStateFlow(
            listOf(
                HostConnection(
                    hostId = "host-a",
                    state = "online",
                    runnerState = "disabled",
                    lastContactAtMillis = 0L,
                ),
            ),
        )
        var now = 0L
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = HomeViewModel(
            repo = repoWithFailingHost(),
            connectionsProvider = { listOf(connection) },
            testScope = scope,
            hosts = hosts,
            nowMillis = { now },
        )
        vm.refresh()?.join()
        assertEquals(
            HostLadderState.WORKSPACE_DEGRADED,
            (vm.state.value as HomeUiState.Content).errors.single().ladderState,
        )

        now = DIRECTORY_STALE_MS
        advanceTimeBy(DIRECTORY_STALE_MS)
        runCurrent()

        assertEquals(
            HostLadderState.STALE,
            (vm.state.value as HomeUiState.Content).errors.single().ladderState,
        )
        scope.cancel()
    }
}
