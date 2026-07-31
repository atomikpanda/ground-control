package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import com.atomikpanda.groundcontrol.ui.tasks.ActionRef
import com.atomikpanda.groundcontrol.ui.tasks.TaskDetailUiState
import com.atomikpanda.groundcontrol.ui.tasks.TaskDetailViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(scope: CoroutineScope, handler: MockRequestHandler) =
        TaskDetailViewModel(
            TasksRepository(
                SpecApi(
                    HttpClient(MockEngine(handler)) { mshipDefaults() }
                )
            ),
            conn, "t1", testScope = scope,
        )

    private val assumptionsJsonOnePending =
        """{"task":"t1","fresh":true,"pending":1,"flags":[
            {"axis":"scope","source":"plan","reason":"assumed single-repo change","approved":false}
        ]}"""

    private val assumptionsJsonApproved =
        """{"task":"t1","fresh":true,"pending":0,"flags":[
            {"axis":"scope","source":"plan","reason":"assumed single-repo change","approved":true,"approved_by":"operator"}
        ]}"""

    private val assumptionsJsonStale =
        """{"task":"t1","fresh":false,"pending":1,"flags":[
            {"axis":"scope","source":"plan","reason":"assumed single-repo change","approved":false}
        ]}"""

    private val assumptionsJsonTwoPending =
        """{"task":"t1","fresh":true,"pending":2,"flags":[
            {"axis":"scope","source":"plan","reason":"assumed single-repo change","approved":false},
            {"axis":"risk","source":"plan","reason":"assumed low risk","approved":false}
        ]}"""

    private fun MockRequestHandleScope.taskHandler(req: HttpRequestData): HttpResponseData? = when {
        req.url.encodedPath.endsWith("/tasks/t1") ->
            respond(
                """{"slug":"t1","phase":"dev","branch":"feat/t1","description":"do the thing",
                   "affected_repos":["gc"],"pr_urls":{},"test_results":{},"depends_on":[]}""",
                HttpStatusCode.OK, jsonHdr
            )
        req.url.encodedPath.endsWith("/journal/t1") ->
            respond(
                """[{"timestamp":"2026-06-22T10:00:00Z","message":"task started"},
                    {"timestamp":"2026-06-22T11:00:00Z","message":"tests pass","test_state":"green","repo":"gc"}]""",
                HttpStatusCode.OK, jsonHdr
            )
        else -> null
    }

    @Test fun load_success_builds_content_with_task_and_journal() = runTest {
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1") ->
                    respond(assumptionsJsonOnePending, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals("t1", c.task.slug)
        assertEquals("do the thing", c.task.description)
        assertEquals(2, c.journal.size)
        assertEquals("task started", c.journal[0].message)
        assertEquals("green", c.journal[1].testState)
        assertEquals(1, c.assumptions?.pending)
    }

    @Test fun approveFlag_calls_repo_and_updates_assumptions_from_returned_envelope() = runTest {
        var approveCalled = false
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1/approve") -> {
                    approveCalled = true
                    respond(assumptionsJsonApproved, HttpStatusCode.OK, jsonHdr)
                }
                req.url.encodedPath.endsWith("/plan-assumptions/t1") ->
                    respond(assumptionsJsonOnePending, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        assertEquals(1, (vm.state.value as TaskDetailUiState.Content).assumptions?.pending)

        vm.approveFlag("scope")?.join()

        assertTrue(approveCalled)
        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals(0, c.assumptions?.pending)
        assertEquals(true, c.assumptions?.flags?.first()?.approved)
    }

    @Test fun load_404_maps_to_not_found() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.NotFound) }
        vm.load()?.join()
        assertEquals(ErrorKind.NOT_FOUND, (vm.state.value as TaskDetailUiState.Error).kind)
    }

    @Test fun load_401_maps_to_auth() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.Unauthorized) }
        vm.load()?.join()
        assertEquals(ErrorKind.AUTH, (vm.state.value as TaskDetailUiState.Error).kind)
    }

    @Test fun load_network_error_maps_to_network() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.InternalServerError) }
        vm.load()?.join()
        val s = vm.state.value as TaskDetailUiState.Error
        assertEquals(ErrorKind.NETWORK, s.kind)
        assertTrue(s.message.isNotBlank())
    }

    // FINDING 1: a stale/already-handled flag 404s on approve. That must NOT blank the whole
    // screen — task + journal stay put, assumptions get refreshed, and a notice explains why
    // the row vanished.
    @Test fun approveFlag_404_refreshes_assumptions_and_sets_notice_without_going_fatal() = runTest {
        var assumptionsCallCount = 0
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1/approve") ->
                    respondError(HttpStatusCode.NotFound)
                req.url.encodedPath.endsWith("/plan-assumptions/t1") -> {
                    assumptionsCallCount++
                    respond(assumptionsJsonApproved, HttpStatusCode.OK, jsonHdr)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        assertEquals(1, assumptionsCallCount)

        vm.approveFlag("scope")?.join()

        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals("t1", c.task.slug)
        assertEquals(2, c.journal.size)
        assertEquals(2, assumptionsCallCount)
        assertEquals(0, c.assumptions?.pending)
        assertEquals("That assumption was already handled — refreshed.", c.assumptionsNotice)
    }

    // FINDING 2: a stale assumptions set (fresh == false) must surface distinctly and must not
    // offer Approve (the gate will reject approvals until a re-check).
    @Test fun load_stale_assumptions_marks_content_not_approvable() = runTest {
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1") ->
                    respond(assumptionsJsonStale, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals(false, c.assumptions?.fresh)
        assertTrue(c.isAssumptionsStale)
        assertEquals(false, c.canApproveAssumptions)
    }

    // FINDING 3: task+journal are the core content and load in parallel with assumptions;
    // an assumptions-fetch failure degrades to assumptions == null (with a note) instead of
    // blanking the whole screen.
    @Test fun load_assumptions_failure_still_yields_content_with_null_assumptions() = runTest {
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1") ->
                    respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals("t1", c.task.slug)
        assertEquals(2, c.journal.size)
        assertEquals(null, c.assumptions)
        assertTrue(c.assumptionsNotice?.isNotBlank() == true)
    }

    // GREPTILE FINDING 1 (TaskDetailViewModel.kt:118): a transient failure (timeout, 5xx,
    // decode error) during approve is NOT the same as a stale/already-handled flag. It must
    // not be presented as "refreshed" (that implies the approval was accounted for some way),
    // the pending flag must stay visible (not silently dropped), and the notice must say the
    // approval failed and is retryable.
    @Test fun approveFlag_transient_failure_keeps_pending_flag_and_sets_retry_notice() = runTest {
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1/approve") ->
                    respondError(HttpStatusCode.InternalServerError)
                req.url.encodedPath.endsWith("/plan-assumptions/t1") ->
                    respond(assumptionsJsonOnePending, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        assertEquals(1, (vm.state.value as TaskDetailUiState.Content).assumptions?.pending)

        vm.approveFlag("scope")?.join()

        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals("t1", c.task.slug)
        // Pending flag is still shown — never silently dropped by a failed approve.
        assertEquals(1, c.assumptions?.pending)
        assertEquals(false, c.assumptions?.flags?.first()?.approved)
        // inFlight cleared so the Approve button is tappable again.
        assertEquals(null, c.inFlight)
        assertEquals("Approval failed — tap Approve to retry.", c.assumptionsNotice)
    }

    // GREPTILE FINDING 2 (TaskDetailViewModel.kt:73): a pull-to-refresh while an approval is
    // in flight must not discard the refresh outright (that leaves task/journal/assumptions
    // stale). Instead load() defers: it awaits the in-flight approve job first (so the
    // approval's own state update lands, never clobbered), then runs its normal fetch — so
    // the operator's refresh is still honored once the approve settles. The gate on the
    // approve response proves the ordering deterministically: while approve is still
    // pending, the deferred load must not have re-queried assumptions yet; once approve
    // completes, the deferred load re-fetches and picks up the already-approved state.
    @Test fun load_while_approve_in_flight_defers_then_refreshes_after_approve_lands() = runTest {
        var assumptionsCallCount = 0
        var approved = false
        val approveGate = CompletableDeferred<Unit>()
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1/approve") -> {
                    approveGate.await()
                    approved = true
                    respond(assumptionsJsonApproved, HttpStatusCode.OK, jsonHdr)
                }
                req.url.encodedPath.endsWith("/plan-assumptions/t1") -> {
                    assumptionsCallCount++
                    respond(
                        if (approved) assumptionsJsonApproved else assumptionsJsonOnePending,
                        HttpStatusCode.OK, jsonHdr,
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        assertEquals(1, assumptionsCallCount)

        val approveJob = vm.approveFlag("scope")
        val loadJob = vm.load()

        // Approve is still pending (blocked on the gate): the deferred load must not have
        // re-queried assumptions yet, and the in-flight marker is still showing.
        assertEquals(1, assumptionsCallCount)
        val mid = vm.state.value as TaskDetailUiState.Content
        assertTrue(mid.inFlight is ActionRef.ApproveFlag)

        approveGate.complete(Unit)
        approveJob?.join()
        loadJob?.join()

        // The deferred load ran after the approve completed: assumptions were re-fetched
        // (call count advanced past the approve's own state update), and the approval
        // result was not clobbered — the flag is still approved, not reverted to pending.
        assertEquals(2, assumptionsCallCount)
        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals(0, c.assumptions?.pending)
        assertEquals(true, c.assumptions?.flags?.first()?.approved)
        assertEquals(null, c.inFlight)
    }

    // A load() with no approve in flight must not wait on anything — the common case stays
    // as fast as before, and completes synchronously with respect to the mock engine.
    @Test fun load_with_no_approve_in_flight_does_not_wait() = runTest {
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1") ->
                    respond(assumptionsJsonOnePending, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        val first = vm.state.value as TaskDetailUiState.Content
        assertEquals(1, first.assumptions?.pending)

        vm.load()?.join()
        val second = vm.state.value as TaskDetailUiState.Content
        assertEquals(1, second.assumptions?.pending)
    }

    // GREPTILE RE-REVIEW FINDING 1 (TaskDetailViewModel.kt:80): approveJob is a single var
    // overwritten by each approve call. Tapping Approve on a second assumption while the first
    // is still in flight must NOT start a second job (which would make the single approveJob
    // reference — the one load()'s defer-join awaits — point only at the second call, losing
    // track of the first). Only one approve may be in flight at a time.
    @Test fun approveFlag_ignores_overlapping_call_while_one_is_in_flight() = runTest {
        var approveCallCount = 0
        val approveGate = CompletableDeferred<Unit>()
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1/approve") -> {
                    approveCallCount++
                    approveGate.await()
                    respond(assumptionsJsonApproved, HttpStatusCode.OK, jsonHdr)
                }
                req.url.encodedPath.endsWith("/plan-assumptions/t1") ->
                    respond(assumptionsJsonTwoPending, HttpStatusCode.OK, jsonHdr)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        assertEquals(2, (vm.state.value as TaskDetailUiState.Content).assumptions?.pending)

        val jobA = vm.approveFlag("scope")
        val jobB = vm.approveFlag("risk")

        // The overlapping tap is ignored: no second job, and the in-flight marker (set
        // synchronously by approveFlag, before its coroutine even runs) still points at the
        // first, still-running approve.
        assertEquals(null, jobB)
        val mid = vm.state.value as TaskDetailUiState.Content
        assertEquals(ActionRef.ApproveFlag("scope"), mid.inFlight)

        approveGate.complete(Unit)
        jobA?.join()

        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals(1, approveCallCount)
        assertEquals(null, c.inFlight)
    }

    // GREPTILE RE-REVIEW FINDING 2 (TaskDetailViewModel.kt:150): the 404/409 reconcile path
    // stamps a "...refreshed" notice even when the refetch itself fails, claiming success for a
    // failed reconciliation while the stale snapshot remains. The notice must reflect the
    // refetch's real outcome, and the prior assumptions/pending state must stay intact.
    @Test fun approveFlag_404_then_refetch_failure_reports_refresh_failed_not_succeeded() = runTest {
        var assumptionsCallCount = 0
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1/approve") ->
                    respondError(HttpStatusCode.NotFound)
                req.url.encodedPath.endsWith("/plan-assumptions/t1") -> {
                    assumptionsCallCount++
                    if (assumptionsCallCount == 1) respond(assumptionsJsonOnePending, HttpStatusCode.OK, jsonHdr)
                    else respondError(HttpStatusCode.InternalServerError)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        assertEquals(1, assumptionsCallCount)

        vm.approveFlag("scope")?.join()

        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals(2, assumptionsCallCount)
        // Notice reflects the failed refresh — never claims "refreshed" when it didn't happen.
        assertEquals("That assumption changed, but refreshing failed — pull to refresh.", c.assumptionsNotice)
        // Prior (pre-refetch) assumptions/pending state stays visibly intact.
        assertEquals(1, c.assumptions?.pending)
        assertEquals(false, c.assumptions?.flags?.first()?.approved)
    }

    // P1B: the defer decision in load() must be dispatcher-independent. The old implementation
    // captured `pendingApprove = approveJob` synchronously at load()-call time, *outside* its own
    // launched coroutine. If load() is issued before approveFlag() (its coroutine body merely
    // queued, not run inline, on StandardTestDispatcher) and approveFlag() then runs its
    // synchronous prefix (sets `inFlight`, assigns `approveJob`) before either queued coroutine
    // gets a turn, the old code's captured `pendingApprove` is already fixed at a stale `null` —
    // so it skips the join entirely and flips straight to Loading, blanking task/journal/
    // assumptions out from under an approve that's still in flight (and dropping that approve's
    // own state write if it lands during the blank window). Gating on `inFlight`, read *inside*
    // load's own coroutine body (so it reflects state as of when that body actually runs, not
    // when load() was called), keeps load() correctly deferred no matter which order the two
    // calls arrive in.
    //
    // Distinguishing old vs. new requires observing state *while the approve is still pending*
    // (both eventually converge to the same final value once the approve's own network call has
    // landed, because load()'s independent re-fetch re-syncs from the server) — so this test
    // gates the approve's HTTP response on a CompletableDeferred and inspects state after
    // runCurrent() runs both queued coroutines up to their next real suspension, mirroring the
    // gating technique the other in-flight tests in this file already use.
    @Test fun load_issued_just_before_approve_does_not_blank_screen_while_approve_in_flight() = runTest {
        val approveGate = CompletableDeferred<Unit>()
        var approved = false
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1/approve") -> {
                    approveGate.await()
                    approved = true
                    respond(assumptionsJsonApproved, HttpStatusCode.OK, jsonHdr)
                }
                req.url.encodedPath.endsWith("/plan-assumptions/t1") ->
                    respond(
                        if (approved) assumptionsJsonApproved else assumptionsJsonOnePending,
                        HttpStatusCode.OK, jsonHdr,
                    )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()

        // load() is issued first — its coroutine body is only queued on StandardTestDispatcher,
        // not run inline — then approveFlag() runs its synchronous prefix (sets inFlight, assigns
        // approveJob) before either queued coroutine gets a chance to execute.
        val loadJob = vm.load()
        val approveJob = vm.approveFlag("scope")

        // Run both queued coroutines up to their next real suspension (load's defer join / the
        // gated approve call) without resolving the gate yet.
        runCurrent()

        // While the approve is still pending, the screen must still show Content with the
        // in-flight marker — never Loading. A load() that skipped the join here would have
        // blanked the whole screen (and orphaned the approve's own pending state update).
        assertTrue(
            "load() blanked the screen to Loading while an approve was still in flight",
            vm.state.value is TaskDetailUiState.Content,
        )
        val mid = vm.state.value as TaskDetailUiState.Content
        assertTrue(mid.inFlight is ActionRef.ApproveFlag)

        approveGate.complete(Unit)
        approveJob?.join()
        loadJob?.join()

        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals(true, c.assumptions?.flags?.first()?.approved)
        assertEquals(0, c.assumptions?.pending)
        assertEquals(null, c.inFlight)
    }

    // P1: reconciliation must not release the approve serialization lock early. The gone-stale
    // (404/409) branch used to clear `inFlight` BEFORE calling the suspending
    // refetchAssumptionsWithNotice() — so while that refetch was still in flight, the guard in
    // approveFlag() would let a second concurrent approve start, clobbering the first's outcome.
    // `inFlight` must stay held for the whole reconcile and clear exactly once, when the refetch
    // itself reaches its terminal state.
    @Test fun approveFlag_holds_serialization_lock_across_gone_stale_refetch() = runTest {
        var approveCallCount = 0
        var assumptionsCallCount = 0
        val refetchGate = CompletableDeferred<Unit>()
        val vm = vm(this) { req ->
            taskHandler(req) ?: when {
                req.url.encodedPath.endsWith("/plan-assumptions/t1/approve") -> {
                    approveCallCount++
                    respondError(HttpStatusCode.NotFound)
                }
                req.url.encodedPath.endsWith("/plan-assumptions/t1") -> {
                    assumptionsCallCount++
                    if (assumptionsCallCount == 1) {
                        respond(assumptionsJsonOnePending, HttpStatusCode.OK, jsonHdr)
                    } else {
                        // The reconcile refetch (2nd+ GET): suspend until the test releases it,
                        // so we can probe the lock state mid-refetch.
                        refetchGate.await()
                        respond(assumptionsJsonApproved, HttpStatusCode.OK, jsonHdr)
                    }
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        assertEquals(1, assumptionsCallCount)

        val jobA = vm.approveFlag("scope")

        // Drive jobA forward in real time until it's blocked inside the gone-stale refetch
        // (gated by refetchGate) — a real suspension, not virtual/delay-based time, so we poll
        // rather than advanceUntilIdle (which would spin forever on an unresolved real gate).
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (assumptionsCallCount < 2) yield()
            }
        }

        // While the refetch is still suspended, the lock must still be held: a second approve
        // is a no-op.
        val mid = vm.state.value as TaskDetailUiState.Content
        assertEquals(ActionRef.ApproveFlag("scope"), mid.inFlight)
        val jobB = vm.approveFlag("risk")
        assertEquals(null, jobB)
        assertEquals(1, approveCallCount)

        refetchGate.complete(Unit)
        jobA?.join()

        val c = vm.state.value as TaskDetailUiState.Content
        assertEquals(null, c.inFlight)
        assertEquals("That assumption was already handled — refreshed.", c.assumptionsNotice)
    }
}
