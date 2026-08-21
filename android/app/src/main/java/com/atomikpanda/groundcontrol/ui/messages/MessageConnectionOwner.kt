package com.atomikpanda.groundcontrol.ui.messages

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.asStateFlow

internal const val LIVE_POLL_RETRY_DELAY_MILLIS = 2_000L

internal data class MessageRequestToken(
    val generation: Long,
    val revision: Long,
    val kind: Kind,
) {
    enum class Kind { INITIAL, REFRESH, POLL }
}

internal sealed interface MessageLoadResult

internal data class MessageFullLoad(
    val threads: List<ThreadSummary>,
    val items: List<WorkItemSummary>,
) : MessageLoadResult

internal data class MessagePollDelta(
    val changedThreads: List<ThreadSummary>,
    val cursor: String,
) : MessageLoadResult

internal data class MessageConnectionSnapshot(
    val connection: WorkspaceConnection,
    val threads: Result<List<ThreadSummary>>,
    val items: List<WorkItemSummary>,
    val cursor: String,
    val phase: Phase,
    val lastError: Throwable?,
) {
    enum class Phase { INITIAL_LOADING, INITIAL_ERROR, READY }
}

internal data class HandoffReceipt(val generation: Long)

/**
 * The sole mutable owner of one connection's message lifecycle. Requests run outside [mutex];
 * tokens make their later completions harmless if a refresh, replacement, or removal superseded
 * them while they were suspended.
 */
internal class MessageConnectionOwner(
    connection: WorkspaceConnection,
    private val fullLoad: suspend (WorkspaceConnection) -> MessageFullLoad,
    private val poll: suspend (WorkspaceConnection, String) -> MessagePollDelta,
    private val scope: CoroutineScope,
    private val cursorClock: () -> String = { java.time.Instant.now().toString() },
    private val retryDelay: suspend () -> Unit = { delay(LIVE_POLL_RETRY_DELAY_MILLIS) },
) {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(
        MessageConnectionSnapshot(
            connection = connection,
            threads = Result.success(emptyList()),
            items = emptyList(),
            cursor = "",
            phase = MessageConnectionSnapshot.Phase.INITIAL_LOADING,
            lastError = null,
        ),
    )
    val snapshot: StateFlow<MessageConnectionSnapshot> = _snapshot.asStateFlow()

    private var generation = 0L
    private var latestIssuedRevision = 0L
    private var activeToken: MessageRequestToken? = null
    private var activeRequest: Job? = null
    private var retryJob: Job? = null
    private var retiredRequestJobs = emptySet<Job>()
    private var pendingHandoffJobs = emptySet<Job>()
    private var queuedRefreshWaiters = emptyList<CompletableDeferred<Unit>>()
    private var pollingEnabled = false
    private var cancelled = false

    fun initialLoad(): Job = scope.launch {
        mutex.withLock { launchRequestLocked(MessageRequestToken.Kind.INITIAL) }?.join()
    }
    fun refresh(): Job = scope.launch {
        val queued = CompletableDeferred<Unit>()
        var queuedForHandoff = false
        val request = mutex.withLock {
            when {
                cancelled -> null
                pendingHandoffJobs.isNotEmpty() -> {
                    queuedRefreshWaiters = queuedRefreshWaiters + queued
                    queuedForHandoff = true
                    null
                }
                else -> launchRequestLocked(MessageRequestToken.Kind.REFRESH)
            }
        }
        if (request != null) waitForAuthoritativeRefresh(request) else if (queuedForHandoff) queued.await()
    }

    /** A cancelled refresh is only complete after the refresh that superseded it
     * has finished. Polls deliberately do not extend this wait. */
    private suspend fun waitForAuthoritativeRefresh(request: Job) {
        var awaited = request
        while (true) {
            awaited.join()
            awaited = mutex.withLock {
                activeRequest?.takeIf {
                    it !== awaited && activeToken?.kind == MessageRequestToken.Kind.REFRESH
                }
            } ?: return
        }

    }

    fun startPolling(): Job = scope.launch {
        mutex.withLock {
            pollingEnabled = true
            if (readyToPollLocked()) launchRequestLocked(MessageRequestToken.Kind.POLL)
        }
    }

    private fun readyToPollLocked(): Boolean =
        !cancelled && _snapshot.value.phase == MessageConnectionSnapshot.Phase.READY &&
            activeToken == null && retryJob == null && pendingHandoffJobs.isEmpty()

    private fun launchRequestLocked(kind: MessageRequestToken.Kind): Job? {
        if (cancelled || pendingHandoffJobs.isNotEmpty()) return null
        activeRequest?.let(::retireRequestLocked)
        retryJob?.cancel()
        retryJob = null
        val token = issueLocked(kind)
        val requestConnection = _snapshot.value.connection
        val request = scope.launch {
            val result: Result<MessageLoadResult> = try {
                when (kind) {
                    MessageRequestToken.Kind.INITIAL,
                    MessageRequestToken.Kind.REFRESH -> Result.success(fullLoad(requestConnection))
                    MessageRequestToken.Kind.POLL -> Result.success(poll(requestConnection, tokenCursor(token)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            complete(token, result)
        }
        activeRequest = request
        return request
    }

    private suspend fun tokenCursor(token: MessageRequestToken): String = mutex.withLock {
        if (activeToken == token) _snapshot.value.cursor else ""
    }

    private fun retireRequestLocked(request: Job) {
        retiredRequestJobs = retiredRequestJobs + request
        request.cancel()
        scope.launch {
            request.join()
            mutex.withLock { retiredRequestJobs = retiredRequestJobs - request }
        }
    }

    private fun issueLocked(kind: MessageRequestToken.Kind): MessageRequestToken {
        latestIssuedRevision += 1
        return MessageRequestToken(generation, latestIssuedRevision, kind).also { activeToken = it }
    }

    private suspend fun complete(token: MessageRequestToken, result: Result<out MessageLoadResult>) {
        mutex.withLock {
            if (!isCurrentLocked(token)) return
            activeToken = null
            activeRequest = null
            result.fold(
                onSuccess = { payload -> acceptSuccessLocked(token, payload) },
                onFailure = { failure -> acceptFailureLocked(token, failure) },
            )
        }
    }

    private fun isCurrentLocked(token: MessageRequestToken): Boolean =
        !cancelled && token.generation == generation &&
            token.revision == latestIssuedRevision && activeToken == token

    private fun acceptSuccessLocked(token: MessageRequestToken, payload: MessageLoadResult) {
        val current = _snapshot.value
        val pollMadeProgress = payload !is MessagePollDelta ||
            (payload.cursor.isNotBlank() && payload.cursor != current.cursor)
        _snapshot.value = when (payload) {
            is MessageFullLoad -> current.copy(
                threads = Result.success(payload.threads),
                items = payload.items,
                cursor = current.cursor.ifBlank {
                    payload.threads.mapNotNull { it.updatedAt }.maxOrNull() ?: cursorClock()
                },
                phase = MessageConnectionSnapshot.Phase.READY,
                lastError = null,
            )
            is MessagePollDelta -> current.copy(
                threads = Result.success(mergeThreadsById(current.threads.getOrDefault(emptyList()), payload.changedThreads)),
                cursor = payload.cursor.ifBlank { current.cursor },
                phase = MessageConnectionSnapshot.Phase.READY,
                lastError = null,
            )
        }
        if (pollingEnabled) {
            if (payload is MessagePollDelta && !pollMadeProgress) {
                scheduleRetryLocked(MessageRequestToken.Kind.POLL)
            } else {
                launchRequestLocked(MessageRequestToken.Kind.POLL)
            }
        }
    }
    private fun acceptFailureLocked(token: MessageRequestToken, failure: Throwable) {
        val current = _snapshot.value
        _snapshot.value = if (current.phase != MessageConnectionSnapshot.Phase.READY) {
            current.copy(
                threads = Result.failure(failure),
                phase = MessageConnectionSnapshot.Phase.INITIAL_ERROR,
                lastError = failure,
            )
        } else {
            current.copy(lastError = failure)
        }
        scheduleRetryLocked(token.kind)
    }

    private fun scheduleRetryLocked(kind: MessageRequestToken.Kind) {
        if (cancelled || retryJob != null || pendingHandoffJobs.isNotEmpty()) return
        retryJob = scope.launch {
            retryDelay()
            mutex.withLock {
                if (!cancelled && retryJob == coroutineContext[Job] && pendingHandoffJobs.isEmpty()) {
                    retryJob = null
                    launchRequestLocked(kind)
                }
            }
        }
    }

    internal suspend fun beginForTest(kind: MessageRequestToken.Kind): MessageRequestToken = mutex.withLock {
        activeRequest?.cancel()
        retryJob?.cancel()
        retryJob = null
        issueLocked(kind)
    }

    internal suspend fun completeForTest(token: MessageRequestToken, result: Result<out MessageLoadResult>) = complete(token, result)

    internal suspend fun pollOnceForTest(cursor: String): String {
        val tokenAndConnection = mutex.withLock {
            if (cancelled) return _snapshot.value.cursor
            val token = issueLocked(MessageRequestToken.Kind.POLL)
            token to _snapshot.value.connection
        }
        val (token, connection) = tokenAndConnection
        val result: Result<MessageLoadResult> = try {
            Result.success(poll(connection, cursor))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        complete(token, result)
        return snapshot.value.cursor
    }

    /** Detaches, cancels, and joins obsolete work before a caller may resume this owner. */
    suspend fun handoffTo(replacement: WorkspaceConnection): HandoffReceipt? {
        val handoff = mutex.withLock {
            if (_snapshot.value.connection == replacement) null
            else {
                generation += 1
                latestIssuedRevision += 1
                activeToken = null
                val jobs = (retiredRequestJobs + listOfNotNull(activeRequest, retryJob)).toList()
                retiredRequestJobs = emptySet()
                pendingHandoffJobs = pendingHandoffJobs + jobs
                activeRequest = null
                retryJob = null
                _snapshot.value = if (_snapshot.value.connection.id == replacement.id) {
                    MessageConnectionSnapshot(
                        connection = replacement,
                        threads = Result.success(emptyList()),
                        items = emptyList(),
                        cursor = "",
                        phase = MessageConnectionSnapshot.Phase.INITIAL_LOADING,
                        lastError = null,
                    )
                } else {
                    _snapshot.value.copy(connection = replacement)
                }
                Handoff(generation, jobs)
            }
        } ?: return null
        handoff.jobs.forEach(Job::cancel)
        handoff.jobs.joinAll()
        return mutex.withLock {
            if (cancelled || generation != handoff.generation || _snapshot.value.connection != replacement) null
            else {
                pendingHandoffJobs = pendingHandoffJobs - handoff.jobs.toSet()
                HandoffReceipt(handoff.generation)
            }
        }
    }

    suspend fun resumeAfterHandoff(receipt: HandoffReceipt) {
        mutex.withLock {
            if (generation != receipt.generation || cancelled || pendingHandoffJobs.isNotEmpty() ||
                activeToken != null || retryJob != null
            ) return
            val kind = when {
                queuedRefreshWaiters.isNotEmpty() -> MessageRequestToken.Kind.REFRESH
                _snapshot.value.phase != MessageConnectionSnapshot.Phase.READY ->
                    MessageRequestToken.Kind.INITIAL
                pollingEnabled -> MessageRequestToken.Kind.POLL
                else -> null
            }
            val request = kind?.let(::launchRequestLocked)
            if (kind == MessageRequestToken.Kind.REFRESH) {
                val waiters = queuedRefreshWaiters
                queuedRefreshWaiters = emptyList()
                request?.let { launched ->
                    scope.launch {
                        launched.join()
                        waiters.forEach { it.complete(Unit) }
                    }
                }
            }
        }
    }

    internal suspend fun holdMutexForTest(
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) = mutex.withLock {
        entered.complete(Unit)
        release.await()
    }

    suspend fun cancel() {
        val (jobs, refreshWaiters) = mutex.withLock {
            if (!cancelled) {
                cancelled = true
                generation += 1
                latestIssuedRevision += 1
            }
            activeToken = null
            val jobs = pendingHandoffJobs + retiredRequestJobs + listOfNotNull(activeRequest, retryJob)
            pendingHandoffJobs = emptySet()
            retiredRequestJobs = emptySet()
            activeRequest = null
            retryJob = null
            val waiters = queuedRefreshWaiters
            queuedRefreshWaiters = emptyList()
            jobs to waiters
        }
        refreshWaiters.forEach { it.complete(Unit) }
        jobs.forEach(Job::cancel)
        jobs.joinAll()
    }


    private data class Handoff(val generation: Long, val jobs: List<Job>)
}
