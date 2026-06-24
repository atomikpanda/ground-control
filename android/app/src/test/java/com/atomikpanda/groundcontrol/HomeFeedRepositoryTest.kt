package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.home.NeedsYouItem
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

class HomeFeedRepositoryTest {
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private val specsJson = """[{"id":"s1","title":"Dark mode","status":"needs_review"},
                                {"id":"s2","title":"Done","status":"approved"}]"""
    private val threadsJson = """[{"id":"t1","subject":"Notifs","awaiting_reply":true,"updated_at":"2026-06-24T10:00:00Z"},
                                  {"id":"t2","subject":"Idle","awaiting_reply":false}]"""
    private val tasksJson = """[{"slug":"k1","phase":"dev","branch":"b","blocked_reason":"needs key","created_at":"2026-06-24T09:00:00Z"},
                                {"slug":"k2","phase":"dev","branch":"b"}]"""

    /** Route by path; fail every call to host "bad"; fail only /tasks for host "partial". */
    private fun api() = SpecApi(HttpClient(MockEngine { req ->
        if (req.url.host == "bad") return@MockEngine respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
        val p = req.url.encodedPath
        if (req.url.host == "partial" && p.endsWith("/tasks")) {
            return@MockEngine respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
        }
        when {
            p.endsWith("/specs") -> respond(specsJson, HttpStatusCode.OK, jsonHdr)
            p.endsWith("/threads") -> respond(threadsJson, HttpStatusCode.OK, jsonHdr)
            p.endsWith("/tasks") -> respond(tasksJson, HttpStatusCode.OK, jsonHdr)
            else -> respond("[]", HttpStatusCode.OK, jsonHdr)
        }
    }) { mshipDefaults() })

    @Test fun merges_filtered_items_across_workspaces_sorted_by_urgency() = runTest {
        val repo = HomeFeedRepository(api())
        val feed = repo.load(listOf(WorkspaceConnection("c1", "http://good:47100", null, "ws-a")))
        // 1 approval + 1 question + 1 blocker survive filtering
        assertEquals(3, feed.items.size)
        assertTrue(feed.items[0] is NeedsYouItem.Blocker)   // urgency: blocker first
        assertTrue(feed.items[1] is NeedsYouItem.Question)
        assertTrue(feed.items[2] is NeedsYouItem.Approval)
        assertTrue(feed.errors.isEmpty())
    }

    @Test fun one_failing_workspace_isolates_to_error_others_still_load() = runTest {
        val repo = HomeFeedRepository(api())
        val feed = repo.load(listOf(
            WorkspaceConnection("ok", "http://good:47100", null, "ws-a"),
            WorkspaceConnection("c2", "http://bad:47100", null, "ws-bad"),
        ))
        assertEquals(3, feed.items.size)                    // ok workspace's items present
        assertTrue(feed.items.all { it.connectionId == "ok" })
        assertEquals(listOf("ws-bad"), feed.errors.map { it.workspaceName })
    }

    @Test fun partial_source_failure_keeps_good_sources_and_records_error() = runTest {
        val repo = HomeFeedRepository(api())
        val feed = repo.load(listOf(WorkspaceConnection("p1", "http://partial:47100", null, "ws-partial")))
        // /specs + /threads succeed, /tasks 500s: approval + question survive, no blocker
        assertEquals(2, feed.items.size)
        assertTrue(feed.items.any { it is NeedsYouItem.Approval })
        assertTrue(feed.items.any { it is NeedsYouItem.Question })
        assertTrue(feed.items.none { it is NeedsYouItem.Blocker })
        // the failed /tasks source still flags the workspace as errored
        assertEquals(listOf("ws-partial"), feed.errors.map { it.workspaceName })
    }
}
