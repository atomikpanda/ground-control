package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
        val vm = HomeViewModel(repo(), connectionState(emptyList()), backgroundScope)
        vm.refresh(); advanceUntilIdle()
        assertEquals(HomeUiState.EmptyConfig, vm.state.value)
    }

    @Test fun rail_pins_all_first_then_workspaces_by_count_desc() = runTest {
        val vm = HomeViewModel(repo(), connectionState(conns), backgroundScope)
        vm.refresh()?.join()
        val c = vm.state.value as HomeUiState.Content
        assertEquals(listOf(null, "a", "b"), c.rail.map { it.connectionId })  // All, then ws-a (1 item) before ws-b (0)
        assertEquals(1, c.rail.first().count)                                  // "All" count == total items
        assertEquals(1, c.items.size)
    }

    @Test fun select_filters_items_to_one_workspace() = runTest {
        val vm = HomeViewModel(repo(), connectionState(conns), backgroundScope)
        vm.refresh()?.join()
        vm.select("b")
        val c = vm.state.value as HomeUiState.Content
        assertEquals("b", c.selectedConnectionId)
        assertEquals(0, c.items.size)                                         // ws-b has nothing
    }

    @Test fun all_count_reflects_only_successful_items_when_error_present() = runTest {
        val vm = HomeViewModel(repoWithFailingHost(), connectionState(listOf(
            WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
            WorkspaceConnection("bad", "http://bad:47100", null, "ws-bad"),
        )), backgroundScope)
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
        val vm = HomeViewModel(repository, connectionState(listOf(connection)), backgroundScope, hosts)

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
            connectionState = connectionState(listOf(connection)),
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
    @Test fun connection_state_loading_error_empty_add_and_remove_reuse_one_owner() = runTest {
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
        val vm = HomeViewModel(repo(), source, backgroundScope)
        assertTrue(vm.state.value is HomeUiState.Loading)

        source.value = ConnectionState.Error(IllegalStateException("disk"))
        assertTrue(vm.state.first { it is HomeUiState.ConnectionsUnavailable } is HomeUiState.ConnectionsUnavailable)

        source.value = ConnectionState.Ready(emptyList())
        assertEquals(HomeUiState.EmptyConfig, vm.state.first { it is HomeUiState.EmptyConfig })

        source.value = ConnectionState.Ready(listOf(conns[0]))
        assertEquals(
            listOf(null, "a"),
            (vm.state.first { (it as? HomeUiState.Content)?.rail?.map { chip -> chip.connectionId } == listOf(null, "a") }
                as HomeUiState.Content).rail.map { it.connectionId },
        )

        source.value = ConnectionState.Ready(conns)
        assertEquals(
            listOf(null, "a", "b"),
            (vm.state.first { (it as? HomeUiState.Content)?.rail?.map { chip -> chip.connectionId } == listOf(null, "a", "b") }
                as HomeUiState.Content).rail.map { it.connectionId },
        )

        source.value = ConnectionState.Ready(listOf(conns[1]))
        assertEquals(
            listOf(null, "b"),
            (vm.state.first { (it as? HomeUiState.Content)?.rail?.map { chip -> chip.connectionId } == listOf(null, "b") }
                as HomeUiState.Content).rail.map { it.connectionId },
        )
    }

    @Test fun same_id_route_and_token_replacement_hides_old_content_until_authoritative_home_load() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val newRequestStarted = CompletableDeferred<Unit>()
        val allowNewResponse = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<String, String?>>()
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val vm = HomeViewModel(
            HomeFeedRepository(SpecApi(HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/specs")) {
                    requests += request.url.host to request.headers[HttpHeaders.Authorization]
                    if (request.url.host == "new") {
                        newRequestStarted.complete(Unit)
                        allowNewResponse.await()
                    }
                }
                respond("[]", HttpStatusCode.OK, jsonHdr)
            }) { mshipDefaults() })),
            source,
            backgroundScope,
        )

        vm.state.first { it is HomeUiState.Content }
        source.value = ConnectionState.Ready(listOf(replacement))
        newRequestStarted.await()
        assertTrue(vm.state.value is HomeUiState.Loading)
        allowNewResponse.complete(Unit)

        val content = vm.state.first {
            (it as? HomeUiState.Content)
                ?.rail
                ?.singleOrNull { chip -> chip.connectionId == replacement.id }
                ?.label == replacement.workspaceName
        } as HomeUiState.Content
        assertEquals(
            listOf("old" to "Bearer old-token", "new" to "Bearer new-token"),
            requests,
        )
        assertEquals(listOf(null, replacement.id), content.rail.map { it.connectionId })
    }

    @Test fun same_snapshot_refresh_keeps_content_visible_until_an_error_loads() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefreshFailure = CompletableDeferred<Unit>()
        var specLoads = 0
        val vm = HomeViewModel(
            HomeFeedRepository(SpecApi(HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/specs")) {
                    specLoads += 1
                    if (specLoads == 2) {
                        refreshStarted.complete(Unit)
                        allowRefreshFailure.await()
                        return@MockEngine respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
                    }
                    return@MockEngine respond(
                        """[{"id":"visible","title":"Visible","status":"needs_review"}]""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                }
                respond("[]", HttpStatusCode.OK, jsonHdr)
            }) { mshipDefaults() })),
            connectionState(listOf(conns[0])),
            backgroundScope,
        )

        vm.state.first { it is HomeUiState.Content }
        val firstRefresh = vm.refresh()!!
        refreshStarted.await()
        val refreshing = vm.state.value as HomeUiState.Content
        assertEquals(listOf("approval:a:visible"), refreshing.items.map { it.key })
        vm.select(conns[0].id)
        assertEquals(conns[0].id, (vm.state.value as HomeUiState.Content).selectedConnectionId)


        allowRefreshFailure.complete(Unit)
        firstRefresh.join()
        val finished = vm.state.value as HomeUiState.Content
        assertEquals(listOf("a"), finished.errors.map { it.connectionId })
    }

    @Test fun removing_connections_while_a_refresh_is_pending_cannot_restore_old_content() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefresh = CompletableDeferred<Unit>()
        var specLoads = 0
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(conns[0])))
        val vm = HomeViewModel(
            HomeFeedRepository(SpecApi(HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/specs")) {
                    specLoads += 1
                    if (specLoads == 2) {
                        refreshStarted.complete(Unit)
                        allowRefresh.await()
                    }
                    return@MockEngine respond(
                        """[{"id":"old","title":"Old","status":"needs_review"}]""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                }
                respond("[]", HttpStatusCode.OK, jsonHdr)
            }) { mshipDefaults() })),
            source,
            backgroundScope,
        )

        vm.state.first { it is HomeUiState.Content }
        vm.refresh()
        refreshStarted.await()
        source.value = ConnectionState.Ready(emptyList())
        assertEquals(HomeUiState.EmptyConfig, vm.state.first { it is HomeUiState.EmptyConfig })
        allowRefresh.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeUiState.EmptyConfig, vm.state.value)
    }
    @Test fun selected_legacy_workspace_adopts_to_canonical_or_clears_when_ambiguous() = runTest {
        val legacy = WorkspaceConnection("legacy", "http://legacy:47100", null, "Legacy")
        val canonical = WorkspaceConnection("canonical", "http://canonical:47100", null, "Canonical", legacyConnectionIds = listOf(legacy.id))
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(legacy)))
        val vm = HomeViewModel(repo(), source, backgroundScope)

        vm.state.first { it is HomeUiState.Content }
        vm.select(legacy.id)
        source.value = ConnectionState.Ready(listOf(canonical))
        assertEquals(
            canonical.id,
            (vm.state.first { (it as? HomeUiState.Content)?.selectedConnectionId == canonical.id } as HomeUiState.Content)
                .selectedConnectionId,
        )

        source.value = ConnectionState.Ready(
            listOf(
                canonical.copy(id = "one"),
                canonical.copy(id = "two"),
            ),
        )
        assertEquals(
            null,
            (vm.state.first { it is HomeUiState.Content && it.selectedConnectionId == null } as HomeUiState.Content)
                .selectedConnectionId,
        )
    }
}
