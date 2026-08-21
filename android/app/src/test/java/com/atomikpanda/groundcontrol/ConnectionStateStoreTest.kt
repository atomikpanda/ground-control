package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ConnectionStateStore
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionStateStoreTest {
    private val connectionA = WorkspaceConnection("a", "https://a", "token-a", "A")
    private val connectionB = WorkspaceConnection("b", "https://b", "token-b", "B")

    @Test fun empty_first_emission_is_ready_not_loading() = runTest {
        val source = MutableSharedFlow<List<WorkspaceConnection>>()
        val store = ConnectionStateStore(source, backgroundScope)
        runCurrent()
        assertEquals(ConnectionState.Loading, store.state.value)

        source.emit(emptyList())
        runCurrent()

        assertEquals(ConnectionState.Ready(emptyList()), store.state.value)
    }

    @Test fun ready_error_retry_ready_never_reenters_initial_loading() = runTest {
        val attempts = Channel<Flow<List<WorkspaceConnection>>>(Channel.UNLIMITED)
        val store = ConnectionStateStore({ flow { emitAll(attempts.receive()) } }, backgroundScope)
        attempts.send(flow {
            emit(listOf(connectionA))
            throw IOException("disk")
        })
        runCurrent()
        assertTrue(store.state.value is ConnectionState.Error)

        store.retry()
        assertTrue(store.state.value is ConnectionState.Error)
        attempts.send(flowOf(listOf(connectionB)))
        runCurrent()

        assertEquals(ConnectionState.Ready(listOf(connectionB)), store.state.value)
    }

    @Test fun initial_error_retry_keeps_error_visible_until_ready() = runTest {
        val attempts = Channel<Flow<List<WorkspaceConnection>>>(Channel.UNLIMITED)
        val store = ConnectionStateStore({ flow { emitAll(attempts.receive()) } }, backgroundScope)
        attempts.send(flow { throw IOException("disk") })
        runCurrent()
        val error = store.state.value
        assertTrue(error is ConnectionState.Error)

        store.retry()
        assertSame(error, store.state.value)
        attempts.send(flowOf(listOf(connectionA)))
        runCurrent()

        assertEquals(ConnectionState.Ready(listOf(connectionA)), store.state.value)
    }

    @Test fun retry_is_single_flight_and_recovers_after_source_failure() = runTest {
        val attempts = Channel<Flow<List<WorkspaceConnection>>>(Channel.UNLIMITED)
        val store = ConnectionStateStore({ flow { emitAll(attempts.receive()) } }, backgroundScope)
        attempts.send(flow { throw IOException("disk") })
        runCurrent()

        store.retry()
        store.retry()
        attempts.send(flowOf(listOf(connectionA)))
        runCurrent()

        assertEquals(ConnectionState.Ready(listOf(connectionA)), store.state.value)
        assertTrue(attempts.isEmpty)
    }
}
