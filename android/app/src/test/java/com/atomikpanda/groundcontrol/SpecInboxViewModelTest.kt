package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecGroup
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.InboxAction
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.specs.InboxUiState
import com.atomikpanda.groundcontrol.ui.inbox.InboxTab
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.cancellation.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpecInboxViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun repo() = SpecRepository(SpecApi(HttpClient(MockEngine {
        respond(
            """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]},
                {"id":"b","title":"B","status":"needs_review","task_slug":null,"affected_repos":[]}]""",
            HttpStatusCode.OK, jsonHdr)
    }) { install(ContentNegotiation) { json(buildJson()) } }))

    @Test fun no_connections_yields_empty_config_state() = runTest {
        val vm = SpecInboxViewModel(repo(), { emptyList() }, this)
        vm.refresh(); advanceUntilIdle()
        assertEquals(InboxUiState.EmptyConfig, vm.state.value)
    }

    @Test fun loads_and_groups_workspace_then_status() = runTest {
        val vm = SpecInboxViewModel(repo(), {
            listOf(WorkspaceConnection("1", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        val content = vm.state.value as InboxUiState.Content
        assertEquals(1, content.sections.size)
        val sec = content.sections[0]
        assertEquals("ws-a", sec.workspaceName)
        // ordered groups: NEEDS_REVIEW before READY_TO_DISPATCH
        assertEquals(
            listOf(SpecGroup.NEEDS_REVIEW, SpecGroup.READY_TO_DISPATCH),
            sec.groups.getOrThrow().map { it.group },
        )
        assertEquals(listOf("b"), sec.groups.getOrThrow()[0].specs.map { it.id })
    }

    @Test fun section_carries_connection_id_for_navigation() = runTest {
        val vm = SpecInboxViewModel(repo(), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        val content = vm.state.value as InboxUiState.Content
        assertEquals("conn-7", content.sections[0].connectionId)
    }

    private fun repoWithArchiveEndpoint(archiveFails: Boolean) = SpecRepository(SpecApi(HttpClient(MockEngine { req ->
        if (req.url.encodedPath.endsWith("/archive")) {
            if (archiveFails) respond("boom", HttpStatusCode.InternalServerError)
            else respond("""{"id":"b","title":"B","status":"needs_review","inbox_state":"archived"}""", HttpStatusCode.OK, jsonHdr)
        } else {
            respond(
                """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]},
                    {"id":"b","title":"B","status":"needs_review","task_slug":null,"affected_repos":[]}]""",
                HttpStatusCode.OK, jsonHdr)
        }
    }) { mshipDefaults() }))

    /** /archive throws CancellationException directly (standing in for the scope being
     *  cancelled mid-request) rather than failing normally — mirrors FarmViewModelTest's
     *  vmWithCancellingUnattendedEndpoint. */
    private fun repoWithCancellingArchiveEndpoint() = SpecRepository(SpecApi(HttpClient(MockEngine { req ->
        if (req.url.encodedPath.endsWith("/archive")) {
            throw CancellationException("scope cancelled")
        } else {
            respond(
                """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]},
                    {"id":"b","title":"B","status":"needs_review","task_slug":null,"affected_repos":[]}]""",
                HttpStatusCode.OK, jsonHdr)
        }
    }) { mshipDefaults() }))

    private fun specIds(vm: SpecInboxViewModel) =
        (vm.state.value as InboxUiState.Content).sections[0].groups.getOrThrow().flatMap { it.specs.map { s -> s.id } }

    @Test fun archive_optimistically_removes_spec_from_inbox() = runTest {
        val vm = SpecInboxViewModel(repoWithArchiveEndpoint(archiveFails = false), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        assertEquals(listOf("b", "a"), specIds(vm))   // needs_review(b) before ready_to_dispatch(a)
        vm.mutateInbox("conn-7", "b", InboxAction.ARCHIVE).join()
        assertEquals(listOf("a"), specIds(vm))
    }

    @Test fun archive_resolves_a_retired_section_handle_to_the_canonical_connection() = runTest {
        val retired = WorkspaceConnection("retired", "http://old:47100", null, "ws-a")
        val canonical = WorkspaceConnection(
            "canonical",
            "http://new:47100",
            null,
            "ws-a",
            legacyConnectionIds = listOf(retired.id),
        )
        var connections = listOf(retired)
        var archiveHost: String? = null
        val archiveRepo = SpecRepository(SpecApi(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/archive")) {
                archiveHost = req.url.host
                respond("""{"id":"b","title":"B","status":"needs_review","inbox_state":"archived"}""", HttpStatusCode.OK, jsonHdr)
            } else {
                respond(
                    """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]},
                        {"id":"b","title":"B","status":"needs_review","task_slug":null,"affected_repos":[]}]""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            }
        }) { mshipDefaults() }))
        val vm = SpecInboxViewModel(archiveRepo, { connections }, this)
        vm.refresh()?.join()

        connections = listOf(canonical)
        vm.mutateInbox(retired.id, "b", InboxAction.ARCHIVE).join()

        assertEquals("new", archiveHost)
        assertEquals(listOf("a"), specIds(vm))
        assertEquals(
            canonical.id,
            (vm.state.value as InboxUiState.Content).sections.single().connectionId,
        )
    }

    @Test fun archive_reverts_inbox_on_failure() = runTest {
        val vm = SpecInboxViewModel(repoWithArchiveEndpoint(archiveFails = true), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        vm.mutateInbox("conn-7", "b", InboxAction.ARCHIVE).join()
        assertEquals(listOf("b", "a"), specIds(vm))
    }

    // Mirrors FarmViewModelTest's cancellation-propagation coverage: runCatching swallows
    // CancellationException, so without the explicit rethrow a scope cancellation mid-request
    // would fall into the failure branch (rolling back) instead of propagating.
    @Test fun archive_propagates_cancellation_instead_of_rolling_back() = runTest {
        val vm = SpecInboxViewModel(repoWithCancellingArchiveEndpoint(), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        val job = vm.mutateInbox("conn-7", "b", InboxAction.ARCHIVE)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("a"), specIds(vm))   // optimistic removal stands; no rollback ran
    }

    /** Archiving "b" blocks in flight until the test releases it; archiving "a" always succeeds
     *  immediately. Lets a test force a concurrent change to land while "b"'s request is still
     *  outstanding. */
    private fun repoWithGatedArchiveEndpoint(bStarted: CompletableDeferred<Unit>, releaseB: CompletableDeferred<Unit>) =
        SpecRepository(SpecApi(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/specs/b/inbox/archive")) {
                bStarted.complete(Unit)
                releaseB.await()
                respond("boom", HttpStatusCode.InternalServerError)
            } else if (req.url.encodedPath.endsWith("/inbox/archive")) {
                respond("""{"id":"a","title":"A","status":"approved","inbox_state":"archived"}""", HttpStatusCode.OK, jsonHdr)
            } else {
                respond(
                    """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]},
                        {"id":"b","title":"B","status":"needs_review","task_slug":null,"affected_repos":[]}]""",
                    HttpStatusCode.OK, jsonHdr)
            }
        }) { mshipDefaults() }))

    // Greptile finding on PR #37: a failed archive used to restore the whole pre-archive
    // snapshot, resurrecting any spec removed by a *different*, concurrently-succeeding archive
    // (or a refresh) that landed while the failed request was still in flight. Force that
    // ordering deterministically: start archiving "b" (blocks), archive "a" while "b" is still in
    // flight (succeeds immediately), then let "b" fail. Only "b" should come back.
    @Test fun archive_failure_reinserts_only_that_spec_not_the_whole_snapshot() = runTest {
        val bStarted = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val vm = SpecInboxViewModel(repoWithGatedArchiveEndpoint(bStarted, releaseB), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        assertEquals(listOf("b", "a"), specIds(vm))

        val archiveBJob = vm.mutateInbox("conn-7", "b", InboxAction.ARCHIVE)
        bStarted.await()   // "b" removed optimistically; its archive request is now in flight

        vm.mutateInbox("conn-7", "a", InboxAction.ARCHIVE).join()   // concurrently archives (and confirms) "a"
        assertEquals(emptyList<String>(), specIds(vm))

        releaseB.complete(Unit)   // now let "b"'s archive fail
        archiveBJob.join()

        // "b" comes back (its archive failed); "a" stays gone (its archive genuinely succeeded).
        assertEquals(listOf("b"), specIds(vm))
    }

    @Test fun tab_search_and_inbox_actions_use_durable_server_filters() = runTest {
        val inboxRequests = mutableListOf<Pair<String?, String?>>()
        val actions = mutableListOf<String>()
        val vm = SpecInboxViewModel(SpecRepository(SpecApi(HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/inbox/")) {
                val action = request.url.encodedPath.substringAfterLast("/inbox/")
                actions += action
                val response = when (action) {
                    "archive", "unpin" -> """{"id":"b","title":"needle","status":"needs_review","inbox_state":"archived","pinned":false}"""
                    "pin" -> """{"id":"b","title":"needle","status":"archived","inbox_state":"active","pinned":true}"""
                    else -> """{"id":"b","title":"needle","status":"archived","inbox_state":"active","pinned":false}"""
                }
                respond(response, HttpStatusCode.OK, jsonHdr)
            } else {
                inboxRequests += request.url.parameters["inbox"] to request.url.parameters["q"]
                val archived = request.url.parameters["inbox"] == "archived"
                respond(
                    if (archived) """[{"id":"b","title":"needle","status":"archived","inbox_state":"archived"}]"""
                    else """[{"id":"b","title":"needle","status":"needs_review"}]""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            }
        }) { mshipDefaults() })), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)

        vm.refresh()?.join()
        assertEquals(InboxTab.ACTIVE, (vm.state.value as InboxUiState.Content).tab)
        vm.onSearchQueryChange("needle")?.join()
        vm.mutateInbox("conn-7", "b", InboxAction.ARCHIVE).join()
        assertTrue(specIds(vm).isEmpty())
        vm.selectInboxTab(InboxTab.ARCHIVED)?.join()
        assertEquals(listOf("b"), specIds(vm))   // lifecycle status "archived" remains renderable
        vm.mutateInbox("conn-7", "b", InboxAction.PIN).join()
        assertTrue(specIds(vm).isEmpty())
        vm.selectInboxTab(InboxTab.ACTIVE)?.join()
        vm.mutateInbox("conn-7", "b", InboxAction.UNPIN).join()
        assertTrue(specIds(vm).isEmpty())
        vm.selectInboxTab(InboxTab.ARCHIVED)?.join()
        vm.mutateInbox("conn-7", "b", InboxAction.RESTORE).join()
        assertTrue(specIds(vm).isEmpty())

        assertTrue(inboxRequests.contains("active" to "needle"))
        assertTrue(inboxRequests.contains("archived" to "needle"))
        assertEquals(listOf("archive", "pin", "unpin", "restore"), actions)
    }

    @Test fun stale_refresh_cannot_overwrite_authoritative_inbox_mutation() = runTest {
        val heldGetStarted = CompletableDeferred<Unit>()
        val releaseGet = CompletableDeferred<Unit>()
        val heldPostStarted = CompletableDeferred<Unit>()
        val releasePost = CompletableDeferred<Unit>()
        var holdNextGet = false
        val vm = SpecInboxViewModel(SpecRepository(SpecApi(HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/inbox/")) {
                heldPostStarted.complete(Unit)
                releasePost.await()
                respond(
                    """{"id":"b","title":"B","status":"needs_review","inbox_state":"archived"}""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            } else {
                val body = when {
                    holdNextGet -> {
                        holdNextGet = false
                        heldGetStarted.complete(Unit)
                        releaseGet.await()
                        """[{"id":"b","title":"B","status":"needs_review"}]"""
                    }
                    request.url.parameters["q"] == "b" ->
                        """[{"id":"b","title":"B","status":"needs_review"}]"""
                    else -> """[{"id":"fresh","title":"Fresh","status":"needs_review"}]"""
                }
                respond(body, HttpStatusCode.OK, jsonHdr)
            }
        }) { mshipDefaults() })), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)

        vm.refresh()?.join()
        assertEquals(listOf("fresh"), specIds(vm))
        // Seed the mutation target, then hold a stale refresh that began before the POST.
        vm.onSearchQueryChange("b")?.join()
        holdNextGet = true
        val staleRefresh = vm.refresh()!!
        heldGetStarted.await()
        val mutation = vm.mutateInbox("conn-7", "b", InboxAction.ARCHIVE)
        heldPostStarted.await()
        releasePost.complete(Unit)
        mutation.join()
        releaseGet.complete(Unit)
        staleRefresh.join()
        assertTrue(specIds(vm).isEmpty())

        vm.onSearchQueryChange("")?.join()
        assertEquals(listOf("fresh"), specIds(vm))
    }

}
