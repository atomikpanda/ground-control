package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.ApiResponseException
import com.atomikpanda.groundcontrol.notify.ReplyExecutor
import com.atomikpanda.groundcontrol.notify.ReplyInputKind
import com.atomikpanda.groundcontrol.notify.ReplyOutboxRecord
import com.atomikpanda.groundcontrol.notify.ReplyOutboxState
import com.atomikpanda.groundcontrol.notify.ReplyOutboxStore
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOutboxStore(private val current: () -> Boolean = { true }) : ReplyOutboxStore {
    private val lock = Mutex()
    val rows = mutableMapOf<String, ReplyOutboxRecord>()
    suspend fun seed(row: ReplyOutboxRecord) = lock.withLock { rows[row.actionKey] = row }
    override suspend fun get(actionKey: String) = lock.withLock { rows[actionKey] }
    override suspend fun moveReadyToWaiting(row: ReplyOutboxRecord) = lock.withLock {
        val saved = rows[row.actionKey] ?: return@withLock false
        if (saved.state != ReplyOutboxState.READY || !current()) false
        else { rows[row.actionKey] = saved.copy(state = ReplyOutboxState.WAITING_FOR_CONNECTION); true }
    }
    override suspend fun claim(row: ReplyOutboxRecord, executionId: String) = lock.withLock {
        val saved = rows[row.actionKey] ?: return@withLock false
        if (saved.state != ReplyOutboxState.READY || !current()) false
        else { rows[row.actionKey] = saved.copy(state = ReplyOutboxState.IN_FLIGHT, executionId = executionId); true }
    }
    override suspend fun complete(row: ReplyOutboxRecord, executionId: String, next: ReplyOutboxState) = lock.withLock {
        val saved = rows[row.actionKey] ?: return@withLock false
        if (saved.state != ReplyOutboxState.IN_FLIGHT || saved.executionId != executionId) false
        else { rows[row.actionKey] = saved.copy(state = next, executionId = null); true }
    }
}

class ReplyExecutorLifecycleTest {
    private val connection = WorkspaceConnection("canonical", "https://example", "token", "workspace")
    private fun ready(key: String = "opaque") = ReplyOutboxRecord(
        actionKey = key, connectionId = connection.id, threadId = "thread", notificationVersion = "source#1",
        state = ReplyOutboxState.READY, executionId = null, claimedAtMillis = null, renderVersion = null,
        renderCapabilityKey = null, replyText = "exact reply", inputKind = ReplyInputKind.FREE_TEXT,
        subject = "subject", workspace = "workspace", baseUrl = connection.baseUrl, decisionJson = "{\"options\":[\"A\"]}",
        retryAttempt = 0, createdAtMillis = 1,
    )

    @Test fun two_executors_racing_post_at_most_once() = runTest {
        val store = FakeOutboxStore(); store.seed(ready())
        val posts = AtomicInteger(); val rendered = mutableListOf<String>()
        val executor = ReplyExecutor(store, { listOf(connection) }, { _, _ -> posts.incrementAndGet() }, { rendered += it })
        awaitAll(async { executor.execute("opaque", "one") }, async { executor.execute("opaque", "two") })
        assertEquals(1, posts.get())
        assertEquals(ReplyOutboxState.DELIVERED_PENDING_RENDER, store.get("opaque")!!.state)
        assertEquals(listOf("opaque"), rendered)
    }

    @Test fun timeout_after_request_start_is_terminal_uncertain_without_retry() = runTest {
        val store = FakeOutboxStore(); store.seed(ready())
        val posts = AtomicInteger()
        val executor = ReplyExecutor(store, { listOf(connection) }, { _, _ -> posts.incrementAndGet(); throw SocketTimeoutException() }, {})
        executor.execute("opaque", "one"); executor.execute("opaque", "two")
        assertEquals(1, posts.get())
        assertEquals(ReplyOutboxState.UNCERTAIN_PENDING_RENDER, store.get("opaque")!!.state)
    }

    @Test fun unresolved_address_is_safe_failure_and_immediately_requests_render() = runTest {
        val store = FakeOutboxStore(); store.seed(ready())
        val rendered = mutableListOf<String>()
        ReplyExecutor(store, { listOf(connection) }, { _, _ -> throw UnresolvedAddressException() }, { rendered += it })
            .execute("opaque", "one")
        assertEquals(ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER, store.get("opaque")!!.state)
        assertEquals(listOf("opaque"), rendered)
    }

    @Test fun missing_connection_moves_to_waiting_without_post_or_retry() = runTest {
        val store = FakeOutboxStore(); store.seed(ready())
        var posted = false
        ReplyExecutor(store, { emptyList() }, { _, _ -> posted = true }, {}).execute("opaque", "one")
        assertEquals(ReplyOutboxState.WAITING_FOR_CONNECTION, store.get("opaque")!!.state)
        assertTrue(!posted)
    }

    @Test fun different_execution_cannot_complete_another_claim() = runTest {
        val store = FakeOutboxStore(); store.seed(ready())
        assertTrue(store.claim(ready(), "owner"))
        assertTrue(!store.complete(ready(), "other", ReplyOutboxState.DELIVERED_PENDING_RENDER))
        assertEquals(ReplyOutboxState.IN_FLIGHT, store.get("opaque")!!.state)
    }

    @Test fun http_4xx_is_safe_failure_while_server_failure_is_uncertain() = runTest {
        val safe = FakeOutboxStore(); safe.seed(ready("safe"))
        ReplyExecutor(safe, { listOf(connection) }, { _, _ -> throw ApiResponseException(HttpStatusCode.BadRequest, "rejected") }, {}).execute("safe", "one")
        assertEquals(ReplyOutboxState.SAFE_FAILURE_PENDING_RENDER, safe.get("safe")!!.state)
        val uncertain = FakeOutboxStore(); uncertain.seed(ready("uncertain"))
        ReplyExecutor(uncertain, { listOf(connection) }, { _, _ -> throw IllegalStateException("server contacted") }, {}).execute("uncertain", "one")
        assertEquals(ReplyOutboxState.UNCERTAIN_PENDING_RENDER, uncertain.get("uncertain")!!.state)
    }
}
