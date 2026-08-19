// app/src/test/java/com/atomikpanda/groundcontrol/QueueRepositoryTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.WorkspaceErrorAction
import com.atomikpanda.groundcontrol.data.hostAwareClient
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.queue.DecisionCard
import com.atomikpanda.groundcontrol.ui.queue.PlanAssumptionCard
import com.atomikpanda.groundcontrol.ui.queue.ProseCard
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRepositoryTest {
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    // Host "b" carries one needs_review spec (s3) and no threads; every other (good)
    // host carries one needs_review spec (s1) + one approved spec (s2, filtered out) and
    // one open-decision thread (t1) + one plain thread (t2, filtered out).
    private val specsDefault =
        """[{"id":"s1","title":"S1","status":"needs_review"},{"id":"s2","title":"S2","status":"approved"}]"""
    private val specsB = """[{"id":"s3","title":"S3","status":"needs_review"}]"""
    private val threadsDefault =
        """[{"id":"t1","needs_decision":true},{"id":"t2","subject":"x","needs_decision":false}]"""
    private val threadDetail =
        """{"id":"t1","updated_at":"2026-06-03T00:00:00Z","messages":[
             {"id":"m1","role":"agent","text":"Pick one","kind":"decision","decision":{"options":["X","Y"]}}]}"""

    private fun specDetail(id: String): String = when (id) {
        "s3" -> """{"id":"s3","title":"S3","status":"needs_review","body":"## Problem\n\nP3","updated_at":"2026-06-02T00:00:00Z"}"""
        else -> """{"id":"s1","title":"S1","status":"needs_review","body":"## Problem\n\nP1","updated_at":"2026-06-01T00:00:00Z"}"""
    }

    private fun api() = SpecApi(HttpClient(MockEngine { req ->
        if (req.url.host == "bad") return@MockEngine respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
        val path = req.url.encodedPath
        val body = when {
            path.endsWith("/specs") -> if (req.url.host == "b") specsB else specsDefault
            path.endsWith("/threads") -> if (req.url.host == "b") "[]" else threadsDefault
            path.endsWith("/plan-assumptions") -> "[]"
            path.contains("/specs/") -> specDetail(path.substringAfterLast("/"))
            path.contains("/threads/") -> threadDetail
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, jsonHdr)
    }) { mshipDefaults() })

    // A dedicated MockEngine for the plan-assumptions source: no specs/threads noise, just the
    // `GET /plan-assumptions` list this test cares about.
    private fun planAssumptionsApi(listBody: String) = SpecApi(HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val body = when {
            path.endsWith("/plan-assumptions") -> listBody
            path.endsWith("/specs") -> "[]"
            path.endsWith("/threads") -> "[]"
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, jsonHdr)
    }) { mshipDefaults() })

    @Test fun plan_assumptions_with_pending_become_a_card_pending_zero_does_not() = runTest {
        val listBody = """[{"task":"dq","fresh":true,"pending":2},{"task":"ok","fresh":true,"pending":0}]"""
        val feed = QueueRepository(planAssumptionsApi(listBody)).load(listOf(
            WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
        ))
        assertTrue(feed.errors.isEmpty())
        val cards = feed.cards.filterIsInstance<PlanAssumptionCard>()
        assertEquals(1, cards.size)
        assertEquals("dq", cards[0].task)
        assertEquals(2, cards[0].pending)
    }

    @Test fun two_workspaces_merge_into_prose_and_decision_cards_urgency_sorted() = runTest {
        val feed = QueueRepository(api()).load(listOf(
            WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
            WorkspaceConnection("b", "http://b:47100", null, "ws-b"),
        ))
        assertTrue(feed.errors.isEmpty())
        // ws-a: 1 prose (s1) + 1 decision (t1); ws-b: 1 prose (s3). s2/t2 are filtered out.
        assertEquals(3, feed.cards.size)
        // URGENT decision sorts ahead of the APPROVAL prose chunks
        assertTrue(feed.cards[0] is DecisionCard)
        assertEquals("t1", (feed.cards[0] as DecisionCard).threadId)
        val prose = feed.cards.filterIsInstance<ProseCard>()
        assertEquals(setOf("s1", "s3"), prose.map { it.specId }.toSet())
        assertEquals(setOf("a", "b"), feed.cards.map { it.connectionId }.toSet())
    }

    @Test fun plan_assumptions_404_degrades_to_no_card_not_empty_queue() = runTest {
        val api = SpecApi(HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/plan-assumptions") -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                path.endsWith("/specs") -> respond(specsDefault, HttpStatusCode.OK, jsonHdr)
                path.endsWith("/threads") -> respond(threadsDefault, HttpStatusCode.OK, jsonHdr)
                path.contains("/specs/") -> respond(specDetail(path.substringAfterLast("/")), HttpStatusCode.OK, jsonHdr)
                path.contains("/threads/") -> respond(threadDetail, HttpStatusCode.OK, jsonHdr)
                else -> respond("{}", HttpStatusCode.OK, jsonHdr)
            }
        }) { mshipDefaults() })
        val feed = QueueRepository(api).load(listOf(
            WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
        ))
        assertTrue(feed.errors.isEmpty())
        // The spec + decision cards still render even though plan-assumptions 404s.
        assertEquals(2, feed.cards.size)
        assertTrue(feed.cards.filterIsInstance<PlanAssumptionCard>().isEmpty())
    }

    @Test fun one_failing_workspace_isolates_to_error_others_still_load() = runTest {
        val feed = QueueRepository(api()).load(listOf(
            WorkspaceConnection("ok", "http://good:47100", null, "ws-good"),
            WorkspaceConnection("c2", "http://bad:47100", null, "ws-bad"),
        ))
        // ws-good still sources its prose + decision cards; ws-bad isolates to an error.
        assertEquals(2, feed.cards.size)
        assertTrue(feed.cards.all { it.connectionId == "ok" })
        assertEquals(listOf("ws-bad"), feed.errors.map { it.workspaceName })
    }

    @Test fun rejected_refresh_is_surfaced_as_a_re_pair_action() = runTest {
        val host = HostConnection(
            hostId = "h-1",
            publicUrl = "https://host.example.com",
            refresh = "rejected",
        )
        val client = hostAwareClient(
            engine = MockEngine { req ->
                if (req.url.encodedPath == "/host/token") {
                    respond("""{"detail":"invalid refresh"}""", HttpStatusCode.Unauthorized, jsonHdr)
                } else {
                    respond("[]", HttpStatusCode.OK, jsonHdr)
                }
            },
            hosts = { listOf(host) },
        )
        val connection = WorkspaceConnection(
            id = "c1",
            baseUrl = "https://host.example.com/workspaces/ws-1",
            workspaceName = "ws",
            hostId = "h-1",
            workspaceId = "ws-1",
        )

        val feed = QueueRepository(SpecApi(client.client)).load(listOf(connection))

        assertEquals(WorkspaceErrorAction.RE_PAIR, feed.errors.single().action)
    }

    @Test fun a_legacy_url_host_id_does_not_claim_a_fleet_host_error() = runTest {
        val legacy = WorkspaceConnection(
            id = "legacy",
            baseUrl = "http://bad:47100/workspaces/ws-1",
            workspaceName = "legacy",
            hostId = "http://bad:47100",
            workspaceId = "ws-1",
        )

        val error = QueueRepository(api()).load(listOf(legacy)).errors.single()

        assertEquals(null, error.hostId)
    }
}
