package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.messages.MessagesUiState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {
    private val mockClients = mutableListOf<HttpClient>()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() {
        mockClients.forEach { it.close() }
        Dispatchers.resetMain()
    }

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

    private fun repoWith(handler: MockRequestHandler): ThreadsRepository {
        val client = HttpClient(MockEngine(handler)) { mshipDefaults() }
        mockClients += client
        return ThreadsRepository(SpecApi(client))
    }

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

    @Test fun live_polling_drops_sections_for_removed_connections() = runTest {
        val connections = MutableStateFlow(listOf(connA, connB))
        val vm = MessagesViewModel(repoWith { req ->
            when {
                req.url.parameters["wait"] == "1" -> awaitCancellation()
                req.url.encodedPath.endsWith("/threads") ->
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)
        vm.refresh()?.join()
        vm.startLivePolling()
        runCurrent()

        connections.value = listOf(connB)
        runCurrent()

        val content = vm.state.value as MessagesUiState.Content
        assertEquals(listOf(connB.id), content.sections.map { it.connectionId })
        assertEquals(listOf(connB.id), content.filteredThreads.map { it.connectionId }.distinct())
    }

    @Test fun live_polling_loads_and_polls_connections_added_after_initial_load() = runTest {
        val connections = MutableStateFlow(listOf(connA))
        val polledHosts = mutableListOf<String>()
        val secondPollStarted = CompletableDeferred<Unit>()
        val vm = MessagesViewModel(repoWith { req ->
            if (req.url.parameters["wait"] == "1") {
                polledHosts += req.url.host
                if (polledHosts.size == 2) secondPollStarted.complete(Unit)
                respond("""{"threads":[],"cursor":"","timed_out":true}""", HttpStatusCode.OK, jsonHdr)
            } else {
                respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)
        runCurrent()
        vm.refresh()?.join()
        vm.startLivePolling()
        runCurrent()

        connections.value = listOf(connA, connB)
        runCurrent()
        runCurrent()
        secondPollStarted.await()

        val content = vm.state.value as MessagesUiState.Content
        assertEquals(listOf(connA.id, connB.id), content.sections.map { it.connectionId })
        assertTrue(polledHosts.containsAll(listOf("a", "b")))
    }

    @Test fun live_polling_renders_empty_config_and_recovers_after_last_connection_is_readded() = runTest {
        val connections = MutableStateFlow(listOf(connA))
        val loadedHosts = mutableListOf<String>()
        val polledHosts = mutableListOf<String>()
        val reloadCompleted = CompletableDeferred<Unit>()
        val secondPollStarted = CompletableDeferred<Unit>()
        val vm = MessagesViewModel(repoWith { req ->
            when {
                req.url.parameters["wait"] == "1" -> {
                    polledHosts += req.url.host
                    if (polledHosts.size == 2) secondPollStarted.complete(Unit)
                    respond("""{"threads":[],"cursor":"","timed_out":true}""", HttpStatusCode.OK, jsonHdr)
                }
                req.url.encodedPath.endsWith("/threads") -> {
                    loadedHosts += req.url.host
                    if (loadedHosts.size == 2) reloadCompleted.complete(Unit)
                    respond("[]", HttpStatusCode.OK, jsonHdr)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)
        runCurrent()
        vm.refresh()?.join()
        vm.startLivePolling()
        runCurrent()

        connections.value = emptyList()
        runCurrent()
        assertEquals(MessagesUiState.EmptyConfig, vm.state.value)

        connections.value = listOf(connA)
        runCurrent()
        runCurrent()
        runCurrent()
        reloadCompleted.await()
        secondPollStarted.await()

        runCurrent()
        runCurrent()
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(listOf(connA.id), content.sections.map { it.connectionId })
        assertEquals(listOf("a", "a"), loadedHosts)
        assertEquals(2, polledHosts.size)
        assertTrue(polledHosts.all { it == "a" })
    }

    @Test fun live_polling_waits_for_the_first_connection_snapshot_before_loading() = runTest {
        val releaseFirstSnapshot = CompletableDeferred<Unit>()
        val loadedHosts = mutableListOf<String>()
        val polledHosts = mutableListOf<String>()
        val pollStarted = CompletableDeferred<Unit>()
        val connections = flow {
            releaseFirstSnapshot.await()
            emit(listOf(connA))
        }
        val vm = MessagesViewModel(repoWith { req ->
            when {
                req.url.parameters["wait"] == "1" -> {
                    polledHosts += req.url.host
                    pollStarted.complete(Unit)
                    awaitCancellation()
                }
                req.url.encodedPath.endsWith("/threads") -> {
                    loadedHosts += req.url.host
                    respond("[]", HttpStatusCode.OK, jsonHdr)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)

        vm.startLivePolling()
        assertEquals(MessagesUiState.Loading, vm.state.value)
        releaseFirstSnapshot.complete(Unit)
        runCurrent()

        assertEquals(MessagesUiState.Loading, vm.state.value)
        assertEquals(emptyList<String>(), loadedHosts)
        assertEquals(emptyList<String>(), polledHosts)

        vm.refresh()?.join()
        pollStarted.await()
        runCurrent()

        val content = vm.state.value as MessagesUiState.Content
        assertEquals(listOf(connA.id), content.sections.map { it.connectionId })
        assertEquals(listOf("a"), loadedHosts)
        assertEquals(listOf("a"), polledHosts)
    }

    @Test fun live_polling_restarts_when_a_stable_connection_id_gets_new_routing_fields() = runTest {
        val original = WorkspaceConnection(
            id = "stable",
            baseUrl = "http://old:47100",
            token = "old-token",
            workspaceName = "ws",
            hostId = "old-host",
            workspaceId = "old-workspace",
        )
        val replacement = original.copy(
            baseUrl = "http://new:47100",
            token = "new-token",
            hostId = "new-host",
            workspaceId = "new-workspace",
        )
        val connections = MutableStateFlow(listOf(original))
        val polledRoutes = mutableListOf<Pair<String, String?>>()
        val oldPollStarted = CompletableDeferred<Unit>()
        val newPollStarted = CompletableDeferred<Unit>()
        var oldPollCancelled = false
        val vm = MessagesViewModel(repoWith { req ->
            when {
                req.url.parameters["wait"] == "1" -> {
                    polledRoutes += req.url.host to req.headers[HttpHeaders.Authorization]
                    if (req.url.host == "old") {
                        oldPollStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            oldPollCancelled = true
                        }
                    } else {
                        newPollStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
                req.url.encodedPath.endsWith("/threads") ->
                    respond(threeThreadsJson, HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }, connections, backgroundScope)
        vm.refresh()?.join()
        vm.startLivePolling()
        runCurrent()

        vm.startLivePolling()
        runCurrent()
        oldPollStarted.await()
        assertEquals(listOf("old" to "Bearer old-token"), polledRoutes)

        connections.value = listOf(replacement)
        runCurrent()

        newPollStarted.await()
        assertTrue(oldPollCancelled)
        assertEquals(
            listOf("old" to "Bearer old-token", "new" to "Bearer new-token"),
            polledRoutes,
        )
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
        val retiredPollStarted = CompletableDeferred<Unit>()
        val retiredPollCancelledSignal = CompletableDeferred<Unit>()
        val canonicalPollStarted = CompletableDeferred<Unit>()
        val vm = MessagesViewModel(repoWith { req ->
            when {
                req.url.parameters["wait"] == "1" && req.url.host == "old" -> {
                    retiredPollStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        retiredPollCancelledSignal.complete(Unit)
                        retiredPollCancelled = true
                    }
                }
                req.url.parameters["wait"] == "1" -> {
                    canonicalWaitCalls += 1
                    if (canonicalWaitCalls > 1) awaitCancellation()
                    polledHost = req.url.host
                    canonicalPollStarted.complete(Unit)
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
        retiredPollStarted.await()
        connections.value = listOf(canonical)
        runCurrent()
        retiredPollCancelledSignal.await()
        canonicalPollStarted.await()
        vm.selectWorkspace(retired.id)

        val content = vm.state.value as MessagesUiState.Content
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

    @Test fun adopted_duplicate_sections_keep_newest_records_and_all_unique_ids() = runTest {
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
        assertEquals(listOf("t-new-only", "t-old-only", "t1"), threads.map { it.id })
        assertEquals("new thread", threads.single { it.id == "t1" }.subject)
        assertEquals(setOf("item-1", "item-new-only", "item-old-only"), section.items.map { it.id }.toSet())
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
}
