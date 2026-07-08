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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
        val vm = MessagesViewModel(repo(), {
            listOf(WorkspaceConnection("42", "http://h:47100", null, "ws-alpha"))
        }, this)
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
        val vm = MessagesViewModel(repo(), { emptyList() }, this)
        vm.refresh()
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
        }, { listOf(WorkspaceConnection("1", "http://h:47100", null, "ws")) }, this)
        vm.refresh()?.join()
        val before = (vm.state.value as MessagesUiState.Content).filteredThreads
        assertEquals(listOf("t3", "t2", "t1"), before.map { it.id })

        val next = vm.pollOnce(WorkspaceConnection("1", "http://h:47100", null, "ws"), "2026-06-22T10:00:00Z")

        val after = (vm.state.value as MessagesUiState.Content).filteredThreads
        assertEquals(3, after.size)                                   // MUST NOT collapse to 1
        assertEquals(listOf("t1", "t3", "t2"), after.map { it.id })   // t1 updated + resorted to top
        assertEquals("2026-06-22T12:00:00Z", next)                    // cursor advanced
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
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), { listOf(connA, connB) }, this)
        vm.refresh()?.join()
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(4, content.filteredThreads.size)
        assertEquals(ThreadStateFilter.ALL, content.stateFilter)
    }

    @Test fun state_filter_unread_shows_only_unseen_threads_across_workspaces() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), { listOf(connA, connB) }, this)
        vm.refresh()?.join()
        vm.selectStateFilter(ThreadStateFilter.UNREAD)
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(setOf("a1", "b1"), content.filteredThreads.map { it.id }.toSet())
    }

    @Test fun state_filter_needs_you_shows_only_needs_you_threads() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), { listOf(connA, connB) }, this)
        vm.refresh()?.join()
        vm.selectStateFilter(ThreadStateFilter.NEEDS_YOU)
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(setOf("a2", "b1"), content.filteredThreads.map { it.id }.toSet())
    }

    @Test fun state_filter_composes_with_workspace_selection() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), { listOf(connA, connB) }, this)
        vm.refresh()?.join()
        vm.selectWorkspace("A")
        vm.selectStateFilter(ThreadStateFilter.UNREAD)
        val onlyAUnread = vm.state.value as MessagesUiState.Content
        assertEquals(listOf("a1"), onlyAUnread.filteredThreads.map { it.id })

        vm.selectStateFilter(ThreadStateFilter.NEEDS_YOU)
        val onlyANeedsYou = vm.state.value as MessagesUiState.Content
        assertEquals(listOf("a2"), onlyANeedsYou.filteredThreads.map { it.id })

        vm.selectWorkspace(null)
        vm.selectStateFilter(ThreadStateFilter.ALL)
        val all = vm.state.value as MessagesUiState.Content
        assertEquals(4, all.filteredThreads.size)
    }

    @Test fun unread_count_reflects_unseen_threads_total_and_per_workspace() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), { listOf(connA, connB) }, this)
        vm.refresh()?.join()
        val content = vm.state.value as MessagesUiState.Content
        assertEquals(2, content.unreadCount)
        assertEquals(1, content.unreadCountsByWorkspace["A"])
        assertEquals(1, content.unreadCountsByWorkspace["B"])
    }

    @Test fun topThreads_returns_most_recent_n_newest_first_optionally_scoped_to_a_workspace() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), { listOf(connA, connB) }, this)
        vm.refresh()?.join()
        assertEquals(listOf("b1", "a3"), vm.topThreads(2).map { it.id })
        assertEquals(listOf("a3"), vm.topThreads(1, "A").map { it.id })
    }

    @Test fun unreadCountFor_returns_total_for_all_and_per_workspace_count_when_scoped() = runTest {
        val vm = MessagesViewModel(repoWith(twoWorkspaceHandler()), { listOf(connA, connB) }, this)
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
