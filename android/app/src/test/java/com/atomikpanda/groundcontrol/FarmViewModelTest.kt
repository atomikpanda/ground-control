package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.farm.FarmUiState
import com.atomikpanda.groundcontrol.ui.farm.FarmPhase
import com.atomikpanda.groundcontrol.ui.farm.FarmViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FarmViewModelTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(ContentType, "application/json")

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(scope: CoroutineScope, fail: Boolean = false) = FarmViewModel(
        SpecApi(HttpClient(MockEngine {
            if (fail) respond("boom", HttpStatusCode.InternalServerError)
            else respond(
                """[{"id":"a","kind":"feature","title":"A","phase":"inbox"},
                    {"id":"b","kind":"bug","title":"B","phase":"in_flight"}]""",
                HttpStatusCode.OK, jsonHdr,
            )
        }) { mshipDefaults() }),
        conn, testScope = scope,
    )

    @Test fun loads_and_groups_items() = runTest {
        val vm = vm(this); vm.refresh().join()
        val c = vm.state.value as FarmUiState.Content
        assertEquals(listOf(FarmPhase.INBOX, FarmPhase.IN_FLIGHT), c.groups.map { it.phase })
        assertTrue(!c.errored)
    }

    @Test fun error_is_isolated_not_crashing() = runTest {
        val vm = vm(this, fail = true); vm.refresh().join()
        val c = vm.state.value as FarmUiState.Content
        assertTrue(c.errored && c.groups.isEmpty())
    }

    private fun vmWithUnattendedEndpoint(scope: CoroutineScope, unattendedFails: Boolean) =
        FarmViewModel(
            SpecApi(HttpClient(MockEngine { req ->
                if (req.url.encodedPath.endsWith("/unattended")) {
                    if (unattendedFails) respond("boom", HttpStatusCode.InternalServerError)
                    else respond("""{"id":"a","unattended":true}""", HttpStatusCode.OK, jsonHdr)
                } else {
                    respond(
                        """[{"id":"a","kind":"feature","title":"A","phase":"inbox","unattended":false}]""",
                        HttpStatusCode.OK, jsonHdr,
                    )
                }
            }) { mshipDefaults() }),
            conn, testScope = scope,
        )

    @Test fun toggle_unattended_optimistically_updates() = runTest {
        val vm = vmWithUnattendedEndpoint(this, unattendedFails = false)
        vm.refresh().join()
        val item = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        vm.setUnattended(item, true).join()
        val updated = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertTrue(updated.unattended)
    }

    @Test fun toggle_unattended_reverts_on_failure() = runTest {
        val vm = vmWithUnattendedEndpoint(this, unattendedFails = true)
        vm.refresh().join()
        val item = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        vm.setUnattended(item, true).join()
        val after = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertFalse(after.unattended)
    }
}
