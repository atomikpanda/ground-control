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
import kotlin.coroutines.cancellation.CancellationException
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

    private fun vmWithUnattendedEndpoint(
        scope: CoroutineScope,
        unattendedFails: Boolean,
        initialUnattended: Boolean = false,
    ) = FarmViewModel(
        SpecApi(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/unattended")) {
                if (unattendedFails) respond("boom", HttpStatusCode.InternalServerError)
                else respond("""{"id":"a","unattended":true}""", HttpStatusCode.OK, jsonHdr)
            } else {
                respond(
                    """[{"id":"a","kind":"feature","title":"A","phase":"inbox","unattended":$initialUnattended}]""",
                    HttpStatusCode.OK, jsonHdr,
                )
            }
        }) { mshipDefaults() }),
        conn, testScope = scope,
    )

    /** /unattended throws CancellationException directly (standing in for the scope being
     *  cancelled mid-request, e.g. the ViewModel being cleared) rather than failing normally. */
    private fun vmWithCancellingUnattendedEndpoint(scope: CoroutineScope) = FarmViewModel(
        SpecApi(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/unattended")) {
                throw CancellationException("scope cancelled")
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

    // Regression for a Greptile finding on PR #34: rollback used to negate the target value
    // (`!on`) instead of restoring the value captured before the optimistic write. That's wrong
    // whenever `on` isn't simply the opposite of the item's current value -- e.g. a redundant
    // toggle to the same value it already holds (which is what a double-toggle race collapses
    // to: the second call's "current value" is the first call's not-yet-confirmed optimistic
    // write). Item starts `unattended = true`; requesting `on = true` again and failing must
    // roll back to the captured original (true), not `!on` (false).
    @Test fun toggle_unattended_reverts_to_captured_original_not_negated_target() = runTest {
        val vm = vmWithUnattendedEndpoint(this, unattendedFails = true, initialUnattended = true)
        vm.refresh().join()
        val item = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertTrue(item.unattended)
        vm.setUnattended(item, true).join()
        val after = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertTrue(after.unattended)
    }

    // Regression for a Greptile finding on PR #34: runCatching swallows CancellationException,
    // so when the scope is cancelled mid-request the coroutine used to fall into the failure
    // branch (rolling back) and complete normally instead of propagating the cancellation --
    // breaking structured concurrency. The fix mirrors refresh()'s rethrow. Verify both halves:
    // the returned Job ends up cancelled (not normally completed), and no rollback runs.
    @Test fun toggle_unattended_propagates_cancellation_instead_of_rolling_back() = runTest {
        val vm = vmWithCancellingUnattendedEndpoint(this)
        vm.refresh().join()
        val item = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        val job = vm.setUnattended(item, true)
        job.join()
        assertTrue(job.isCancelled)
        val after = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertTrue(after.unattended)
    }

    private fun vmWithPhaseEndpoint(
        scope: CoroutineScope,
        phaseFails: Boolean,
        initialPhaseOverride: String? = null,
    ) = FarmViewModel(
        SpecApi(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/phase")) {
                if (phaseFails) respond("boom", HttpStatusCode.InternalServerError)
                else respond("""{"id":"a","phase_override":"done"}""", HttpStatusCode.OK, jsonHdr)
            } else {
                val overrideJson = initialPhaseOverride?.let { "\"$it\"" } ?: "null"
                respond(
                    """[{"id":"a","kind":"feature","title":"A","phase":"inbox","phase_override":$overrideJson}]""",
                    HttpStatusCode.OK, jsonHdr,
                )
            }
        }) { mshipDefaults() }),
        conn, testScope = scope,
    )

    /** /phase throws CancellationException directly (standing in for the scope being cancelled
     *  mid-request) rather than failing normally — mirrors [vmWithCancellingUnattendedEndpoint]. */
    private fun vmWithCancellingPhaseEndpoint(scope: CoroutineScope) = FarmViewModel(
        SpecApi(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/phase")) {
                throw CancellationException("scope cancelled")
            } else {
                respond(
                    """[{"id":"a","kind":"feature","title":"A","phase":"inbox","phase_override":null}]""",
                    HttpStatusCode.OK, jsonHdr,
                )
            }
        }) { mshipDefaults() }),
        conn, testScope = scope,
    )

    @Test fun mark_done_optimistically_updates_phase_override() = runTest {
        val vm = vmWithPhaseEndpoint(this, phaseFails = false)
        vm.refresh().join()
        val item = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        vm.setItemPhase(item, "done").join()
        val updated = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertEquals("done", updated.phaseOverride)
    }

    @Test fun mark_done_reverts_on_failure() = runTest {
        val vm = vmWithPhaseEndpoint(this, phaseFails = true)
        vm.refresh().join()
        val item = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        vm.setItemPhase(item, "done").join()
        val after = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertEquals(null, after.phaseOverride)
    }

    // Same regression shape as toggle_unattended_reverts_to_captured_original_not_negated_target:
    // rollback must restore the captured original value, not assume the opposite of the target.
    // Item starts with phaseOverride = "done"; requesting Reopen (phase = null) and failing must
    // roll back to the captured original ("done"), not null.
    @Test fun reopen_reverts_to_captured_original_not_cleared_target() = runTest {
        val vm = vmWithPhaseEndpoint(this, phaseFails = true, initialPhaseOverride = "done")
        vm.refresh().join()
        val item = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertEquals("done", item.phaseOverride)
        vm.setItemPhase(item, null).join()
        val after = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertEquals("done", after.phaseOverride)
    }

    // Mirrors toggle_unattended_propagates_cancellation_instead_of_rolling_back: runCatching
    // swallows CancellationException, so without the explicit rethrow a scope cancellation
    // mid-request would fall into the failure branch (rolling back) instead of propagating.
    @Test fun set_phase_propagates_cancellation_instead_of_rolling_back() = runTest {
        val vm = vmWithCancellingPhaseEndpoint(this)
        vm.refresh().join()
        val item = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        val job = vm.setItemPhase(item, "done")
        job.join()
        assertTrue(job.isCancelled)
        val after = (vm.state.value as FarmUiState.Content).groups.first().items.first()
        assertEquals("done", after.phaseOverride)
    }
}
