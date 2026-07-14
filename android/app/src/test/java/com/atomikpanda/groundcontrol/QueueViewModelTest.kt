// app/src/test/java/com/atomikpanda/groundcontrol/QueueViewModelTest.kt
//
// Queue v2 card-stack coverage: the head-stable machinery (load / position /
// skip-to-back) plus the PR3 transitions — approve-all + auto-approve, the
// auto-approve 409 (remaining chunks stay), reject (flag + request-changes clears
// the spec), and per-item verdicts applied in place.
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.queue.CriteriaCard
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(scope: CoroutineScope, conns: List<WorkspaceConnection>, handler: MockRequestHandler): QueueViewModel {
        val repo = QueueRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }))
        return QueueViewModel(repo, { conns }, scope)
    }

    private val connsAB = listOf(
        WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
        WorkspaceConnection("b", "http://b:47100", null, "ws-b"),
    )
    private val connsA = listOf(WorkspaceConnection("a", "http://a:47100", null, "ws-a"))
    private val onlyEmpty = listOf(WorkspaceConnection("b", "http://b:47100", null, "ws-b"))

    // ws-a: one needs_review spec (s1) with two prose sections → two prose cards, no threads.
    private fun proseRepoHandler(): MockRequestHandler = { req ->
        val path = req.url.encodedPath
        val body = when {
            path.endsWith("/specs") -> if (req.url.host == "a")
                """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]"
            path.endsWith("/threads") -> "[]"
            path.contains("/specs/") ->
                """{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","updated_at":"2026-01-01T00:00:00Z"}"""
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, jsonHdr)
    }

    @Test fun no_connections_yields_empty_config() = runTest {
        val vm = QueueViewModel(QueueRepository(SpecApi(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, jsonHdr) }) { mshipDefaults() })), { emptyList() }, this)
        vm.refresh()
        assertEquals(QueueUiState.EmptyConfig, vm.state.value)
    }

    @Test fun loads_prose_cards_head_first_with_position() = runTest {
        val vm = vm(this, connsAB, proseRepoHandler())
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
        val vm = vm(this, onlyEmpty, proseRepoHandler())
        vm.refresh()?.join()
        val c = vm.state.value as QueueUiState.Content
        assertTrue(c.caughtUp)
        assertNull(c.current)
    }

    @Test fun skip_sends_head_to_the_back() = runTest {
        val vm = vm(this, connsAB, proseRepoHandler())
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
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(this, connsA, handler)
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
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","acceptance_criteria":[],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(this, connsA, handler)
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
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(this, connsA, handler)
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
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(this, connsA, handler)
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
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(this, connsA, handler)
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
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[{"id":"q1","text":"q?","answer":null}],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(this, connsA, handler)
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
                path.contains("/specs/") ->
                    """{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = vm(this, connsAB, handler)
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
                path.contains("/specs/") ->
                    """{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1\n\n## Approach\n\nA1","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = vm(this, connsA, handler)
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
                path.contains("/specs/") ->
                    """{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"},{"id":"ac2","text":"b","verdict":"unreviewed"}],"open_questions":[],"updated_at":"2026-01-01T00:00:00Z"}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        val vm = vm(this, connsA, handler)
        vm.refresh()?.join()
        vm.setItemVerdict("a", "s1", "ac1", "approved")?.join()
        val c = vm.stateContent()
        assertEquals(1, c.cards.size)                       // no advance
        val head = c.current as CriteriaCard
        assertEquals("approved", head.items.first { it.id == "ac1" }.verdict)
        assertEquals("unreviewed", head.items.first { it.id == "ac2" }.verdict)
        assertEquals(0, c.resolved)
    }

    private fun QueueViewModel.stateContent(): QueueUiState.Content = state.value as QueueUiState.Content
}
