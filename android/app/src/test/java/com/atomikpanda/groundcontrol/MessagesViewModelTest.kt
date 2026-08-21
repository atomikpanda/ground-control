package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.messages.MessagesUiState
import com.atomikpanda.groundcontrol.ui.messages.MessageConnectionSnapshot
import com.atomikpanda.groundcontrol.ui.messages.MessagesViewModel
import com.atomikpanda.groundcontrol.ui.messages.ThreadStateFilter
import com.atomikpanda.groundcontrol.ui.messages.mergeThreadsById
import com.atomikpanda.groundcontrol.ui.messages.unreadCountFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun MessagesUiState.Content.threadsFor(connectionId: String): List<ThreadSummary> =
    sections.single { it.connectionId == connectionId }.threads.getOrThrow()

private fun MessagesViewModel.content(): MessagesUiState.Content =
    state.value as MessagesUiState.Content

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun repo() = ThreadsRepository(SpecApi(HttpClient(MockEngine {
        respond(
            """[{"id":"t1","subject":"Hello","awaiting_reply":true,"last_message":"Hi there","updated_at":"2026-06-22T10:00:00Z"},
               {"id":"t2","subject":"World","awaiting_reply":false,"last_message":"Done","updated_at":"2026-06-22T11:00:00Z"}]""",
            HttpStatusCode.OK, jsonHdr
        )
    }) { install(ContentNegotiation) { json(buildJson()) } }))

    @Test fun sections_carry_workspace_name_and_connection_id_and_threads() = runTest {
        val vm = MessagesViewModel(
            repo(),
            flowOf(listOf(WorkspaceConnection("42", "http://h:47100", null, "ws-alpha"))),
            backgroundScope,
        )
        vm.refresh()?.join()
        val content = vm.state.value as MessagesUiState.Content
        val sec = content.sections[0]
        assertEquals("ws-alpha", sec.workspaceName)
        assertEquals("42", sec.connectionId)
        val threads = sec.threads.getOrThrow()
        assertEquals(2, threads.size)
        assertEquals("t1", threads[0].id)
        assertEquals("t2", threads[1].id)
    }

    @Test fun empty_connections_yields_empty_config() = runTest {
        val vm = MessagesViewModel(repo(), flowOf(emptyList()), backgroundScope)
        vm.refresh()?.join()
        assertEquals(MessagesUiState.EmptyConfig, vm.state.value)
    }

    // --- live-merge + filters + unread counts -------------------------------------------------

    private val connA = WorkspaceConnection("A", "http://a:47100", null, "ws-a")
    private val connB = WorkspaceConnection("B", "http://b:47100", null, "ws-b")

    private fun repoWith(handler: MockRequestHandler) =
        ThreadsRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }))

    private val threeThreadsJson = """
        [{"id":"t3","subject":"c","updated_at":"2026-06-22T11:00:00Z"},
         {"id":"t2","subject":"b","updated_at":"2026-06-22T10:00:00Z"},
         {"id":"t1","subject":"a","updated_at":"2026-06-22T09:00:00Z"}]
    """.trimIndent()

    private val waitT1UpdatedJson =
        """{"threads":[{"id":"t1","subject":"a","updated_at":"2026-06-22T12:00:00Z"}],"cursor":"2026-06-22T12:00:00Z","timed_out":false}"""

    @Test fun poll_merges_one_changed_thread_keeps_others_and_resorts_to_top() = runTest {
        val vm = MessagesViewModel(repoWith { req ->
            if (req.url.parameters["wait"] == "1") respond(waitT1UpdatedJson, HttpStatusCode.OK, jsonHdr)
            else respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
        }, flowOf(listOf(WorkspaceConnection("1", "http://h:47100", null, "ws"))), backgroundScope)
        vm.refresh()?.join()
        val before = (vm.state.value as MessagesUiState.Content).filteredThreads
        assertEquals(listOf("t3", "t2", "t1"), before.map { it.thread.id })
        assertEquals(listOf("1", "1", "1"), before.map { it.connectionId })

        val next = vm.pollOnce(WorkspaceConnection("1", "http://h:47100", null, "ws"), "2026-06-22T10:00:00Z")

        val after = (vm.state.value as MessagesUiState.Content).filteredThreads
        assertEquals(3, after.size)                                          // MUST NOT collapse to 1
        assertEquals(listOf("t1", "t3", "t2"), after.map { it.thread.id })   // t1 updated + resorted to top
        assertEquals("2026-06-22T12:00:00Z", next)                           // cursor advanced
    }

    @Test fun live_polling_adopts_a_retired_section_id_and_publishes_the_canonical_identity() = runTest {
        val retired = WorkspaceConnection("retired", "http://old:47100", null, "ws")
        val canonical = WorkspaceConnection(
            "canonical",
            "http://new:47100",
            null,
            "ws",
            legacyConnectionIds = listOf(retired.id),
        )
        val connections = MutableStateFlow(listOf(retired))
        var canonicalWaitCalls = 0
        var polledHost: String? = null
        var retiredPollCancelled = false
        val vm = MessagesViewModel(repoWith { req ->
            when {
                req.url.parameters["wait"] == "1" && req.url.host == "old" -> {
                    try {
                        awaitCancellation()
                    } finally {
                        retiredPollCancelled = true
                    }
                }
                req.url.parameters["wait"] == "1" -> {
                    canonicalWaitCalls += 1
                    if (canonicalWaitCalls > 1) awaitCancellation()
                    polledHost = req.url.host
                    respond(waitT1UpdatedJson, HttpStatusCode.OK, jsonHdr)
                }
                req.url.encodedPath.endsWith("/threads") ->
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)
        vm.refresh()?.join()

        vm.selectWorkspace(retired.id)
        vm.startLivePolling()
        runCurrent()
        connections.value = listOf(canonical)
        vm.selectWorkspace(retired.id)
        val content = vm.state.first { candidate ->
            candidate is MessagesUiState.Content &&
                candidate.filteredThreads.any {
                    it.connectionId == canonical.id &&
                        it.thread.updatedAt == "2026-06-22T12:00:00Z"
                }
        } as MessagesUiState.Content
        assertEquals("new", polledHost)
        assertTrue(retiredPollCancelled)
        assertEquals(canonical.id, content.selectedConnectionId)
        assertEquals(listOf(canonical.id), content.sections.map { it.connectionId }.distinct())
        assertEquals(listOf(canonical.id), content.filteredThreads.map { it.connectionId }.distinct())
        assertEquals("t1", content.filteredThreads.first().thread.id)
    }

    @Test fun live_polling_started_before_refresh_tracks_adoption_during_the_load() = runTest {
        val retired = WorkspaceConnection("retired", "http://old:47100", null, "ws")
        val canonical = WorkspaceConnection(
            "canonical",
            "http://new:47100",
            null,
            "ws",
            legacyConnectionIds = listOf(retired.id),
        )
        val connections = MutableStateFlow(listOf(retired))
        val initialRequestStarted = CompletableDeferred<Unit>()
        val releaseInitialRequest = CompletableDeferred<Unit>()
        val canonicalPollStarted = CompletableDeferred<Unit>()
        val vm = MessagesViewModel(repoWith { req ->
            when {
                req.url.parameters["wait"] == "1" -> {
                    if (req.url.host == "new") canonicalPollStarted.complete(Unit)
                    awaitCancellation()
                }
                req.url.encodedPath.endsWith("/threads") -> {
                    initialRequestStarted.complete(Unit)
                    releaseInitialRequest.await()
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)

        val refresh = vm.refresh()
        vm.startLivePolling()
        initialRequestStarted.await()
        assertEquals(MessagesUiState.Loading, vm.state.value)
        connections.value = listOf(canonical)
        runCurrent()
        releaseInitialRequest.complete(Unit)
        refresh?.join()
        canonicalPollStarted.await()

        val content = vm.state.value as MessagesUiState.Content
        assertEquals(listOf(canonical.id), content.sections.map { it.connectionId })
        assertEquals(listOf(canonical.id), content.filteredThreads.map { it.connectionId }.distinct())
    }

    @Test fun canonical_alias_convergence_keeps_one_owner_without_merging_owner_state() = runTest {
        val retiredA = WorkspaceConnection("retired-a", "http://old-a:47100", null, "ws")
        val retiredB = WorkspaceConnection("retired-b", "http://old-b:47100", null, "ws")
        val canonical = WorkspaceConnection(
            "canonical",
            "http://new:47100",
            null,
            "ws",
            legacyConnectionIds = listOf(retiredA.id, retiredB.id),
        )
        val connections = MutableStateFlow(listOf(retiredA, retiredB))
        val vm = MessagesViewModel(repoWith { req ->
            when {
                req.url.parameters["wait"] == "1" -> awaitCancellation()
                req.url.encodedPath.endsWith("/items") && req.url.host == "old-a" ->
                    respond(
                        """[{"id":"item-1","kind":"feature","title":"new item","phase":"inbox","updated_at":"2026-06-22T10:00:00Z"},
                            {"id":"item-new-only","kind":"feature","title":"new only","phase":"inbox","updated_at":"2026-06-22T12:00:00Z"}]""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                req.url.encodedPath.endsWith("/items") ->
                    respond(
                        """[{"id":"item-1","kind":"feature","title":"old item","phase":"inbox","updated_at":"2026-06-22T09:00:00Z"},
                            {"id":"item-old-only","kind":"feature","title":"old only","phase":"inbox","updated_at":"2026-06-22T11:00:00Z"}]""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                req.url.host == "old-a" ->
                    respond(
                        """[{"id":"t1","subject":"new thread","updated_at":"2026-06-22T10:00:00Z"},
                            {"id":"t-new-only","subject":"new only","updated_at":"2026-06-22T12:00:00Z"}]""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                else ->
                    respond(
                        """[{"id":"t1","subject":"old thread","updated_at":"2026-06-22T09:00:00Z"},
                            {"id":"t-old-only","subject":"old only","updated_at":"2026-06-22T11:00:00Z"}]""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
            }
        }, connections, backgroundScope)
        vm.refresh()?.join()
        vm.selectWorkspace(retiredA.id)
        vm.startLivePolling()
        runCurrent()

        connections.value = listOf(canonical)
        runCurrent()

        val content = vm.state.value as MessagesUiState.Content
        val section = content.sections.single()
        assertEquals(canonical.id, section.connectionId)
        assertEquals(canonical.id, content.selectedConnectionId)
        val threads = section.threads.getOrThrow()
        assertEquals(listOf("t1", "t-new-only"), threads.map { it.id })
        assertEquals("new thread", threads.single { it.id == "t1" }.subject)
        assertEquals(setOf("item-1", "item-new-only"), section.items.map { it.id }.toSet())
        assertEquals("new item", section.items.single { it.id == "item-1" }.title)
    }

    private val mixedThreadsWsAJson = """
        [{"id":"a1","subject":"x","updated_at":"2026-06-22T09:00:00Z","unseen":true,"needs_you":false},
         {"id":"a2","subject":"y","updated_at":"2026-06-22T10:00:00Z","unseen":false,"needs_you":true},
         {"id":"a3","subject":"z","updated_at":"2026-06-22T11:00:00Z","unseen":false,"needs_you":false}]
    """.trimIndent()

    private val threadsWsBJson = """
        [{"id":"b1","subject":"p","updated_at":"2026-06-22T12:00:00Z","unseen":true,"needs_you":true}]
    """.trimIndent()

    private fun twoWorkspaceHandler(): MockRequestHandler = { req ->
        when (req.url.host) {
            "a" -> respond(mixedThreadsWsAJson, HttpStatusCode.OK, jsonHdr)
            "b" -> respond(threadsWsBJson, HttpStatusCode.OK, jsonHdr)
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    @Test fun state_filter_all_shows_every_thread() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), flowOf(listOf(connA, connB)), backgroundScope)
        vm.refresh()?.join()
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(4, content.filteredThreads.size)
        assertEquals(ThreadStateFilter.ALL, content.stateFilter)
    }

    @Test fun state_filter_unread_shows_only_unseen_threads_across_workspaces() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), flowOf(listOf(connA, connB)), backgroundScope)
        vm.refresh()?.join()
        vm.selectStateFilter(ThreadStateFilter.UNREAD)
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(setOf("a1", "b1"), content.filteredThreads.map { it.thread.id }.toSet())
    }

    @Test fun state_filter_needs_you_shows_only_needs_you_threads() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), flowOf(listOf(connA, connB)), backgroundScope)
        vm.refresh()?.join()
        vm.selectStateFilter(ThreadStateFilter.NEEDS_YOU)
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(setOf("a2", "b1"), content.filteredThreads.map { it.thread.id }.toSet())
    }

    @Test fun state_filter_composes_with_workspace_selection() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), flowOf(listOf(connA, connB)), backgroundScope)
        vm.refresh()?.join()
        vm.selectWorkspace("A")
        vm.selectStateFilter(ThreadStateFilter.UNREAD)
        val onlyAUnread = vm.state.value as MessagesUiState.Content
        assertEquals(listOf("a1"), onlyAUnread.filteredThreads.map { it.thread.id })

        vm.selectStateFilter(ThreadStateFilter.NEEDS_YOU)
        val onlyANeedsYou = vm.state.value as MessagesUiState.Content
        assertEquals(listOf("a2"), onlyANeedsYou.filteredThreads.map { it.thread.id })

        vm.selectWorkspace(null)
        vm.selectStateFilter(ThreadStateFilter.ALL)
        val all = vm.state.value as MessagesUiState.Content
        assertEquals(4, all.filteredThreads.size)
    }

    @Test fun filteredThreads_carries_the_owning_connectionId_per_thread() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), flowOf(listOf(connA, connB)), backgroundScope)
        vm.refresh()?.join()
        val content = vm.state.value as MessagesUiState.Content
        val byThreadId = content.filteredThreads.associate { it.thread.id to it.connectionId }
        assertEquals("A", byThreadId["a1"])
        assertEquals("A", byThreadId["a2"])
        assertEquals("A", byThreadId["a3"])
        assertEquals("B", byThreadId["b1"])
    }

    @Test fun unread_count_reflects_unseen_threads_total_and_per_workspace() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), flowOf(listOf(connA, connB)), backgroundScope)
        vm.refresh()?.join()
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(2, content.unreadCount)
        assertEquals(1, content.unreadCountsByWorkspace["A"])
        assertEquals(1, content.unreadCountsByWorkspace["B"])
    }

    @Test fun topThreads_returns_most_recent_n_newest_first_optionally_scoped_to_a_workspace() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), flowOf(listOf(connA, connB)), backgroundScope)
        vm.refresh()?.join()
        assertEquals(listOf("b1", "a3"), vm.topThreads(2).map { it.id })
        assertEquals(listOf("a3"), vm.topThreads(1, "A").map { it.id })
    }

    @Test fun unreadCountFor_returns_total_for_all_and_per_workspace_count_when_scoped() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), flowOf(listOf(connA, connB)), backgroundScope)
        vm.refresh()?.join()
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(2, content.unreadCountFor(null))
        assertEquals(1, content.unreadCountFor("A"))
        assertEquals(1, content.unreadCountFor("B"))
        assertEquals(0, content.unreadCountFor("nope"))
    }

    // --- pure merge helper -----------------------------------------------------------------

    private fun t(id: String, updatedAt: String, unseen: Boolean = false, needsYou: Boolean = false) =
        ThreadSummary(id = id, subject = id, updatedAt = updatedAt, unseen = unseen, needsYou = needsYou)

    @Test fun mergeThreadsById_updates_existing_thread_keeps_others_and_resorts() {
        val existing = listOf(
            t("t3", "2026-06-22T11:00:00Z"),
            t("t2", "2026-06-22T10:00:00Z"),
            t("t1", "2026-06-22T09:00:00Z"),
        )
        val changed = listOf(t("t1", "2026-06-22T12:00:00Z"))
        val merged = mergeThreadsById(existing, changed)
        assertEquals(3, merged.size)
        assertEquals(listOf("t1", "t3", "t2"), merged.map { it.id })
    }

    @Test fun mergeThreadsById_inserts_a_thread_not_previously_present() {
        val existing = listOf(t("t1", "2026-06-22T09:00:00Z"))
        val changed = listOf(t("t2", "2026-06-22T10:00:00Z"))
        val merged = mergeThreadsById(existing, changed)
        assertEquals(2, merged.size)
        assertEquals(listOf("t2", "t1"), merged.map { it.id })
    }

    @Test fun same_id_handoff_cancellation_recreates_owner_on_next_connection_emission() = runTest {
        val original = WorkspaceConnection("same", "http://old:47100", null, "ws")
        val firstReplacement = original.copy(baseUrl = "http://new:47100")
        val finalReplacement = firstReplacement.copy(token = "replacement-token")
        val connections = MutableStateFlow(listOf(original))
        val oldRequestStarted = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        val vm = MessagesViewModel(repoWith { request ->
            when {
                request.url.host == "old" && request.url.encodedPath.endsWith("/threads") -> {
                    oldRequestStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { releaseOldRequest.await() }
                    }
                }
                request.url.encodedPath.endsWith("/threads") ->
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)

        oldRequestStarted.await()
        connections.value = listOf(firstReplacement)
        runCurrent()
        connections.value = listOf(finalReplacement)
        releaseOldRequest.complete(Unit)
        runCurrent()

        assertEquals(finalReplacement, vm.ownerSnapshot(finalReplacement.id)?.connection)
    }

    @Test fun cancelled_legacy_handoff_never_launches_an_obsolete_load_before_recreating_the_canonical_owner() = runTest {
        val original = WorkspaceConnection("retired", "http://old:47100", null, "ws", state = "ready")
        val replacement = WorkspaceConnection(
            "canonical", "http://new:47100", null, "ws", state = "reconnecting",
            legacyConnectionIds = listOf(original.id),
        )
        val nextReady = replacement.copy(state = "ready")
        val oldRefreshStarted = CompletableDeferred<Unit>()
        val oldRefreshCancelled = CompletableDeferred<Unit>()
        val releaseOldRefresh = CompletableDeferred<Unit>()
        val releaseHandoffStartMutex = CompletableDeferred<Unit>()
        val handoffStartMutexHeld = CompletableDeferred<Unit>()
        val releaseHandoffCompletionMutex = CompletableDeferred<Unit>()
        val handoffCompletionMutexHeld = CompletableDeferred<Unit>()
        val releaseResumeMutex = CompletableDeferred<Unit>()
        val resumeMutexHeld = CompletableDeferred<Unit>()
        var oldLoads = 0
        var newLoads = 0
        val vm = MessagesViewModel(repoWith { request ->
            when {
                request.url.host == "old" && request.url.encodedPath.endsWith("/threads") -> {
                    oldLoads += 1
                    if (oldLoads == 2) {
                        oldRefreshStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            oldRefreshCancelled.complete(Unit)
                            withContext(NonCancellable) { releaseOldRefresh.await() }
                        }
                    }
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                }
                request.url.host == "new" && request.url.encodedPath.endsWith("/threads") -> {
                    newLoads += 1
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)

        vm.state.first { it is MessagesUiState.Content }
        val owner = checkNotNull(vm.ownerForTest(original.id))
        val refresh = owner.refresh()
        oldRefreshStarted.await()
        val holdHandoffStart = backgroundScope.async {
            owner.holdMutexForTest(handoffStartMutexHeld, releaseHandoffStartMutex)
        }
        handoffStartMutexHeld.await()

        connections.value = listOf(replacement)
        runCurrent()
        releaseHandoffStartMutex.complete(Unit)
        oldRefreshCancelled.await()
        val holdHandoffCompletion = backgroundScope.async {
            owner.holdMutexForTest(handoffCompletionMutexHeld, releaseHandoffCompletionMutex)
        }
        handoffCompletionMutexHeld.await()
        releaseOldRefresh.complete(Unit)
        runCurrent()
        val holdResume = backgroundScope.async {
            owner.holdMutexForTest(resumeMutexHeld, releaseResumeMutex)
        }
        releaseHandoffCompletionMutex.complete(Unit)
        resumeMutexHeld.await()
        connections.value = listOf(nextReady)
        runCurrent()

        withTimeout(1_000) {
            while (vm.ownerSnapshot(original.id) != null) delay(1)
        }
        assertEquals(null, vm.ownerSnapshot(original.id))

        releaseResumeMutex.complete(Unit)
        holdHandoffStart.await()
        holdHandoffCompletion.await()
        holdResume.await()
        refresh.join()
        withTimeout(1_000) {
            var replacementOwner = vm.ownerForTest(nextReady.id)
            while (replacementOwner == null) {
                delay(1)
                replacementOwner = vm.ownerForTest(nextReady.id)
            }
            replacementOwner.snapshot.first { it.phase == MessageConnectionSnapshot.Phase.READY }
        }

        assertEquals(nextReady, vm.ownerSnapshot(nextReady.id)?.connection)
        assertEquals(MessageConnectionSnapshot.Phase.READY, vm.ownerSnapshot(nextReady.id)?.phase)
        assertEquals(1, newLoads)
    }

    @Test fun refresh_captured_before_newer_connections_never_restores_or_polls_the_old_snapshot() = runTest {
        val connectionA = WorkspaceConnection("A", "http://a:47100", null, "a")
        val connectionB = WorkspaceConnection("B", "http://b:47100", null, "b")
        val connections = MutableStateFlow(listOf(connectionA))
        val aRequestStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val vm = MessagesViewModel(repoWith { request ->
            when {
                request.url.host == "a" && request.url.encodedPath.endsWith("/threads") -> {
                    aRequestStarted.complete(Unit)
                    releaseA.await()
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                }
                request.url.host == "b" && request.url.encodedPath.endsWith("/threads") ->
                    respond(waitT1UpdatedJson, HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)

        val refresh = vm.refresh()
        aRequestStarted.await()
        connections.value = listOf(connectionB)
        releaseA.complete(Unit)
        refresh.join()
        val content = vm.state.first { candidate ->
            candidate is MessagesUiState.Content &&
                candidate.sections.map { it.connectionId } == listOf(connectionB.id)
        } as MessagesUiState.Content
        assertEquals(listOf(connectionB.id), content.sections.map { it.connectionId })
    }

    @Test fun newer_connection_revision_fences_a_captured_refresh_before_old_state_can_render() = runTest {
        val a = WorkspaceConnection("A", "http://a:47100")
        val b = WorkspaceConnection("B", "http://b:47100")
        val connections = MutableStateFlow(listOf(a))
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val vm = MessagesViewModel(repoWith { request ->
            when (request.url.host) {
                "a" -> {
                    aStarted.complete(Unit)
                    releaseA.await()
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                }
                else -> respond(waitT1UpdatedJson, HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)

        val refresh = vm.refresh()
        aStarted.await()
        connections.value = listOf(b)
        runCurrent()
        releaseA.complete(Unit)
        refresh.join()
        val content = vm.state.first { it is MessagesUiState.Content &&
            (it as MessagesUiState.Content).sections.map { section -> section.connectionId } == listOf("B") } as MessagesUiState.Content
        assertEquals(listOf("B"), content.sections.map { it.connectionId })
    }

    @Test fun refresh_after_a_newer_revision_begins_never_loads_the_snapshot_pending_before_its_mutex() = runTest {
        val original = WorkspaceConnection("same", "http://old:47100", null, "old")
        val replacement = original.copy(baseUrl = "http://replacement:47100", workspaceName = "replacement")
        val newest = WorkspaceConnection("newest", "http://newest:47100", null, "newest")
        val source = MutableStateFlow(listOf(original))
        val refreshSubscriptionEntered = CompletableDeferred<Unit>()
        val releaseRefreshSubscription = CompletableDeferred<Unit>()
        var subscriptions = 0
        val connections = kotlinx.coroutines.flow.flow {
            if (subscriptions++ > 0) {
                refreshSubscriptionEntered.complete(Unit)
                releaseRefreshSubscription.await()
            }
            emitAll(source)
        }
        val oldPollStarted = CompletableDeferred<Unit>()
        val oldPollCancelled = CompletableDeferred<Unit>()
        val releaseOldPoll = CompletableDeferred<Unit>()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        var replacementLoads = 0
        val vm = MessagesViewModel(repoWith { request ->
            when {
                request.url.host == "old" && request.url.parameters["wait"] == "1" -> {
                    oldPollStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        oldPollCancelled.complete(Unit)
                        withContext(NonCancellable) { releaseOldPoll.await() }
                    }
                }
                request.url.host == "replacement" && request.url.encodedPath.endsWith("/threads") -> {
                    replacementLoads += 1
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                }
                request.url.host == "newest" && request.url.encodedPath.endsWith("/threads") ->
                    respond(waitT1UpdatedJson, HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)

        vm.state.first { it is MessagesUiState.Content }
        vm.startLivePolling()
        oldPollStarted.await()
        val refresh = vm.refresh()
        refreshSubscriptionEntered.await()

        source.value = listOf(replacement)
        runCurrent()
        oldPollCancelled.await()
        val holdReconcileMutex = backgroundScope.async {
            vm.holdReconcileMutexForTest(holderEntered, releaseHolder)
        }
        runCurrent()
        holderEntered.await()
        source.value = listOf(newest)
        runCurrent()
        releaseRefreshSubscription.complete(Unit)
        runCurrent()
        releaseOldPoll.complete(Unit)
        runCurrent()
        releaseHolder.complete(Unit)
        holdReconcileMutex.await()
        refresh.join()

        val content = vm.state.first { candidate ->
            candidate is MessagesUiState.Content &&
                candidate.sections.map { it.connectionId } == listOf(newest.id)
        } as MessagesUiState.Content
        assertEquals(listOf(newest.id), content.sections.map { it.connectionId })
        assertEquals(0, replacementLoads)
    }
}
