// app/src/test/java/com/atomikpanda/groundcontrol/QueueRepositoryTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.queue.QueueKind
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

    // wi-a: blocked + needs_review (2 cards); wi-b: needs_approval (1 card)
    private val itemsJson = """[
      {"id":"wi-a","kind":"feature","title":"A","phase":"review","updated_at":"2026-06-01T00:00:00Z",
       "attention":{"blocked":true,"needs_review":true,"blocked_tasks":1}},
      {"id":"wi-b","kind":"feature","title":"B","phase":"ready","spec_id":"s-b","updated_at":"2026-06-02T00:00:00Z",
       "attention":{"needs_approval":true}}
    ]"""

    private fun api() = SpecApi(HttpClient(MockEngine { req ->
        if (req.url.host == "bad") return@MockEngine respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
        respond(itemsJson, HttpStatusCode.OK, jsonHdr)   // any /items call
    }) { mshipDefaults() })

    @Test fun maps_attention_to_cards_and_urgency_sorts() = runTest {
        val feed = QueueRepository(api()).load(listOf(WorkspaceConnection("c1", "http://good:47100", null, "ws-a")))
        assertEquals(3, feed.cards.size)
        // URGENT (blocked) first, then APPROVAL, then REVIEW
        assertEquals(QueueKind.BLOCKED, feed.cards[0].kind)
        assertEquals(QueueKind.NEEDS_APPROVAL, feed.cards[1].kind)
        assertEquals(QueueKind.NEEDS_REVIEW, feed.cards[2].kind)
        assertTrue(feed.errors.isEmpty())
    }

    @Test fun one_failing_workspace_isolates_to_error_others_still_load() = runTest {
        val feed = QueueRepository(api()).load(listOf(
            WorkspaceConnection("ok", "http://good:47100", null, "ws-a"),
            WorkspaceConnection("c2", "http://bad:47100", null, "ws-bad"),
        ))
        assertEquals(3, feed.cards.size)
        assertTrue(feed.cards.all { it.connectionId == "ok" })
        assertEquals(listOf("ws-bad"), feed.errors.map { it.workspaceName })
    }
}
