package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailUiState
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.ArrayDeque
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpecDetailViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        handler: io.ktor.client.engine.mock.MockRequestHandler,
    ): SpecDetailViewModel {
        val repo = SpecDetailRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }))
        return SpecDetailViewModel(repo, conn, "s1", testScope = scope)
    }

    @Test fun load_success_builds_content_with_full_body() = runTest {
        val vm = vm(this) {
            respond("""{"id":"s1","title":"T","status":"needs_review","body":"## Problem\n\nhi\n## Architecture\n\nz",
                        "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],
                        "open_questions":[{"id":"q1","text":"q","answer":null}],
                        "non_goals":["ng"],"risks":["rk"]}""", HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("T", c.detail.title)
        assertTrue(c.detail.bodyMarkdown.contains("## Architecture"))
        assertEquals(1, c.detail.summary.unansweredQuestions)
    }

    @Test fun load_404_maps_to_not_found() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.NotFound) }
        vm.load()?.join()
        assertEquals(ErrorKind.NOT_FOUND, (vm.state.value as SpecDetailUiState.Error).kind)
    }

    @Test fun load_401_maps_to_auth() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.Unauthorized) }
        vm.load()?.join()
        assertEquals(ErrorKind.AUTH, (vm.state.value as SpecDetailUiState.Error).kind)
    }

    @Test fun set_verdict_patches_state_from_returned_review() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[]}""",
                HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"needs_review",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[],
                "summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.setVerdict("ac1", "approved")?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("approved", c.detail.criteria.first().verdict)
        assertEquals(1, c.detail.summary.approved)
    }

    @Test fun approve_gated_409_surfaces_blockers() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[]}""",
                HttpStatusCode.OK, jsonHdr)
            else respond("""{"detail":"cannot approve: ac1 not approved; q1 unanswered"}""",
                HttpStatusCode.Conflict, jsonHdr)
        }
        vm.load()?.join()
        vm.approve(bypass = false)?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("needs_review", c.detail.status)         // unchanged
        assertNotNull(c.blockers)
        assertEquals(2, c.blockers!!.size)
    }

    @Test fun dispatch_success_sets_result_and_status() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"approved","body":"b"}""", HttpStatusCode.OK, jsonHdr)
            else respond("""{"spec":{"id":"s1","title":"T","status":"dispatched"},"task_slug":"s1","spawned":true,"handoff":"go"}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.dispatch()?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("dispatched", c.detail.status)
        assertEquals("s1", c.dispatchResult!!.taskSlug)
    }

    @Test fun stale_409_on_dispatch_sets_banner_and_refetches() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            when (call) {
                1 -> respond("""{"id":"s1","title":"T","status":"approved","body":"b"}""", HttpStatusCode.OK, jsonHdr)
                2 -> respond("""{"detail":"invalid transition approved -> dispatched"}""", HttpStatusCode.Conflict, jsonHdr)
                else -> respond("""{"id":"s1","title":"T","status":"dispatched","body":"b"}""", HttpStatusCode.OK, jsonHdr)
            }
        }
        vm.load()?.join()
        vm.dispatch()?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertNotNull(c.banner)
        assertEquals("dispatched", c.detail.status)            // refetched truth
    }

    @Test fun answer_patches_question() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[],"open_questions":[{"id":"q1","text":"q","answer":null}]}""",
                HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[],
                "open_questions":[{"id":"q1","text":"q","answer":"yes"}],
                "summary":{"criteria_total":0,"approved":0,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.answer("q1", "yes")?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("yes", c.detail.questions.first().answer)
        assertEquals(0, c.detail.summary.unansweredQuestions)
    }

    @Test fun approve_success_transitions_to_approved() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""",
                HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"approved",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[],
                "summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.approve(bypass = false)?.join()
        assertEquals("approved", (vm.state.value as SpecDetailUiState.Content).detail.status)
    }

    @Test fun set_verdict_stale_409_refetches_and_shows_banner() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            when (call) {
                1 -> respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                    "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[]}""",
                    HttpStatusCode.OK, jsonHdr)
                2 -> respond("""{"detail":"invalid transition needs_review -> approved"}""", HttpStatusCode.Conflict, jsonHdr)
                else -> respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                    "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""",
                    HttpStatusCode.OK, jsonHdr)
            }
        }
        vm.load()?.join()
        vm.setVerdict("ac1", "approved")?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertNotNull(c.banner)
        assertEquals("approved", c.detail.criteria.first().verdict)
    }

    @Test fun dispatch_auto_spawn_unavailable_shows_actionable_banner() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"approved","body":"b"}""", HttpStatusCode.OK, jsonHdr)
            else respond("""{"detail":"auto-spawn unavailable: this server has no worktree manager; spawn a task named 's1' first, then dispatch."}""",
                HttpStatusCode.Conflict, jsonHdr)
        }
        vm.load()?.join()
        vm.dispatch()?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertNotNull(c.banner)
        assertTrue(c.banner!!.contains("Auto-spawn") || c.banner!!.contains("terminal"))
        assertEquals("approved", c.detail.status)   // unchanged; no spurious refetch
    }

    @Test fun request_changes_transitions_to_needs_clarification() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"needs_clarification","acceptance_criteria":[],"open_questions":[],
                "summary":{"criteria_total":0,"approved":0,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.requestChanges("needs work")?.join()
        assertEquals("needs_clarification", (vm.state.value as SpecDetailUiState.Content).detail.status)
    }

    @Test fun activity_poll_advances_task_phase_then_stops_at_terminal() = runTest {
        // /specs/s1: dispatched (load), dispatched (tick 1), implemented (tick 2 -> stop).
        val specStatuses = ArrayDeque(listOf("dispatched", "dispatched", "implemented"))
        val vm = vm(this) { req ->
            when (req.url.encodedPath) {
                "/specs/s1" -> {
                    val status = if (specStatuses.size > 1) specStatuses.removeFirst() else specStatuses.first()
                    respond(
                        """{"id":"s1","title":"T","status":"$status","body":"b","task_slug":"s1"}""",
                        HttpStatusCode.OK, jsonHdr,
                    )
                }
                "/tasks/s1" -> respond(
                    """{"slug":"s1","phase":"dev","branch":"b","finished_at":null,
                        "last_activity_at":"2026-07-13T12:00:00Z"}""",
                    HttpStatusCode.OK, jsonHdr,
                )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        vm.startActivityPolling(intervalMs = 1000).join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("dev", c.detail.taskPhase)
        assertEquals("2026-07-13T12:00:00Z", c.detail.taskLastActivityAt)
        assertEquals("implemented", c.detail.status) // terminal reached; poll stopped
    }

    @Test fun activity_poll_does_not_run_for_non_dispatched_spec() = runTest {
        var taskCalls = 0
        val vm = vm(this) { req ->
            when (req.url.encodedPath) {
                "/specs/s1" -> respond(
                    """{"id":"s1","title":"T","status":"approved","body":"b","task_slug":"s1"}""",
                    HttpStatusCode.OK, jsonHdr,
                )
                "/tasks/s1" -> { taskCalls++; respond("""{"slug":"s1","phase":"plan","branch":"b"}""", HttpStatusCode.OK, jsonHdr) }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        vm.load()?.join()
        vm.startActivityPolling(intervalMs = 1000).join()
        assertEquals(0, taskCalls) // never polled a non-dispatched (approved) spec
    }

    @Test fun lead_and_guidance_surface_for_review_spec_with_sole_unanswered_blocker() = runTest {
        val vm = vm(this) {
            respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],
                "open_questions":[{"id":"q1","text":"q","answer":null}]}""", HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        val d = (vm.state.value as SpecDetailUiState.Content).detail
        assertEquals("1 unanswered question(s) — answer to approve", d.unansweredLead)   // ac1
        assertEquals("Answer 1 question(s) to approve", d.approveGuidance)               // ac2
    }

    @Test fun no_lead_or_guidance_when_no_open_questions() = runTest {                    // ac5
        val vm = vm(this) {
            respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        val d = (vm.state.value as SpecDetailUiState.Content).detail
        assertNull(d.unansweredLead)
        assertNull(d.approveGuidance)
    }

    @Test fun answering_last_question_auto_approves_and_clears_lead_and_guidance() = runTest {   // ac4
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],
                "open_questions":[{"id":"q1","text":"q","answer":null}]}""", HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"approved",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],
                "open_questions":[{"id":"q1","text":"q","answer":"yes"}],
                "summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        assertNotNull((vm.state.value as SpecDetailUiState.Content).detail.approveGuidance)  // blocked before
        vm.answer("q1", "yes")?.join()
        val d = (vm.state.value as SpecDetailUiState.Content).detail
        assertEquals("approved", d.status)   // server auto-approved on the answer POST; no Request-changes
        assertNull(d.unansweredLead)
        assertNull(d.approveGuidance)
    }
}
