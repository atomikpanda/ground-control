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
import com.atomikpanda.groundcontrol.ui.queue.QuestionsCard
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
    // verdict POSTs succeed and approve returns approved, so approve-all auto-approves the whole spec.
    @Test fun approve_all_marks_items_and_auto_approves_when_fully_approved() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/approve") -> """{"id":"s1","status":"approved"}"""
                path.endsWith("/verdict") ->
                    """{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"},{"id":"ac2","text":"b","verdict":"approved"}],"open_questions":[]}"""
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
        assertTrue(vm.stateContent().current is CriteriaCard)
        vm.approveAllCurrent()?.join()
        val c = vm.stateContent()
        assertTrue(c.caughtUp)            // the fully-approved spec's card left the queue
        assertEquals(1, c.resolved)
        assertNull(c.actionError)
    }

    // ws-a spec s1 with one criterion AND one open question → CriteriaCard (head) + QuestionsCard.
    // approve-all approves the criterion but /approve 409s (the question is unanswered): the head
    // advances, the QuestionsCard chunk stays in the queue.
    @Test fun approve_all_409_leaves_other_chunks_in_queue() = runTest {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/approve") ->
                    respond("""{"detail":"cannot approve: q1 unanswered"}""", HttpStatusCode.Conflict, jsonHdr)
                path.endsWith("/verdict") ->
                    respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[{"id":"q1","text":"q","answer":null}]}""", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/specs") ->
                    respond(if (req.url.host == "a") """[{"id":"s1","title":"S1","status":"needs_review"}]""" else "[]", HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond("[]", HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") ->
                    respond("""{"id":"s1","title":"S1","status":"needs_review","body":"","acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[{"id":"q1","text":"q","answer":null}],"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }
        val vm = vm(this, connsA, handler)
        vm.refresh()?.join()
        assertEquals(2, vm.stateContent().cards.size)
        assertTrue(vm.stateContent().current is CriteriaCard)
        vm.approveAllCurrent()?.join()
        val c = vm.stateContent()
        assertEquals(1, c.resolved)                         // the criteria head advanced
        assertTrue(c.current is QuestionsCard)              // the still-un-approved chunk stays
        assertNull(c.actionError)
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
        vm.setItemVerdict("s1", "ac1", "approved")?.join()
        val c = vm.stateContent()
        assertEquals(1, c.cards.size)                       // no advance
        val head = c.current as CriteriaCard
        assertEquals("approved", head.items.first { it.id == "ac1" }.verdict)
        assertEquals("unreviewed", head.items.first { it.id == "ac2" }.verdict)
        assertEquals(0, c.resolved)
    }

    private fun QueueViewModel.stateContent(): QueueUiState.Content = state.value as QueueUiState.Content
}
