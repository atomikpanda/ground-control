// app/src/test/java/com/atomikpanda/groundcontrol/QueueViewModelTest.kt
//
// Queue v2 card-stack coverage: the head-stable machinery (load / position /
// skip-to-back) plus the PR3 transitions — approve-all + auto-approve, the
// auto-approve 409 (remaining chunks stay), reject (flag + request-changes clears
// the spec), and per-item verdicts applied in place.
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.DIRECTORY_STALE_MS
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.queue.CriteriaCard
import com.atomikpanda.groundcontrol.ui.queue.PlanAssumptionCard
import com.atomikpanda.groundcontrol.ui.queue.ProseCard
import com.atomikpanda.groundcontrol.ui.queue.QueueUiState
import com.atomikpanda.groundcontrol.ui.queue.QueueViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(
        scope: CoroutineScope,
        conns: List<WorkspaceConnection>,
        handler: MockRequestHandler,
        hosts: List<HostConnection> = emptyList(),
    ): QueueViewModel {
        val repo = QueueRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }))
        return QueueViewModel(repo, connectionState(conns), scope, flowOf(hosts))
    }

    private val connsAB = listOf(
        WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
        WorkspaceConnection("b", "http://b:47100", null, "ws-b"),
    )
    private val connsA = listOf(WorkspaceConnection("a", "http://a:47100", null, "ws-a"))
    private val onlyEmpty = listOf(WorkspaceConnection("b", "http://b:47100", null, "ws-b"))

    // ws-a: one needs_review spec (s1) with two prose sections → two prose cards, no threads.
    private fun proseRepoHandler(allHosts: Boolean = false): MockRequestHandler = { req ->
        val path = req.url.encodedPath
        val body = when {
            path.endsWith("/specs") -> if (allHosts || req.url.host == "a")
                """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]"
            path.endsWith("/threads") -> "[]"
            path.endsWith("/plan-assumptions") -> "[]"
            path.contains("/specs/") ->
                """{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","updated_at":"2026-01-01T00:00:00Z"}"""
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, jsonHdr)
    }

    @Test fun no_connections_yields_empty_config() = runTest {
        val vm = QueueViewModel(QueueRepository(SpecApi(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, jsonHdr) }) { mshipDefaults() })), connectionState(emptyList()), backgroundScope)
        vm.refresh()
        assertEquals(QueueUiState.EmptyConfig, vm.state.value)
    }


    @Test fun shared_host_failures_render_once() = runTest {
        val connections = listOf(
            WorkspaceConnection(
                id = "a",
                baseUrl = "https://host.example/workspaces/a",
                workspaceName = "a",
                hostId = "host-1",
                workspaceId = "a",
            ),
            WorkspaceConnection(
                id = "b",
                baseUrl = "https://host.example/workspaces/b",
                workspaceName = "b",
                hostId = "host-1",
                workspaceId = "b",
            ),
        )
        val host = HostConnection(hostId = "host-1", publicUrl = "https://host.example")
        val vm = vm(
            backgroundScope,
            connections,
            { throw java.io.IOException("offline") },
            hosts = listOf(host),
        )

        vm.refresh()?.join()

        assertEquals(1, (vm.state.value as QueueUiState.Content).errors.size)
    }

    @Test fun refresh_classifies_errors_with_post_request_host_freshness() = runTest {
        val connection = WorkspaceConnection(
            id = "a",
            baseUrl = "http://a:47100",
            workspaceName = "ws-a",
            hostId = "host-a",
            workspaceId = "ws-a",
        )
        val now = DIRECTORY_STALE_MS
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
        val repo = QueueRepository(
            SpecApi(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/threads")) {
                            respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
                        } else {
                            hosts.value = hosts.value.map {
                                it.copy(lastContactAtMillis = now)
                            }
                            respond("[]", HttpStatusCode.OK, jsonHdr)
                        }
                    },
                ) { mshipDefaults() },
            ),
        )
        val vm = QueueViewModel(
            repo = repo,
            connectionState = connectionState(listOf(connection)),
            testScope = backgroundScope,
            hosts = hosts,
            nowMillis = { now },
        )

        vm.refresh()?.join()

        assertEquals(
            HostLadderState.WORKSPACE_DEGRADED,
            (vm.state.value as QueueUiState.Content).errors.single().ladderState,
        )
    }

    @Test fun queue_error_labels_recompute_at_the_stale_deadline() = runTest {
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
        val repo = QueueRepository(
            SpecApi(
                HttpClient(
                    MockEngine { throw java.io.IOException("offline") },
                ) { mshipDefaults() },
            ),
        )
        val vm = QueueViewModel(
            repo = repo,
            connectionState = connectionState(listOf(connection)),
            testScope = scope,
            hosts = hosts,
            nowMillis = { now },
        )
        vm.refresh()?.join()
        assertEquals(
            HostLadderState.WORKSPACE_DEGRADED,
            (vm.state.value as QueueUiState.Content).errors.single().ladderState,
        )

        now = DIRECTORY_STALE_MS
        advanceTimeBy(DIRECTORY_STALE_MS)
        runCurrent()

        assertEquals(
            HostLadderState.STALE,
            (vm.state.value as QueueUiState.Content).errors.single().ladderState,
        )
        scope.cancel()
    }

    @Test fun loads_prose_cards_head_first_with_position() = runTest {
        val vm = vm(backgroundScope, connsAB, proseRepoHandler())
        vm.refresh()?.join()
        val c = vm.state.value as QueueUiState.Content
        val head = c.current as ProseCard
        assertEquals("s1", head.specId)
        assertEquals("problem", head.sectionId)
        assertEquals(1, c.position)
        assertEquals(2, c.total)
        assertTrue(!c.caughtUp)
    }

    @Test fun empty_queue_is_caught_up() = runTest {
        val vm = vm(backgroundScope, onlyEmpty, proseRepoHandler())
        vm.refresh()?.join()
        val c = vm.state.value as QueueUiState.Content
        assertTrue(c.caughtUp)
        assertNull(c.current)
    }

    @Test fun skip_sends_head_to_the_back() = runTest {
        val vm = vm(backgroundScope, connsAB, proseRepoHandler())
        vm.refresh()?.join()
        vm.skip()
        val c = vm.state.value as QueueUiState.Content
        assertEquals("approach", (c.current as ProseCard).sectionId)                 // advanced to the next section
        assertTrue(c.cards.filterIsInstance<ProseCard>().any { it.sectionId == "problem" })  // deferred to back
    }

    // ws-a spec s1 with only acceptance criteria (no prose/questions) → a single CriteriaCard.
    // That card IS the spec's last chunk, so verdict POSTs + /approve run and the whole spec
    // auto-approves. On approve success undo is NOT armed (can't un-approve server-side).
    @Test fun approve_all_marks_items_and_auto_approves_when_last_chunk() = runTest {
        var approveCalled = false
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") -> { approveCalled = true; respond("""{"id":"s1","status":"approved"}""", HttpStatusCode.OK, jsonHdr) }
                path.endsWith("/verdict") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"},{"id":"ac2","text":"b","verdict":"approved"}],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertTrue(vm.stateContent().current is CriteriaCard)
        vm.approveAllCurrent()?.join()
        val c = vm.stateContent()
        assertTrue(approveCalled)         // last chunk → auto-approve fired
        assertTrue(c.caughtUp)            // the fully-approved spec's card left the queue
        assertEquals(1, c.resolved)
        assertNull(c.actionError)
        assertNull(c.undo)                // approve success does NOT arm undo (can't be reversed server-side)
    }

    // AC4: swiping to finalize the LAST remaining chunk of a spec surfaces an explicit whole-spec
    // confirmation naming the spec (the operator just shipped it) — distinct from the single-chunk
    // 'Approved / Undo'. Server-side undo isn't possible, so it confirms by name instead. Single
    // criteria card = the spec's last chunk, so /approve fires and the notice carries the spec title.
    @Test fun approving_last_chunk_surfaces_whole_spec_confirmation_with_title() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") -> respond("""{"id":"s1","status":"approved"}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/verdict") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"Ship it","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"Ship it","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        vm.approveAllCurrent()?.join()
        val c = vm.stateContent()
        assertTrue(c.caughtUp)                       // whole spec approved → its card left the queue
        assertNotNull(c.specApproved)                // explicit whole-spec confirmation
        assertEquals("Ship it", c.specApproved!!.title)
        assertNull(c.undo)                           // a whole-spec approve is NOT undoable
    }

    // AC4 (the other branch): approving a NON-final chunk keeps the existing 'Approved / Undo' and
    // does NOT surface a whole-spec confirmation (the spec isn't shipped yet). Two prose sections →
    // approving the head advances past it without approving the spec.
    @Test fun approving_non_final_chunk_keeps_undo_and_no_spec_confirmation() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/prose-verdict") -> respond("""{"id":"s1","status":"needs_review"}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","acceptance_criteria":[],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertEquals(2, vm.stateContent().cards.size)
        vm.approveAllCurrent()?.join()
        val c = vm.stateContent()
        assertNull(c.specApproved)                   // spec not finalized → no whole-spec confirmation
        assertNotNull(c.undo)                        // non-final chunk keeps 'Approved / Undo'
    }

    // FIX 2: approving one chunk of a MULTI-chunk spec must NOT auto-approve while siblings remain
    // (else a skipped/unreviewed sibling is silently swept away). ws-a spec s1 has two prose sections →
    // two ProseCards. Approving the head advances past it WITHOUT calling /approve (arming undo); only
    // when the last chunk is approved does the spec auto-approve.
    @Test fun approve_all_does_not_auto_approve_while_other_chunks_remain() = runTest {
        var approveCalled = false
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") -> { approveCalled = true; respond("""{"id":"s1","status":"approved"}""", HttpStatusCode.OK, jsonHdr) }
                path.endsWith("/prose-verdict") -> respond("""{"id":"s1","status":"needs_review"}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","acceptance_criteria":[],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertEquals(2, vm.stateContent().cards.size)       // two prose sections
        vm.approveAllCurrent()?.join()
        var c = vm.stateContent()
        assertFalse(approveCalled)                          // FIX 2: not the last chunk → no auto-approve
        assertEquals(1, c.resolved)                         // head advanced past 'problem'
        assertEquals("approach", (c.current as ProseCard).sectionId)  // the sibling chunk stays
        assertTrue(c.undo != null)                          // advance-without-approve arms undo
        // approve the last remaining chunk → now the whole spec auto-approves
        vm.approveAllCurrent()?.join()
        c = vm.stateContent()
        assertTrue(approveCalled)                           // last chunk → auto-approve fired
        assertTrue(c.caughtUp)
        assertEquals(2, c.resolved)
        assertNull(c.undo)                                  // approve success does NOT arm undo
    }

    // FIX 6 / FINDING 1 (already-approved): a 409 on /approve where a fresh load no longer lists the
    // spec (a concurrent device approved it) → reconcile drops the ghost: head resolves + advances, no
    // error. Single-chunk criteria spec; /specs lists s1 on first load, [] on the reconcile reload.
    @Test fun approve_all_conflict_when_already_approved_drops_ghost() = runTest {
        var specsCalls = 0
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") ->
                    respond("""{"detail":"invalid transition: spec already approved"}""", HttpStatusCode.Conflict, jsonHdr)
                path.endsWith("/verdict") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") -> {
                    specsCalls++
                    respond(if (specsCalls == 1) """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                }
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertTrue(vm.stateContent().current is CriteriaCard)
        vm.approveAllCurrent()?.join()
        val c = vm.stateContent()
        assertTrue(c.caughtUp)             // reconcile: spec gone from the feed → ghost dropped
        assertEquals(1, c.resolved)
        assertNull(c.actionError)          // NOT surfaced as a generic error
    }

    // FINDING 1 (genuinely blocked): a 409 on /approve where a fresh load STILL lists the spec as
    // needs_review (blocked by a server-side blocker we have no card for) must NOT silently resolve the
    // card — the spec would vanish unapproved. Instead the card stays put and an actionError surfaces.
    @Test fun approve_all_conflict_when_blocked_keeps_card_and_surfaces() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") ->
                    respond("""{"detail":"cannot approve: 1 blocker"}""", HttpStatusCode.Conflict, jsonHdr)
                path.endsWith("/verdict") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertTrue(vm.stateContent().current is CriteriaCard)
        vm.approveAllCurrent()?.join()
        val c = vm.stateContent()
        assertFalse(c.caughtUp)                    // card NOT silently dropped
        assertTrue(c.current is CriteriaCard)      // still the head
        assertEquals(0, c.resolved)
        assertTrue(c.actionError != null)          // blocked → surfaced
        assertFalse(c.inFlight)
    }

    // FINDING 3: approving EVERY criterion via the per-item Check (not the swipe) must also complete the
    // card — when all are approved and it's the last chunk, the spec auto-approves and the card leaves,
    // instead of a fully-approved card lingering forever. Two criteria; /verdict returns ac2 unreviewed
    // after the first write, both approved after the second.
    @Test fun per_item_approving_all_completes_and_auto_approves() = runTest {
        var approveCalled = false
        var verdictCalls = 0
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") -> { approveCalled = true; respond("""{"id":"s1","status":"approved"}""", HttpStatusCode.OK, jsonHdr) }
                path.endsWith("/verdict") -> {
                    verdictCalls++
                    val acs = if (verdictCalls >= 2)
                        """[{"id":"ac1","text":"a","verdict":"approved"},{"id":"ac2","text":"b","verdict":"approved"}]"""
                    else
                        """[{"id":"ac1","text":"a","verdict":"approved"},{"id":"ac2","text":"b","verdict":"unreviewed"}]"""
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":$acs,"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                }
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        vm.setItemVerdict("a", "s1", "ac1", "approved")?.join()
        assertFalse(vm.stateContent().caughtUp)     // one still unreviewed → card stays
        assertFalse(approveCalled)
        vm.setItemVerdict("a", "s1", "ac2", "approved")?.join()
        val c = vm.stateContent()
        assertTrue(approveCalled)                    // all approved + last chunk → auto-approve fired
        assertTrue(c.caughtUp)                       // the completed card left the queue
        assertEquals(1, c.resolved)
        assertNull(c.actionError)
    }

    // Regression (Greptile #51 P1): toggling a criterion rebuilds the CriteriaCard from the write
    // response; evidence must survive that in-place rebuild, or a backed criterion flashes as
    // unverified until the next full reload. s1 has ac1 (with evidence) + ac2; toggling ac1 keeps the
    // card (ac2 still unreviewed), and ac1 must still carry its evidence afterwards.
    @Test fun toggling_a_criterion_preserves_evidence_on_the_card() = runTest {
        val ev = """"evidence":[{"kind":"test","ref":"pytest -q","note":"18 passed"}]"""
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/verdict") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved",$ev},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed",$ev},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        vm.setItemVerdict("a", "s1", "ac1", "approved")?.join()
        val card = vm.stateContent().current as CriteriaCard   // still the head (ac2 unreviewed)
        val ac1 = card.items.first { it.id == "ac1" }
        assertEquals(1, ac1.evidence.size)
        assertEquals("pytest -q", ac1.evidence.first().ref)
    }

    // FINDING 2: a fully-answered QuestionsCard must leave the queue (not linger showing answered items),
    // so it neither strands the operator nor blocks the spec's auto-approve. Spec s1 has one criterion +
    // one open question → 2 cards. Answering the question completes its card; approving the criterion is
    // then the last chunk → the spec auto-approves.
    @Test fun answering_last_question_clears_questions_card() = runTest {
        var approveCalled = false
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") -> { approveCalled = true; respond("""{"id":"s1","status":"approved"}""", HttpStatusCode.OK, jsonHdr) }
                path.endsWith("/answer") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[{"id":"q1","text":"q?","answer":"yes"}]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/verdict") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[{"id":"q1","text":"q?","answer":"yes"}]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[{"id":"q1","text":"q?","answer":null}],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertEquals(2, vm.stateContent().cards.size)          // criteria + questions
        vm.answerQuestion("a", "s1", "q1", "yes")?.join()
        var c = vm.stateContent()
        assertEquals(1, c.cards.size)                          // the answered questions card left the queue
        assertTrue(c.current is CriteriaCard)                  // only the criterion remains
        assertFalse(approveCalled)                             // not the last chunk yet
        vm.approveAllCurrent()?.join()
        c = vm.stateContent()
        assertTrue(approveCalled)                              // criterion was the last chunk → spec auto-approves
        assertTrue(c.caughtUp)
    }

    // FIX 1: spec ids are workspace-local slugs. Two workspaces each expose a needs_review spec "s1"
    // (single-chunk). Approving ws-a's must resolve/remove ONLY ws-a's card, leaving ws-b's same-id
    // card untouched in the queue.
    @Test fun spec_ops_are_scoped_to_workspace() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/approve") -> """{"id":"s1","status":"approved"}"""
                path.endsWith("/verdict") ->
                    """{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}"""
                path.endsWith("/specs") -> """[{"id":"s1","title":"S1","status":"needs_review"}]"""  // BOTH hosts
                path.endsWith("/threads") -> "[]"
            path.endsWith("/plan-assumptions") -> "[]"
                path.contains("/specs/") ->
                    """{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = vm(backgroundScope, connsAB, handler)
        vm.refresh()?.join()
        assertEquals(2, vm.stateContent().cards.size)                       // ws-a/s1 + ws-b/s1
        assertEquals("a", (vm.stateContent().current as CriteriaCard).connectionId)
        vm.approveAllCurrent()?.join()
        val c = vm.stateContent()
        assertEquals(1, c.cards.size)                                       // only ws-a's card left the queue
        assertEquals("b", (c.current as CriteriaCard).connectionId)         // ws-b's same-id spec untouched
        assertEquals("s1", (c.current as CriteriaCard).specId)
        assertEquals(1, c.resolved)
    }

    // ws-a spec s1 with two prose sections + one criterion → 3 cards. Rejecting the head flags it
    // and requests changes, and every one of the spec's cards leaves the queue.
    @Test fun reject_flags_requests_changes_and_clears_spec_cards() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/request-changes") -> """{"id":"s1","status":"needs_clarification"}"""
                path.endsWith("/prose-verdict") -> """{"id":"s1","status":"needs_review"}"""
                path.endsWith("/specs") -> if (req.url.host == "a")
                    """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]"
                path.endsWith("/threads") -> "[]"
            path.endsWith("/plan-assumptions") -> "[]"
                path.contains("/specs/") ->
                    """{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertEquals(3, vm.stateContent().cards.size)
        vm.rejectCurrent("needs work")?.join()
        val c = vm.stateContent()
        assertTrue(c.caughtUp)                 // all of s1's cards left the queue
        assertEquals(3, c.resolved)
        assertNull(c.actionError)
    }

    // ws-a spec s1 with two criteria → one CriteriaCard. A per-item verdict updates that card in
    // place (no advance): ac1 becomes approved, ac2 stays unreviewed, head is still the CriteriaCard.
    @Test fun per_item_verdict_updates_card_in_place() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/verdict") ->
                    """{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[]}"""
                path.endsWith("/specs") -> if (req.url.host == "a")
                    """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]"
                path.endsWith("/threads") -> "[]"
            path.endsWith("/plan-assumptions") -> "[]"
                path.contains("/specs/") ->
                    """{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        vm.setItemVerdict("a", "s1", "ac1", "approved")?.join()
        val c = vm.stateContent()
        assertEquals(1, c.cards.size)                       // no advance
        val head = c.current as CriteriaCard
        assertEquals("approved", head.items.first { it.id == "ac1" }.verdict)
        assertEquals("unreviewed", head.items.first { it.id == "ac2" }.verdict)
        assertEquals(0, c.resolved)
    }

    // Whole-spec confirm parity: approving the LAST criterion in place (the per-item finalize path, not a
    // swipe) approves the whole spec and drops its cards — it must surface the SAME by-name whole-spec
    // confirmation the swipe path does, not vanish silently. Single-criterion spec → approving ac1 finalizes.
    @Test fun finalizing_last_criterion_in_place_surfaces_whole_spec_confirmation_with_title() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") -> respond("""{"id":"s1","status":"approved"}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/verdict") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"Ship it","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"Ship it","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        vm.setItemVerdict("a", "s1", "ac1", "approved")?.join()
        val c = vm.stateContent()
        assertTrue(c.caughtUp)                       // whole spec approved → its card left the queue
        assertNotNull(c.specApproved)                // parity: per-item finalize also confirms by name
        assertEquals("Ship it", c.specApproved!!.title)
        assertNull(c.undo)                           // a whole-spec approve is not undoable
    }

    // Greptile P1 (PR #65): mergeKeepingHead freezes the head card instance across a live refresh so
    // an in-progress interaction (checking a criterion, answering a question) isn't yanked from under
    // the operator. A PlanAssumptionCard has no such in-place interaction (it's a tap-out deep-link,
    // QueueHints.OPEN_TASK) — freezing it only hides that its `pending` count changed, or that it
    // resolved to zero (the repo filters pending==0 out of the feed entirely, see QueueRepository).
    // A single-workspace queue with only a plan-assumption card: refreshing across pending 2 -> 1 -> 0
    // (gone) must be reflected live, not pinned to the first-loaded instance.
    @Test fun plan_assumption_head_reflects_pending_changes_and_disappears_at_zero() = runTest {
        var planCalls = 0
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/specs") -> "[]"
                path.endsWith("/threads") -> "[]"
                path.endsWith("/plan-assumptions") -> {
                    planCalls++
                    when (planCalls) {
                        1 -> """[{"task":"t1","fresh":true,"pending":2}]"""
                        2 -> """[{"task":"t1","fresh":true,"pending":1}]"""
                        else -> "[]"
                    }
                }
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertEquals(2, (vm.stateContent().current as PlanAssumptionCard).pending)

        vm.refresh()?.join()  // live refresh: pending drops 2 -> 1
        assertEquals(1, (vm.stateContent().current as PlanAssumptionCard).pending)

        vm.refresh()?.join()  // live refresh: fully resolved -> the card leaves the queue
        assertTrue(vm.stateContent().caughtUp)
        assertNull(vm.stateContent().current)
    }

    // Greptile P1 (PR #65) follow-up: the staleness fix above excluded PlanAssumptionCard from
    // stableHead entirely, so a displayed plan-assumption head got swept into the normal
    // urgency-sorted merge on every refresh — a higher-priority DecisionCard arriving mid-refresh
    // would replace it at the head (a yank) while the operator was viewing/about-to-tap it. The
    // fix must keep the plan-assumption head PINNED (refreshed with fresh data, not verbatim) so a
    // newly-arrived higher-priority card is merged BEHIND it, not swapped in.
    @Test fun plan_assumption_head_is_not_yanked_by_a_higher_priority_card_on_refresh() = runTest {
        var threadCalls = 0
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/specs") -> "[]"
                path.endsWith("/threads") -> {
                    threadCalls++
                    if (threadCalls == 1) "[]" else """[{"id":"t1","needs_decision":true}]"""
                }
                path.contains("/threads/") ->
                    """{"id":"t1","updated_at":"2026-06-03T00:00:00Z","messages":[
                         {"id":"m1","role":"agent","text":"Pick one","kind":"decision","decision":{"options":["X","Y"]}}]}"""
                path.endsWith("/plan-assumptions") -> """[{"task":"t1","fresh":true,"pending":2}]"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = vm(backgroundScope, connsA, handler)
        vm.refresh()?.join()
        assertTrue(vm.stateContent().current is PlanAssumptionCard)

        vm.refresh()?.join()  // live refresh: a higher-priority decision thread now also appears
        val c = vm.stateContent()
        assertTrue("head must stay the plan-assumption card, not be yanked by the new decision", c.current is PlanAssumptionCard)
        assertEquals(2, (c.current as PlanAssumptionCard).pending)
        assertEquals(2, c.cards.size)
    }

    private fun QueueViewModel.stateContent(): QueueUiState.Content = state.value as QueueUiState.Content
    @Test fun connection_state_loading_error_empty_add_and_remove_reuse_one_owner() = runTest {
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
        val vm = QueueViewModel(
            QueueRepository(SpecApi(HttpClient(MockEngine(proseRepoHandler(allHosts = true))) { mshipDefaults() })),
            source,
            backgroundScope,
            flowOf(emptyList()),
        )
        assertTrue(vm.state.value is QueueUiState.Loading)

        source.value = ConnectionState.Error(IllegalStateException("disk"))
        assertTrue(vm.state.first { it is QueueUiState.ConnectionsUnavailable } is QueueUiState.ConnectionsUnavailable)

        source.value = ConnectionState.Ready(emptyList())
        assertEquals(QueueUiState.EmptyConfig, vm.state.first { it is QueueUiState.EmptyConfig })

        source.value = ConnectionState.Ready(connsA)
        assertEquals(
            listOf("a"),
            (vm.state.first { (it as? QueueUiState.Content)?.cards?.map { card -> card.connectionId }?.distinct() == listOf("a") }
                as QueueUiState.Content).cards.map { it.connectionId }.distinct(),
        )

        source.value = ConnectionState.Ready(connsAB)
        assertEquals(
            listOf("a", "b"),
            (vm.state.first { (it as? QueueUiState.Content)?.cards?.map { card -> card.connectionId }?.distinct() == listOf("a", "b") }
                as QueueUiState.Content).cards.map { it.connectionId }.distinct(),
        )

        source.value = ConnectionState.Ready(listOf(connsAB[1]))
        assertEquals(
            listOf("b"),
            (vm.state.first { (it as? QueueUiState.Content)?.cards?.map { card -> card.connectionId }?.distinct() == listOf("b") }
                as QueueUiState.Content).cards.map { it.connectionId }.distinct(),
        )
    }

    @Test fun same_id_route_and_token_replacement_starts_one_new_authoritative_queue_load() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val newRequestStarted = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<String, String?>>()
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val vm = QueueViewModel(
            QueueRepository(SpecApi(HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/specs")) {
                    requests += request.url.host to request.headers[HttpHeaders.Authorization]
                    if (request.url.host == "new") newRequestStarted.complete(Unit)
                }
                respond("[]", HttpStatusCode.OK, jsonHdr)
            }) { mshipDefaults() })),
            source,
            backgroundScope,
            flowOf(emptyList()),
        )

        vm.state.first { it is QueueUiState.Content }
        source.value = ConnectionState.Ready(listOf(replacement))
        newRequestStarted.await()

        assertEquals(
            listOf("old" to "Bearer old-token", "new" to "Bearer new-token"),
            requests,
        )
        vm.state.first { it is QueueUiState.Content }
    }

    @Test fun two_repeated_manual_queue_refreshes_publish_only_newest_after_delayed_old_completion() = runTest {
        val firstManualStarted = CompletableDeferred<Unit>()
        val firstManualCancelled = CompletableDeferred<Unit>()
        var specLists = 0
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(connsA))
        val vm = QueueViewModel(
            QueueRepository(SpecApi(HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/specs") -> {
                        specLists += 1
                        when (specLists) {
                            2 -> {
                                firstManualStarted.complete(Unit)
                                try {
                                    CompletableDeferred<Unit>().await()
                                    respond("""[{"id":"old","title":"Old","status":"needs_review"}]""", HttpStatusCode.OK, jsonHdr)
                                } finally {
                                    firstManualCancelled.complete(Unit)
                                }
                            }
                            3 -> respond("""[{"id":"new","title":"New","status":"needs_review"}]""", HttpStatusCode.OK, jsonHdr)
                            else -> respond("[]", HttpStatusCode.OK, jsonHdr)
                        }
                    }
                    path.contains("/specs/") -> {
                        val specId = path.substringAfterLast("/")
                        respond(
                            """{"id":"$specId","title":"$specId","status":"needs_review","body":"## Problem\n\nP"}""",
                            HttpStatusCode.OK,
                            jsonHdr,
                        )
                    }
                    else -> respond("[]", HttpStatusCode.OK, jsonHdr)
                }
            }) { mshipDefaults() })),
            source,
            backgroundScope,
            flowOf(emptyList()),
        )

        vm.state.first { it is QueueUiState.Content }
        vm.refresh()
        firstManualStarted.await()
        val secondManual = vm.refresh()
        firstManualCancelled.await()
        secondManual?.join()
        assertEquals(listOf("new"), vm.stateContent().cards.map { (it as ProseCard).specId })
    }
    @Test fun same_id_replacement_clears_old_cards_until_the_new_generation_loads() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val newLoadStarted = CompletableDeferred<Unit>()
        val releaseNewLoad = CompletableDeferred<Unit>()
        var mutationRequests = 0
        val vm = QueueViewModel(
            QueueRepository(SpecApi(HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/specs") && request.url.host == "old" ->
                        respond("""[{"id":"old","title":"Old","status":"needs_review"}]""", HttpStatusCode.OK, jsonHdr)
                    path.endsWith("/specs") -> {
                        newLoadStarted.complete(Unit)
                        releaseNewLoad.await()
                        respond("""[{"id":"new","title":"New","status":"needs_review"}]""", HttpStatusCode.OK, jsonHdr)
                    }
                    path.contains("/specs/") -> {
                        val specId = path.substringAfterLast("/")
                        respond(
                            """{"id":"$specId","title":"$specId","status":"needs_review","body":"## Problem\n\nP"}""",
                            HttpStatusCode.OK,
                            jsonHdr,
                        )
                    }
                    path.endsWith("/threads") || path.endsWith("/plan-assumptions") ->
                        respond("[]", HttpStatusCode.OK, jsonHdr)
                    else -> {
                        mutationRequests += 1
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                }
            }) { mshipDefaults() })),
            source,
            backgroundScope,
            flowOf(emptyList()),
        )

        vm.state.first { (it as? QueueUiState.Content)?.cards?.any { card -> (card as? ProseCard)?.specId == "old" } == true }
        source.value = ConnectionState.Ready(listOf(replacement))
        newLoadStarted.await()

        assertEquals(QueueUiState.Loading, vm.state.value)
        assertNull(vm.approveAllCurrent())
        assertEquals(0, mutationRequests)

        releaseNewLoad.complete(Unit)
        val content = vm.state.first {
            (it as? QueueUiState.Content)?.cards?.mapNotNull { card -> (card as? ProseCard)?.specId } == listOf("new")
        } as QueueUiState.Content
        assertEquals(listOf("new"), content.cards.map { (it as ProseCard).specId })
        assertNotNull(vm.approveAllCurrent())
    }
    @Test fun approval_conflict_reconcile_does_not_resolve_replacement_generation() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val reconcileStarted = CompletableDeferred<Unit>()
        val releaseReconcile = CompletableDeferred<Unit>()
        var oldSpecLoads = 0
        val vm = QueueViewModel(
            QueueRepository(SpecApi(HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/approve") ->
                        respond("""{"detail":"already approved"}""", HttpStatusCode.Conflict, jsonHdr)
                    path.endsWith("/verdict") ->
                        respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                    path.endsWith("/specs") && request.url.host == "old" -> {
                        oldSpecLoads++
                        if (oldSpecLoads == 2) {
                            reconcileStarted.complete(Unit)
                            releaseReconcile.await()
                            respond("[]", HttpStatusCode.OK, jsonHdr)
                        } else {
                            respond("""[{"id":"s1","title":"S1","status":"needs_review"}]""", HttpStatusCode.OK, jsonHdr)
                        }
                    }
                    path.endsWith("/specs") ->
                        respond("""[{"id":"s1","title":"S1","status":"needs_review"}]""", HttpStatusCode.OK, jsonHdr)
                    path.endsWith("/threads") || path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                    path.contains("/specs/") ->
                        respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                    else -> respond("{}", HttpStatusCode.OK, jsonHdr)
                }
            }) { mshipDefaults() })),
            source,
            backgroundScope,
            flowOf(emptyList()),
        )

        vm.state.first { (it as? QueueUiState.Content)?.current is CriteriaCard }
        val approval = vm.approveAllCurrent()!!
        reconcileStarted.await()
        releaseReconcile.complete(Unit)
        source.value = ConnectionState.Ready(listOf(replacement))
        approval.join()

        vm.refresh()?.join()
        val content = vm.state.value as QueueUiState.Content
        assertTrue(content.current is CriteriaCard)
        assertFalse(content.caughtUp)
    }

    @Test fun final_approval_does_not_resolve_replacement_generation_after_request() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val approveStarted = CompletableDeferred<Unit>()
        val releaseApprove = CompletableDeferred<Unit>()
        val vm = QueueViewModel(
            QueueRepository(SpecApi(HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/approve") -> {
                        approveStarted.complete(Unit)
                        releaseApprove.await()
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                    path.endsWith("/verdict") ->
                        respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
                    path.endsWith("/specs") ->
                        respond("""[{"id":"s1","title":"S1","status":"needs_review"}]""", HttpStatusCode.OK, jsonHdr)
                    path.endsWith("/threads") || path.endsWith("/plan-assumptions") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                    path.contains("/specs/") ->
                        respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                    else -> respond("{}", HttpStatusCode.OK, jsonHdr)
                }
            }) { mshipDefaults() })),
            source,
            backgroundScope,
            flowOf(emptyList()),
        )

        vm.state.first { (it as? QueueUiState.Content)?.current is CriteriaCard }
        val approval = vm.setItemVerdict(old.id, "s1", "ac1", "approved")!!
        approveStarted.await()
        releaseApprove.complete(Unit)
        source.value = ConnectionState.Ready(listOf(replacement))
        approval.join()

        vm.refresh()?.join()
        val content = vm.state.value as QueueUiState.Content
        assertTrue(content.current is CriteriaCard)
        assertFalse(content.caughtUp)
    }

    @Test fun queued_mutation_rechecks_the_connection_before_its_first_request() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        var mutationRequests = 0
        val vm = QueueViewModel(
            QueueRepository(SpecApi(HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/prose-verdict") || request.url.encodedPath.endsWith("/verdict")) {
                    mutationRequests += 1
                }
                val body = when {
                    request.url.encodedPath.endsWith("/specs") ->
                        """[{"id":"s1","title":"S1","status":"needs_review"}]"""
                    request.url.encodedPath.endsWith("/threads") ||
                        request.url.encodedPath.endsWith("/plan-assumptions") -> "[]"
                    request.url.encodedPath.contains("/specs/") ->
                        """{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","updated_at":"2026-01-01T00:00:00Z"}"""
                    else -> "{}"
                }
                respond(body, HttpStatusCode.OK, jsonHdr)
            }) { mshipDefaults() })),
            source,
            backgroundScope,
            flowOf(emptyList()),
        )
        vm.state.first { it is QueueUiState.Content }

        assertNotNull(vm.approveAllCurrent())
        source.value = ConnectionState.Ready(listOf(replacement))
        runCurrent()

        assertEquals(0, mutationRequests)
    }

    @Test fun removing_the_mutating_workspace_reenables_remaining_queue_cards() = runTest {
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(connsAB))
        val mutationStarted = CompletableDeferred<Unit>()
        val holdMutation = CompletableDeferred<Unit>()
        val vm = QueueViewModel(
            QueueRepository(SpecApi(HttpClient(MockEngine { request ->
                if (
                    request.url.encodedPath.endsWith("/prose-verdict") ||
                    request.url.encodedPath.endsWith("/verdict")
                ) {
                    mutationStarted.complete(Unit)
                    holdMutation.await()
                }
                val body = when {
                    request.url.encodedPath.endsWith("/specs") ->
                        """[{"id":"s1","title":"S1","status":"needs_review"}]"""
                    request.url.encodedPath.endsWith("/threads") ||
                        request.url.encodedPath.endsWith("/plan-assumptions") -> "[]"
                    request.url.encodedPath.contains("/specs/") ->
                        """{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","updated_at":"2026-01-01T00:00:00Z"}"""
                    else -> "{}"
                }
                respond(body, HttpStatusCode.OK, jsonHdr)
            }) { mshipDefaults() })),
            source,
            backgroundScope,
            flowOf(emptyList()),
        )
        val initial = vm.state.first {
            (it as? QueueUiState.Content)?.cards?.map { card -> card.connectionId }?.distinct() == listOf("a", "b")
        } as QueueUiState.Content
        val actingConnectionId = initial.current!!.connectionId
        val remainingConnection = connsAB.single { it.id != actingConnectionId }
        val mutation = vm.approveAllCurrent()
        assertNotNull(mutation)
        runCurrent()
        mutationStarted.await()

        source.value = ConnectionState.Ready(listOf(remainingConnection))
        runCurrent()
        holdMutation.complete(Unit)
        runCurrent()

        val content = vm.state.value as QueueUiState.Content
        assertTrue(content.cards.isNotEmpty())
        assertTrue(content.cards.all { it.connectionId == remainingConnection.id })
        assertFalse(content.inFlight)
    }
}
