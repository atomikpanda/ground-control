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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    @Test fun notifies_once_for_a_new_needs_decision() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val r = NeedsYouReconciler(store, notifier)
        r.reconcile(conn, listOf(decisionSummary("t1", true)))
        assertEquals(1, notifier.events.size)
        assertEquals("t1", notifier.events[0].threadId)
        r.reconcile(conn, listOf(decisionSummary("t1", true)))
        assertEquals(1, notifier.events.size)
    }

    @Test fun notifies_once_for_a_new_needs_you() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val r = NeedsYouReconciler(store, notifier)
        r.reconcile(conn, listOf(summary("t1", true)))
        assertEquals(1, notifier.events.size)
        assertEquals("t1", notifier.events[0].threadId)
        assertEquals("ws", notifier.events[0].workspaceName)
        r.reconcile(conn, listOf(summary("t1", true)))
        assertEquals(1, notifier.events.size)
    }

    @Test fun plain_note_does_not_notify() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        NeedsYouReconciler(store, notifier).reconcile(conn, listOf(summary("t1", false)))
        assertEquals(0, notifier.events.size)
    }

    @Test fun re_notifies_after_resolve_then_recurrence() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val r = NeedsYouReconciler(store, notifier)
        r.reconcile(conn, listOf(summary("t1", true)))
        r.reconcile(conn, listOf(summary("t1", false)))
        r.reconcile(conn, listOf(summary("t1", true)))
        assertEquals(2, notifier.events.size)
    }

    @Test fun fetch_and_reconcile_via_mockengine() = runTest {
        val store = FakeStore(); val notifier = FakeNotifier()
        val api = SpecApi(HttpClient(MockEngine {
            respond("""[{"id":"t1","subject":"hi","needs_you":true,"last_message":"look"}]""",
                HttpStatusCode.OK, jsonHdr)
        }) { install(ContentNegotiation) { json(buildJson()) } })
        val r = NeedsYouReconciler(store, notifier)
        r.fetchAndReconcile(conn, ThreadsRepository(api))
        assertEquals(1, notifier.events.size)
        assertEquals("look", notifier.events[0].preview)
    }
}
