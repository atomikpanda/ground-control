package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.notify.NeedsYouEvent
import com.atomikpanda.groundcontrol.notify.NeedsYouReconciler
import com.atomikpanda.groundcontrol.notify.Notifier
import com.atomikpanda.groundcontrol.notify.NotifiedStore
import com.atomikpanda.groundcontrol.notify.threadKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : NotifiedStore {
    val marks = mutableSetOf<String>()
    private fun k(c: String, t: String) = "$c|$t"
    override suspend fun isNotified(connId: String, threadId: String) = k(connId, threadId) in marks
    override suspend fun markNotified(connId: String, threadId: String) { marks += k(connId, threadId) }
    override suspend fun clear(connId: String, threadId: String) { marks -= k(connId, threadId) }
}

private class FakeNotifier : Notifier {
    val events = mutableListOf<NeedsYouEvent>()
    override fun notify(event: NeedsYouEvent) { events += event }
}

class NeedsYouReconcilerTest {
    private val conn = WorkspaceConnection("c1", "http://h:47100", "tok", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun summary(id: String, needsYou: Boolean) =
        ThreadSummary(id = id, subject = "S-$id", needsYou = needsYou, lastMessage = "msg-$id", updatedAt = "2026-06-30T12:00:00Z")

    private fun decisionSummary(id: String, needsDecision: Boolean) =
        ThreadSummary(id = id, subject = "S-$id", needsDecision = needsDecision, lastMessage = "msg-$id", updatedAt = "2026-06-30T12:00:00Z")

    /** Route GET /threads (list) and GET /threads/{id} (full thread) to canned bodies. */
    private fun routedRepo(
        threads: String = "[]",
        thread: (String) -> String = { """{"id":"$it","subject":"S","messages":[]}""" },
    ): ThreadsRepository {
        val handler: MockRequestHandler = { req ->
            val path = req.url.encodedPath
            val body = when {
                Regex(".*/threads/[^/]+").matches(path) -> thread(path.substringAfterLast('/'))
                path.endsWith("/threads") -> threads
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHdr)
        }
        return ThreadsRepository(SpecApi(HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(buildJson()) }
        }))
    }

    @Test fun notifies_once_for_a_new_needs_decision() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val r = NeedsYouReconciler(store, notifier, routedRepo())
        r.reconcile(conn, listOf(decisionSummary("t1", true)))
        assertEquals(1, notifier.events.size)
        assertEquals("t1", notifier.events[0].threadId)
        r.reconcile(conn, listOf(decisionSummary("t1", true)))
        assertEquals(1, notifier.events.size)
    }

    @Test fun notifies_once_for_a_new_needs_you() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val r = NeedsYouReconciler(store, notifier, routedRepo())
        r.reconcile(conn, listOf(summary("t1", true)))
        assertEquals(1, notifier.events.size)
        assertEquals("t1", notifier.events[0].threadId)
        assertEquals("ws", notifier.events[0].workspaceName)
        r.reconcile(conn, listOf(summary("t1", true)))
        assertEquals(1, notifier.events.size)
    }

    @Test fun plain_note_does_not_notify() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        NeedsYouReconciler(store, notifier, routedRepo()).reconcile(conn, listOf(summary("t1", false)))
        assertEquals(0, notifier.events.size)
    }

    @Test fun re_notifies_after_resolve_then_recurrence() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val r = NeedsYouReconciler(store, notifier, routedRepo())
        r.reconcile(conn, listOf(summary("t1", true)))
        r.reconcile(conn, listOf(summary("t1", false)))
        r.reconcile(conn, listOf(summary("t1", true)))
        assertEquals(2, notifier.events.size)
    }

    @Test fun enriches_event_with_thread_messages_and_active_decision() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val repo = routedRepo(thread = { id ->
            """{"id":"$id","subject":"S-$id","messages":[
                {"id":"m1","thread_id":"$id","role":"human","text":"hi","created_at":"2026-06-30T12:00:00Z"},
                {"id":"m2","thread_id":"$id","role":"agent","text":"pick","created_at":"2026-06-30T12:01:00Z",
                 "kind":"decision","decision":{"options":["A","B","C"],"recommended":1,"allow_free_text":false}}
            ]}"""
        })
        NeedsYouReconciler(store, notifier, repo).reconcile(conn, listOf(decisionSummary("t1", true)))
        assertEquals(1, notifier.events.size)
        val e = notifier.events[0]
        assertEquals(2, e.messages.size)
        assertNotNull(e.decision)
        assertEquals(listOf("A", "B", "C"), e.decision!!.options)
        assertEquals(1, e.decision!!.recommended)
    }

    @Test fun enrichment_degrades_gracefully_when_get_thread_fails() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val handler: MockRequestHandler = { req ->
            if (Regex(".*/threads/[^/]+").matches(req.url.encodedPath))
                respond("""{"detail":"boom"}""", HttpStatusCode.InternalServerError, jsonHdr)
            else respond("[]", HttpStatusCode.OK, jsonHdr)
        }
        val repo = ThreadsRepository(SpecApi(HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(buildJson()) }
        }))
        NeedsYouReconciler(store, notifier, repo).reconcile(conn, listOf(summary("t1", true)))
        assertEquals(1, notifier.events.size)
        assertTrue(notifier.events[0].messages.isEmpty())
        assertNull(notifier.events[0].decision)
        assertEquals("msg-t1", notifier.events[0].preview)
    }

    @Test fun fetch_and_reconcile_via_mockengine() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val repo = routedRepo(
            threads = """[{"id":"t1","subject":"hi","needs_you":true,"last_message":"look"}]""",
            thread = { id -> """{"id":"$id","subject":"hi","messages":[]}""" },
        )
        NeedsYouReconciler(store, notifier, repo).fetchAndReconcile(conn)
        assertEquals(1, notifier.events.size)
        assertEquals("look", notifier.events[0].preview)
    }

    @Test fun suppresses_notification_while_the_thread_is_open_in_foreground() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        var openKey: String? = threadKey(conn.id, "t1")
        val r = NeedsYouReconciler(store, notifier, routedRepo(), foregroundThreadKey = { openKey })
        r.reconcile(conn, listOf(decisionSummary("t1", true)))
        assertEquals(0, notifier.events.size)   // suppressed: user is viewing t1 in the foreground
        // Deliberately NOT marked notified: once the user leaves, the next reconcile surfaces it.
        openKey = null
        r.reconcile(conn, listOf(decisionSummary("t1", true)))
        assertEquals(1, notifier.events.size)
    }

    @Test fun does_not_suppress_when_a_different_thread_is_open() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val r = NeedsYouReconciler(
            store, notifier, routedRepo(),
            foregroundThreadKey = { threadKey(conn.id, "someOtherThread") },
        )
        r.reconcile(conn, listOf(summary("t1", true)))
        assertEquals(1, notifier.events.size)
    }
}
