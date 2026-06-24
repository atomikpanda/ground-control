package com.atomikpanda.groundcontrol

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
}
