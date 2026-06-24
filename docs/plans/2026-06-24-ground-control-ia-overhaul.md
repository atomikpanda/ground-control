# Ground Control IA Overhaul — Slice 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `ground-control-ia-overhaul` (mship spec, dispatched → this task). Slice 1 of 3 (IA + Home attention queue). Slices 2 (capture/C3) and 3 (visual pass) are separate specs.

**Goal:** Replace the 5-tab nav (Specs, Messages, Decisions, Tasks, Settings) with a 3-tab nav (Home, Tasks, Settings) where **Home is one cross-workspace "Needs you" queue** — approval-ready specs + agent questions + blocked tasks, urgency-sorted, workspace-chipped — plus a workspace chip rail and a scoped single-workspace browse.

**Architecture:** Client-side only (no mothership change). A `HomeFeedRepository` fans out over every `WorkspaceConnection`, pulls `/specs` + `/threads` + `/tasks` concurrently, maps each to a unified `NeedsYouItem`, filters to "blocked on you" states, and merges into one urgency-sorted list with per-connection error isolation. `HomeViewModel` exposes the list + a workspace rail; `HomeScreen` renders it and routes taps to the existing `specDetail` / `thread` / `taskDetail` screens. A `WorkspaceScreen` gives the scoped browse.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Navigation-Compose, Ktor client, kotlinx.serialization, kotlinx-coroutines; JUnit4 + kotlinx-coroutines-test + Ktor `MockEngine` for tests (no emulator).

**Urgency rule (spec q1):** `BLOCKER > QUESTION > APPROVAL`; within a tier, newest-first. *Note:* `SpecSummary` carries no timestamp over `/specs`, so APPROVAL items sort by title within their tier (documented simplification; revisit if a spec timestamp is exposed later).

**Rail rule (spec q4):** `"All"` chip pinned first, then connections ordered by needs-you count (desc) then name. *Note:* pure "recent activity" ordering for zero-item workspaces is deferred — no cheap global last-activity signal exists yet; zero-item workspaces sort by name.

---

## Prerequisites

Run once in the worktree before starting (the worktree is at `.worktrees/ground-control-ia-overhaul/ground-control`):

```bash
source ~/toolchains/android-env.sh          # sets ANDROID_HOME (JVM unit tests only; no emulator)
test -f android/local.properties || (cd android && printf "sdk.dir=%s\n" "$ANDROID_HOME" > local.properties)
```

All test commands below assume `~/toolchains/android-env.sh` is sourced in the shell. New main code lives under `android/app/src/main/java/com/atomikpanda/groundcontrol/`; new tests under `android/app/src/test/java/com/atomikpanda/groundcontrol/` (flat package `com.atomikpanda.groundcontrol`, matching existing tests).

## File Structure

| File | Responsibility |
|---|---|
| `ui/home/NeedsYouItem.kt` (create) | Unified item model + `UrgencyTier` + pure DTO→item mapping + comparator. No Android deps. |
| `data/HomeFeedRepository.kt` (create) | Fan out over connections, merge `/specs`+`/threads`+`/tasks` → `HomeFeed(items, errors)` with per-connection isolation. |
| `ui/home/HomeViewModel.kt` (create) | `HomeUiState` (Loading/EmptyConfig/Content), `refresh()`, workspace chip rail, `select()` filter. |
| `ui/home/HomeScreen.kt` (create) | Chip rail + "Needs you" list + empty/error states + tap routing + "Browse workspace". |
| `ui/workspace/WorkspaceViewModel.kt` (create) | Load specs+threads+tasks for ONE connection (scoped browse). |
| `ui/workspace/WorkspaceScreen.kt` (create) | Render scoped browse + scoped "New conversation". |
| `ui/nav/Section.kt` (modify) | 5 tabs → 3 (HOME, TASKS, SETTINGS). |
| `GroundControlApp.kt` (modify) | Start at Home; wire Home + Workspace routes; remove specs/messages/decisions tab routes; keep `specDetail`/`thread`/`taskDetail`/`newThread`; add optional preselect to `newThread`. |
| `ui/messages/NewThreadScreen.kt` (modify) | Optional `initialConnectionId` to scope a new conversation to a workspace. |
| `ui/placeholder/PlaceholderScreen.kt` (delete) | Only used by the retired Decisions tab. |

Retained-but-unused-as-tabs: `ui/specs/SpecInboxScreen.kt` and `ui/messages/MessagesScreen.kt` (and their VMs/tests) are left in place — their grouping logic may inform later slices and keeping them keeps the existing test suite green. Only their *tab wiring* is removed.

---

<!-- mship:task id=1 -->
### Task 1: NeedsYouItem model + pure mapping & sort

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/NeedsYouItem.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/NeedsYouItemTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/atomikpanda/groundcontrol/NeedsYouItemTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.home.NeedsYouItem
import com.atomikpanda.groundcontrol.ui.home.UrgencyTier
import com.atomikpanda.groundcontrol.ui.home.approvalsFrom
import com.atomikpanda.groundcontrol.ui.home.blockersFrom
import com.atomikpanda.groundcontrol.ui.home.questionsFrom
import com.atomikpanda.groundcontrol.ui.home.sortNeedsYou
import org.junit.Assert.assertEquals
import org.junit.Test

class NeedsYouItemTest {
    private val conn = WorkspaceConnection("c1", "http://h:47100", null, "ws-a")

    @Test fun approvals_only_include_needs_review_specs() {
        val specs = listOf(
            SpecSummary("s1", "Alpha", "needs_review"),
            SpecSummary("s2", "Beta", "approved"),
            SpecSummary("s3", "Gamma", "drafting"),
        )
        val items = approvalsFrom(conn, specs)
        assertEquals(listOf("s1"), items.map { (it as NeedsYouItem.Approval).specId })
        assertEquals("ws-a", items[0].workspaceName)
        assertEquals(UrgencyTier.APPROVAL, items[0].tier)
    }

    @Test fun questions_only_include_threads_awaiting_reply() {
        val threads = listOf(
            ThreadSummary("t1", subject = "Q", awaitingReply = true),
            ThreadSummary("t2", subject = "Done", awaitingReply = false),
        )
        val items = questionsFrom(conn, threads)
        assertEquals(listOf("t1"), items.map { (it as NeedsYouItem.Question).threadId })
        assertEquals(UrgencyTier.QUESTION, items[0].tier)
    }

    @Test fun blockers_only_include_blocked_tasks() {
        val tasks = listOf(
            TaskSummary(slug = "k1", phase = "dev", branch = "b", blockedReason = "needs key"),
            TaskSummary(slug = "k2", phase = "dev", branch = "b", blockedReason = null),
        )
        val items = blockersFrom(conn, tasks)
        assertEquals(listOf("k1"), items.map { (it as NeedsYouItem.Blocker).taskSlug })
        assertEquals(UrgencyTier.BLOCKER, items[0].tier)
    }

    @Test fun sort_orders_blocker_then_question_then_approval() {
        val a = NeedsYouItem.Approval("c1", "ws", "s1", "Title")
        val q = NeedsYouItem.Question("c1", "ws", "t1", "Q", "msg", "2026-06-24T10:00:00Z")
        val b = NeedsYouItem.Blocker("c1", "ws", "k1", "r", "2026-06-24T09:00:00Z")
        assertEquals(listOf(b, q, a), sortNeedsYou(listOf(a, q, b)))
    }

    @Test fun sort_is_newest_first_within_a_tier() {
        val older = NeedsYouItem.Question("c1", "ws", "old", "Q", "m", "2026-06-24T08:00:00Z")
        val newer = NeedsYouItem.Question("c1", "ws", "new", "Q", "m", "2026-06-24T12:00:00Z")
        assertEquals(listOf(newer, older), sortNeedsYou(listOf(older, newer)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.NeedsYouItemTest"`
Expected: FAIL — unresolved references (`NeedsYouItem`, `approvalsFrom`, …).

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/NeedsYouItem.kt
package com.atomikpanda.groundcontrol.ui.home

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary

/** Sort tiers for the "Needs you" queue. Lower ordinal = higher urgency. */
enum class UrgencyTier { BLOCKER, QUESTION, APPROVAL }

/** One actionable item in the cross-workspace "Needs you" queue. */
sealed interface NeedsYouItem {
    val connectionId: String
    val workspaceName: String
    val tier: UrgencyTier
    /** Recency key within a tier; newest-first == descending sort. */
    val sortKey: String
    /** Stable, unique key for LazyColumn. */
    val key: String

    data class Approval(
        override val connectionId: String,
        override val workspaceName: String,
        val specId: String,
        val title: String,
    ) : NeedsYouItem {
        override val tier get() = UrgencyTier.APPROVAL
        override val sortKey get() = title.lowercase()   // /specs carries no timestamp
        override val key get() = "approval:$connectionId:$specId"
    }

    data class Question(
        override val connectionId: String,
        override val workspaceName: String,
        val threadId: String,
        val subject: String,
        val lastMessage: String,
        val updatedAt: String,
    ) : NeedsYouItem {
        override val tier get() = UrgencyTier.QUESTION
        override val sortKey get() = updatedAt
        override val key get() = "question:$connectionId:$threadId"
    }

    data class Blocker(
        override val connectionId: String,
        override val workspaceName: String,
        val taskSlug: String,
        val reason: String,
        val createdAt: String,
    ) : NeedsYouItem {
        override val tier get() = UrgencyTier.BLOCKER
        override val sortKey get() = createdAt
        override val key get() = "blocker:$connectionId:$taskSlug"
    }
}

/** Display name for a workspace (blank name → baseUrl). */
fun WorkspaceConnection.displayName(): String = workspaceName.ifBlank { baseUrl }

fun approvalsFrom(conn: WorkspaceConnection, specs: List<SpecSummary>): List<NeedsYouItem> =
    specs.filter { it.status == "needs_review" }
        .map { NeedsYouItem.Approval(conn.id, conn.displayName(), it.id, it.title) }

fun questionsFrom(conn: WorkspaceConnection, threads: List<ThreadSummary>): List<NeedsYouItem> =
    threads.filter { it.awaitingReply }
        .map { NeedsYouItem.Question(conn.id, conn.displayName(), it.id, it.subject, it.lastMessage, it.updatedAt ?: "") }

fun blockersFrom(conn: WorkspaceConnection, tasks: List<TaskSummary>): List<NeedsYouItem> =
    tasks.filter { it.blockedReason != null }
        .map { NeedsYouItem.Blocker(conn.id, conn.displayName(), it.slug, it.blockedReason ?: "", it.createdAt ?: "") }

/** Urgency order: tier asc (blocker first), then newest-first within tier. */
val needsYouComparator: Comparator<NeedsYouItem> =
    compareBy<NeedsYouItem> { it.tier.ordinal }.thenByDescending { it.sortKey }

fun sortNeedsYou(items: List<NeedsYouItem>): List<NeedsYouItem> = items.sortedWith(needsYouComparator)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.NeedsYouItemTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/NeedsYouItem.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/NeedsYouItemTest.kt
git commit -m "feat(home): NeedsYouItem model + DTO mapping + urgency sort"
mship journal "NeedsYouItem model + pure mapping/sort; 5 unit tests passing" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=2 -->
### Task 2: HomeFeedRepository — cross-workspace fan-out with per-connection isolation

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/HomeFeedRepository.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/HomeFeedRepositoryTest.kt`

The repo reuses the established `coroutineScope { async … awaitAll() }` fan-out (same shape as `SpecRepository.listAllSpecs`) and calls `SpecApi` directly. Tests use Ktor `MockEngine` routed by URL path/host, and `mshipDefaults()` for the client (same helper used by `SpecDetailViewModelTest`).

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/atomikpanda/groundcontrol/HomeFeedRepositoryTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.home.NeedsYouItem
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

class HomeFeedRepositoryTest {
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private val specsJson = """[{"id":"s1","title":"Dark mode","status":"needs_review"},
                                {"id":"s2","title":"Done","status":"approved"}]"""
    private val threadsJson = """[{"id":"t1","subject":"Notifs","awaiting_reply":true,"updated_at":"2026-06-24T10:00:00Z"},
                                  {"id":"t2","subject":"Idle","awaiting_reply":false}]"""
    private val tasksJson = """[{"slug":"k1","phase":"dev","branch":"b","blocked_reason":"needs key","created_at":"2026-06-24T09:00:00Z"},
                                {"slug":"k2","phase":"dev","branch":"b"}]"""

    /** Route by path; fail every call to host "bad". */
    private fun api() = SpecApi(HttpClient(MockEngine { req ->
        if (req.url.host == "bad") return@MockEngine respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
        val p = req.url.encodedPath
        when {
            p.endsWith("/specs") -> respond(specsJson, HttpStatusCode.OK, jsonHdr)
            p.endsWith("/threads") -> respond(threadsJson, HttpStatusCode.OK, jsonHdr)
            p.endsWith("/tasks") -> respond(tasksJson, HttpStatusCode.OK, jsonHdr)
            else -> respond("[]", HttpStatusCode.OK, jsonHdr)
        }
    }) { mshipDefaults() })

    @Test fun merges_filtered_items_across_workspaces_sorted_by_urgency() = runTest {
        val repo = HomeFeedRepository(api())
        val feed = repo.load(listOf(WorkspaceConnection("c1", "http://good:47100", null, "ws-a")))
        // 1 approval + 1 question + 1 blocker survive filtering
        assertEquals(3, feed.items.size)
        assertTrue(feed.items[0] is NeedsYouItem.Blocker)   // urgency: blocker first
        assertTrue(feed.items[1] is NeedsYouItem.Question)
        assertTrue(feed.items[2] is NeedsYouItem.Approval)
        assertTrue(feed.errors.isEmpty())
    }

    @Test fun one_failing_workspace_isolates_to_error_others_still_load() = runTest {
        val repo = HomeFeedRepository(api())
        val feed = repo.load(listOf(
            WorkspaceConnection("ok", "http://good:47100", null, "ws-a"),
            WorkspaceConnection("c2", "http://bad:47100", null, "ws-bad"),
        ))
        assertEquals(3, feed.items.size)                    // ok workspace's items present
        assertTrue(feed.items.all { it.connectionId == "ok" })
        assertEquals(listOf("ws-bad"), feed.errors.map { it.workspaceName })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.HomeFeedRepositoryTest"`
Expected: FAIL — `HomeFeedRepository` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/data/HomeFeedRepository.kt
package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.ui.home.NeedsYouItem
import com.atomikpanda.groundcontrol.ui.home.approvalsFrom
import com.atomikpanda.groundcontrol.ui.home.blockersFrom
import com.atomikpanda.groundcontrol.ui.home.displayName
import com.atomikpanda.groundcontrol.ui.home.questionsFrom
import com.atomikpanda.groundcontrol.ui.home.sortNeedsYou
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A workspace whose fetch failed (one or more sources errored). */
data class WorkspaceError(val connectionId: String, val workspaceName: String)

/** The merged cross-workspace "Needs you" feed. */
data class HomeFeed(
    val items: List<NeedsYouItem>,
    val errors: List<WorkspaceError>,
)

/**
 * Fans out over every connected workspace, pulling specs + threads + tasks
 * concurrently, mapping each to "blocked on you" items, and merging into one
 * urgency-sorted list. A workspace whose fetch fails contributes a
 * [WorkspaceError] instead of sinking the whole feed.
 */
class HomeFeedRepository(private val api: SpecApi) {
    suspend fun load(connections: List<WorkspaceConnection>): HomeFeed = coroutineScope {
        val perConn = connections.map { conn -> async { loadOne(conn) } }.awaitAll()
        HomeFeed(
            items = sortNeedsYou(perConn.flatMap { it.items }),
            errors = perConn.mapNotNull { it.error },
        )
    }

    private data class ConnResult(val items: List<NeedsYouItem>, val error: WorkspaceError?)

    private suspend fun loadOne(conn: WorkspaceConnection): ConnResult = coroutineScope {
        val specs = async { runCatching { api.listSpecs(conn) } }
        val threads = async { runCatching { api.listThreads(conn) } }
        val tasks = async { runCatching { api.listTasks(conn) } }
        val s = specs.await(); val t = threads.await(); val k = tasks.await()
        val items = buildList {
            s.getOrNull()?.let { addAll(approvalsFrom(conn, it)) }
            t.getOrNull()?.let { addAll(questionsFrom(conn, it)) }
            k.getOrNull()?.let { addAll(blockersFrom(conn, it)) }
        }
        val failed = s.isFailure || t.isFailure || k.isFailure
        ConnResult(items, if (failed) WorkspaceError(conn.id, conn.displayName()) else null)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.HomeFeedRepositoryTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/data/HomeFeedRepository.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/HomeFeedRepositoryTest.kt
git commit -m "feat(home): HomeFeedRepository fan-out + per-connection error isolation"
mship journal "HomeFeedRepository merges specs/threads/tasks across workspaces; per-connection isolation; 2 tests" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=3 -->
### Task 3: HomeViewModel — state, refresh, workspace rail, scope filter

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeViewModel.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/HomeViewModelTest.kt`

Same VM shape as `SpecInboxViewModel`: `(repo, connectionsProvider, testScope?)`, sealed `HomeUiState`, `refresh(): Job?`.

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/atomikpanda/groundcontrol/HomeViewModelTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.home.HomeUiState
import com.atomikpanda.groundcontrol.ui.home.HomeViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun repo() = HomeFeedRepository(SpecApi(HttpClient(MockEngine { req ->
        val p = req.url.encodedPath
        when {
            // ws-a (host a) has 1 needs_review spec; ws-b (host b) has none
            p.endsWith("/specs") -> respond(
                if (req.url.host == "a") """[{"id":"s1","title":"Dark","status":"needs_review"}]""" else "[]",
                HttpStatusCode.OK, jsonHdr)
            else -> respond("[]", HttpStatusCode.OK, jsonHdr)
        }
    }) { mshipDefaults() }))

    private val conns = listOf(
        WorkspaceConnection("a", "http://a:47100", null, "ws-a"),
        WorkspaceConnection("b", "http://b:47100", null, "ws-b"),
    )

    @Test fun no_connections_yields_empty_config() = runTest {
        val vm = HomeViewModel(repo(), { emptyList() }, this)
        vm.refresh(); advanceUntilIdle()
        assertEquals(HomeUiState.EmptyConfig, vm.state.value)
    }

    @Test fun rail_pins_all_first_then_workspaces_by_count_desc() = runTest {
        val vm = HomeViewModel(repo(), { conns }, this)
        vm.refresh()?.join()
        val c = vm.state.value as HomeUiState.Content
        assertEquals(listOf(null, "a", "b"), c.rail.map { it.connectionId })  // All, then ws-a (1 item) before ws-b (0)
        assertEquals(1, c.rail.first().count)                                  // "All" count == total items
        assertEquals(1, c.items.size)
    }

    @Test fun select_filters_items_to_one_workspace() = runTest {
        val vm = HomeViewModel(repo(), { conns }, this)
        vm.refresh()?.join()
        vm.select("b")
        val c = vm.state.value as HomeUiState.Content
        assertEquals("b", c.selectedConnectionId)
        assertEquals(0, c.items.size)                                         // ws-b has nothing
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.HomeViewModelTest"`
Expected: FAIL — `HomeViewModel` / `HomeUiState` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeViewModel.kt
package com.atomikpanda.groundcontrol.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.HomeFeed
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.WorkspaceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A chip in the workspace rail. connectionId == null is the pinned "All" chip. */
data class WorkspaceChip(val connectionId: String?, val label: String, val count: Int)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object EmptyConfig : HomeUiState
    data class Content(
        val rail: List<WorkspaceChip>,
        val selectedConnectionId: String?,   // null == All
        val items: List<NeedsYouItem>,        // already filtered by selection
        val errors: List<WorkspaceError>,
    ) : HomeUiState
}

class HomeViewModel(
    private val repo: HomeFeedRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var feed: HomeFeed = HomeFeed(emptyList(), emptyList())
    private var selected: String? = null

    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = HomeUiState.EmptyConfig; return null }
        _state.value = HomeUiState.Loading
        return (testScope ?: viewModelScope).launch {
            feed = repo.load(connections)
            render(connections)
        }
    }

    /** Select a workspace chip to scope the queue; null == All. */
    fun select(connectionId: String?) {
        selected = connectionId
        val connections = connectionsProvider()
        if (connections.isNotEmpty()) render(connections)
    }

    private fun render(connections: List<WorkspaceConnection>) {
        val counts = feed.items.groupingBy { it.connectionId }.eachCount()
        val chips = buildList {
            add(WorkspaceChip(null, "All", feed.items.size))
            connections
                .sortedWith(
                    compareByDescending<WorkspaceConnection> { counts[it.id] ?: 0 }
                        .thenBy { it.displayName().lowercase() }
                )
                .forEach { add(WorkspaceChip(it.id, it.displayName(), counts[it.id] ?: 0)) }
        }
        val visible = if (selected == null) feed.items else feed.items.filter { it.connectionId == selected }
        _state.value = HomeUiState.Content(chips, selected, visible, feed.errors)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.HomeViewModelTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeViewModel.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/HomeViewModelTest.kt
git commit -m "feat(home): HomeViewModel with workspace rail + scope filter"
mship journal "HomeViewModel: state/refresh/rail/select; 3 tests passing" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=4 -->
### Task 4: HomeScreen — chip rail + Needs-you list + tap routing

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeScreen.kt`

UI only — this codebase unit-tests ViewModels, not composables, so this task is verified by a compile/build (no test file). Routing is via callbacks the nav layer supplies (Task 5).

- [ ] **Step 1: Write the composable**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeScreen.kt
package com.atomikpanda.groundcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onApproval: (connectionId: String, specId: String) -> Unit,
    onQuestion: (connectionId: String, threadId: String) -> Unit,
    onBlocker: (connectionId: String, slug: String) -> Unit,
    onBrowseWorkspace: (connectionId: String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    when (val s = state) {
        is HomeUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is HomeUiState.EmptyConfig -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("Add a workspace in Settings to get started.")
        }
        is HomeUiState.Content -> LazyColumn(Modifier.fillMaxSize()) {
            // Workspace chip rail
            item {
                LazyRow(
                    Modifier.fillMaxSize().padding(12.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.rail) { chip ->
                        FilterChip(
                            selected = chip.connectionId == s.selectedConnectionId,
                            onClick = { vm.select(chip.connectionId) },
                            label = { Text(if (chip.count > 0) "${chip.label} · ${chip.count}" else chip.label) },
                        )
                    }
                }
            }
            // Per-connection error indicators
            items(s.errors, key = { "err:${it.connectionId}" }) { err ->
                AssistChip(
                    onClick = {},
                    label = { Text("${err.workspaceName} unreachable") },
                    modifier = Modifier.padding(12.dp, 4.dp),
                )
            }
            // "Browse this workspace" when scoped to one
            if (s.selectedConnectionId != null) {
                item {
                    TextButton(onClick = { onBrowseWorkspace(s.selectedConnectionId!!) },
                        modifier = Modifier.padding(8.dp, 0.dp)) {
                        Text("Browse all in this workspace →")
                    }
                }
            }
            // Empty state
            if (s.items.isEmpty() && s.errors.isEmpty()) {
                item {
                    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                        Text("Nothing needs you right now.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            // The "Needs you" queue
            items(s.items, key = { it.key }) { item ->
                NeedsYouRow(item, onApproval, onQuestion, onBlocker)
            }
        }
    }
}

@Composable
private fun NeedsYouRow(
    item: NeedsYouItem,
    onApproval: (String, String) -> Unit,
    onQuestion: (String, String) -> Unit,
    onBlocker: (String, String) -> Unit,
) {
    val (leading, title, supporting, onClick) = when (item) {
        is NeedsYouItem.Blocker -> Quad("⛔", "Blocked: ${item.taskSlug}", item.reason) {
            onBlocker(item.connectionId, item.taskSlug)
        }
        is NeedsYouItem.Question -> Quad("💬", item.subject, item.lastMessage) {
            onQuestion(item.connectionId, item.threadId)
        }
        is NeedsYouItem.Approval -> Quad("✅", item.title, "ready to review") {
            onApproval(item.connectionId, item.specId)
        }
    }
    ListItem(
        leadingContent = { Text(leading) },
        overlineContent = { Text(item.workspaceName, fontWeight = FontWeight.SemiBold) },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        modifier = Modifier.clickable { onClick() },
    )
}

/** Tiny holder so the when-expression can destructure. */
private data class Quad(val a: String, val b: String, val c: String, val d: () -> Unit)
```

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (HomeScreen not yet referenced by nav — that's Task 5).

- [ ] **Step 3: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeScreen.kt
git commit -m "feat(home): HomeScreen chip rail + needs-you list + routing callbacks"
mship journal "HomeScreen composable (rail, list, empty/error states, tap routing)" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=5 -->
### Task 5: Nav restructure — 5 tabs → 3, Home as start

**Files:**
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/nav/Section.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt`
- Delete: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/placeholder/PlaceholderScreen.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SectionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/atomikpanda/groundcontrol/SectionTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.nav.Section
import org.junit.Assert.assertEquals
import org.junit.Test

class SectionTest {
    @Test fun exactly_three_destinations_home_tasks_settings() {
        assertEquals(listOf("home", "tasks", "settings"), Section.entries.map { it.route })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SectionTest"`
Expected: FAIL — current routes are `specs, messages, decisions, tasks, settings`.

- [ ] **Step 3: Replace `Section.kt`**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/nav/Section.kt
package com.atomikpanda.groundcontrol.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Section(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    TASKS("tasks", "Tasks", Icons.AutoMirrored.Filled.Assignment),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}
```

- [ ] **Step 4: Rewire `GroundControlApp.kt`**

In `GroundControlApp.kt`: add `val homeRepo = remember { HomeFeedRepository(api) }` alongside the other repos. Change `startDestination = Section.SPECS.route` → `startDestination = Section.HOME.route`. Replace the `Section.SPECS`, `Section.MESSAGES`, and `Section.DECISIONS` composable blocks with a single `Section.HOME` block (below). Keep `Section.TASKS`, `Section.SETTINGS`, and all sub-routes (`specDetail/...`, `taskDetail/...`, `newThread`, `thread/...`) exactly as they are. Add the new `workspace/{connectionId}` route in Task 7.

Replace the three removed tab blocks with:

```kotlin
            composable(Section.HOME.route) {
                val vm = viewModel {
                    HomeViewModel(homeRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                HomeScreen(
                    vm,
                    onApproval = { connId, specId -> nav.navigate("specDetail/$connId/$specId") },
                    onQuestion = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onBlocker = { connId, slug -> nav.navigate("taskDetail/$connId/$slug") },
                    onBrowseWorkspace = { connId -> nav.navigate("workspace/$connId") },
                )
            }
```

Add imports: `com.atomikpanda.groundcontrol.data.HomeFeedRepository`, `com.atomikpanda.groundcontrol.ui.home.HomeScreen`, `com.atomikpanda.groundcontrol.ui.home.HomeViewModel`. Remove now-unused imports for `PlaceholderScreen`, `SpecInboxScreen`/`SpecInboxViewModel`, `MessagesScreen`/`MessagesViewModel` **only if** the IDE/compiler flags them unused (the `newThread`/`thread` routes still use `NewThread*`/`Conversation*` and `ThreadsRepository`, so keep those).

> The `workspace/{connectionId}` route referenced by `onBrowseWorkspace` is added in Task 7. To keep this task compiling on its own, temporarily point it at an existing route — use `nav.navigate("specDetail/$connId/_")`? No. Instead, for this task only, make `onBrowseWorkspace = {}` (no-op), then wire it in Task 7. Use:

```kotlin
                    onBrowseWorkspace = { /* wired in Task 7 */ },
```

- [ ] **Step 5: Delete the placeholder screen**

```bash
git rm android/app/src/main/java/com/atomikpanda/groundcontrol/ui/placeholder/PlaceholderScreen.kt
```

- [ ] **Step 6: Run tests + build**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SectionTest" && ./gradlew compileDebugKotlin`
Expected: SectionTest PASS; BUILD SUCCESSFUL. App now opens on Home with a 3-tab bar.

- [ ] **Step 7: Commit + journal**

```bash
git add -A
git commit -m "feat(nav): 3-tab IA (Home/Tasks/Settings); Home is the start; drop Specs/Messages/Decisions tabs"
mship journal "Nav restructured to 3 tabs; Home wired to queue; PlaceholderScreen removed; SectionTest green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=6 -->
### Task 6: WorkspaceViewModel — scoped single-workspace browse

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/workspace/WorkspaceViewModel.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/WorkspaceViewModelTest.kt`

Loads one connection's threads + specs + tasks for the scoped view. Single-connection (no `connectionsProvider`); same `testScope?` pattern as `SpecDetailViewModel`.

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/atomikpanda/groundcontrol/WorkspaceViewModelTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.workspace.WorkspaceUiState
import com.atomikpanda.groundcontrol.ui.workspace.WorkspaceViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("c1", "http://h:47100", null, "ws-a")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(scope: kotlinx.coroutines.CoroutineScope, failTasks: Boolean = false): WorkspaceViewModel {
        val api = SpecApi(HttpClient(MockEngine { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/threads") -> respond("""[{"id":"t1","subject":"Q"}]""", HttpStatusCode.OK, jsonHdr)
                p.endsWith("/specs") -> respond("""[{"id":"s1","title":"A","status":"drafting"}]""", HttpStatusCode.OK, jsonHdr)
                p.endsWith("/tasks") ->
                    if (failTasks) respond("boom", HttpStatusCode.InternalServerError, jsonHdr)
                    else respond("""[{"slug":"k1","phase":"dev","branch":"b"}]""", HttpStatusCode.OK, jsonHdr)
                else -> respond("[]", HttpStatusCode.OK, jsonHdr)
            }
        }) { mshipDefaults() })
        return WorkspaceViewModel(api, conn, testScope = scope)
    }

    @Test fun loads_all_three_lists_for_one_workspace() = runTest {
        val vm = vm(this)
        vm.refresh()?.join()
        val c = vm.state.value as WorkspaceUiState.Content
        assertEquals(listOf("t1"), c.threads.map { it.id })
        assertEquals(listOf("s1"), c.specs.map { it.id })
        assertEquals(listOf("k1"), c.tasks.map { it.slug })
        assertTrue(!c.errored)
    }

    @Test fun partial_failure_sets_errored_but_keeps_other_lists() = runTest {
        val vm = vm(this, failTasks = true)
        vm.refresh()?.join()
        val c = vm.state.value as WorkspaceUiState.Content
        assertEquals(listOf("t1"), c.threads.map { it.id })
        assertTrue(c.tasks.isEmpty())
        assertTrue(c.errored)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.WorkspaceViewModelTest"`
Expected: FAIL — `WorkspaceViewModel` / `WorkspaceUiState` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/workspace/WorkspaceViewModel.kt
package com.atomikpanda.groundcontrol.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface WorkspaceUiState {
    data object Loading : WorkspaceUiState
    data class Content(
        val threads: List<ThreadSummary>,
        val specs: List<SpecSummary>,
        val tasks: List<TaskSummary>,
        val errored: Boolean,
    ) : WorkspaceUiState
}

class WorkspaceViewModel(
    private val api: SpecApi,
    private val conn: WorkspaceConnection,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<WorkspaceUiState>(WorkspaceUiState.Loading)
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    fun refresh(): Job = (testScope ?: viewModelScope).launch {
        val threads = async { runCatching { api.listThreads(conn) } }
        val specs = async { runCatching { api.listSpecs(conn) } }
        val tasks = async { runCatching { api.listTasks(conn) } }
        val t = threads.await(); val s = specs.await(); val k = tasks.await()
        _state.value = WorkspaceUiState.Content(
            threads = t.getOrDefault(emptyList()),
            specs = s.getOrDefault(emptyList()),
            tasks = k.getOrDefault(emptyList()),
            errored = t.isFailure || s.isFailure || k.isFailure,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.WorkspaceViewModelTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/workspace/WorkspaceViewModel.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/WorkspaceViewModelTest.kt
git commit -m "feat(workspace): scoped single-workspace browse ViewModel"
mship journal "WorkspaceViewModel loads scoped threads/specs/tasks; partial-failure handling; 2 tests" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=7 -->
### Task 7: WorkspaceScreen + route + scoped new-conversation

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/workspace/WorkspaceScreen.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/messages/NewThreadScreen.kt`

UI + nav wiring (build-verified). Satisfies AC7's scoped view + scoped creation.

- [ ] **Step 1: Write `WorkspaceScreen.kt`**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/workspace/WorkspaceScreen.kt
package com.atomikpanda.groundcontrol.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    vm: WorkspaceViewModel,
    workspaceName: String,
    onThread: (threadId: String) -> Unit,
    onSpec: (specId: String) -> Unit,
    onTask: (slug: String) -> Unit,
    onNewConversation: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workspaceName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewConversation,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New conversation") },
            )
        },
    ) { padding ->
        when (val s = state) {
            is WorkspaceUiState.Loading ->
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            is WorkspaceUiState.Content -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { Header("Conversations") }
                items(s.threads.size, key = { "t:${s.threads[it].id}" }) { i ->
                    val t = s.threads[i]
                    ListItem(
                        headlineContent = { Text(t.subject.ifBlank { t.id }) },
                        supportingContent = { Text(t.lastMessage) },
                        modifier = Modifier.clickable { onThread(t.id) },
                    )
                }
                item { Header("Specs") }
                items(s.specs.size, key = { "s:${s.specs[it].id}" }) { i ->
                    val sp = s.specs[i]
                    ListItem(
                        headlineContent = { Text(sp.title) },
                        supportingContent = { Text(sp.status) },
                        modifier = Modifier.clickable { onSpec(sp.id) },
                    )
                }
                item { Header("Tasks") }
                items(s.tasks.size, key = { "k:${s.tasks[it].slug}" }) { i ->
                    val k = s.tasks[i]
                    ListItem(
                        headlineContent = { Text(k.slug) },
                        supportingContent = { Text(k.phase) },
                        modifier = Modifier.clickable { onTask(k.slug) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
```

- [ ] **Step 2: Add the `workspace/{connectionId}` route in `GroundControlApp.kt`**

Add this composable block next to the other sub-routes, and update the Home block's `onBrowseWorkspace` (from Task 5's temporary no-op) to `onBrowseWorkspace = { connId -> nav.navigate("workspace/$connId") }`:

```kotlin
            composable(
                route = "workspace/{connectionId}",
                arguments = listOf(navArgument("connectionId") { type = NavType.StringType }),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to Home.") }
                } else {
                    val vm = viewModel(key = "workspace-$connectionId") {
                        WorkspaceViewModel(api, conn)
                    }
                    WorkspaceScreen(
                        vm,
                        workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                        onThread = { id -> nav.navigate("thread/$connectionId/$id") },
                        onSpec = { id -> nav.navigate("specDetail/$connectionId/$id") },
                        onTask = { slug -> nav.navigate("taskDetail/$connectionId/$slug") },
                        onNewConversation = { nav.navigate("newThread?connectionId=$connectionId") },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
```

Add imports: `com.atomikpanda.groundcontrol.ui.workspace.WorkspaceScreen`, `com.atomikpanda.groundcontrol.ui.workspace.WorkspaceViewModel`.

- [ ] **Step 3: Make `newThread` accept an optional preselected workspace**

Change the existing `composable("newThread")` to an optional-arg route and pass it through:

```kotlin
            composable(
                route = "newThread?connectionId={connectionId}",
                arguments = listOf(navArgument("connectionId") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                }),
            ) { entry ->
                val preselect = entry.arguments?.getString("connectionId")
                val vm = viewModel {
                    NewThreadViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                NewThreadScreen(
                    vm,
                    initialConnectionId = preselect,
                    onCreated = { connId, id ->
                        nav.navigate("thread/$connId/$id") { popUpTo("newThread?connectionId={connectionId}") { inclusive = true } }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
```

Then add the `initialConnectionId` param to `NewThreadScreen` and preselect once connections load. In `NewThreadScreen.kt`, change the signature and add a `LaunchedEffect`:

```kotlin
@Composable
fun NewThreadScreen(
    vm: NewThreadViewModel,
    onCreated: (connectionId: String, threadId: String) -> Unit,
    onBack: () -> Unit,
    initialConnectionId: String? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    // Preselect the scoped workspace once connections are available.
    LaunchedEffect(state.connections, initialConnectionId) {
        if (initialConnectionId != null && state.connections.any { it.id == initialConnectionId }) {
            vm.onSelectConnection(initialConnectionId)
        }
    }
    // ...rest unchanged...
```

- [ ] **Step 4: Build**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Existing `NewThreadViewModelTest` still passes — signature change is source-compatible via the default param.)

- [ ] **Step 5: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/workspace/WorkspaceScreen.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/ui/messages/NewThreadScreen.kt
git commit -m "feat(workspace): scoped browse screen + route + scoped new-conversation"
mship journal "WorkspaceScreen + workspace/{id} route + newThread preselect; scoped browse/create wired" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=8 -->
### Task 8: Full-suite verification + evidence

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit-test suite via mship (records evidence for `mship finish`)**

Run: `mship test --repos ground-control`
Expected: all unit tests PASS, including the four new files (`NeedsYouItemTest`, `HomeFeedRepositoryTest`, `HomeViewModelTest`, `WorkspaceViewModelTest`, `SectionTest`) and the pre-existing suite still green.

- [ ] **Step 2: Confirm the build assembles**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke (optional, needs a running workspace) — `mship capture` or a device**

With at least one paired workspace reachable, launch the app and verify: opens on **Home** (3-tab bar); "Needs you" shows approvals/questions/blockers chipped by workspace; tapping routes to spec detail / thread / task detail; the chip rail filters; "Browse all in this workspace →" opens the scoped view; an unreachable workspace shows an "unreachable" chip but others still load. (Capture a screenshot with `mship capture --repo ground-control --platform android` if a device/emulator is attached.)

- [ ] **Step 4: Journal the slice complete**

```bash
mship journal "Slice 1 (IA + Home queue) complete; full ground-control unit suite green" --action verified --test-state pass
```

- [ ] **Step 5: Move to review phase**

Run: `mship phase review`
Expected: transitions (tests have passing evidence from Step 1).
<!-- /mship:task -->

---

## Self-Review

**Spec coverage** (each acceptance criterion → task):
- AC1 (exactly 3 tabs) → Task 5 (`SectionTest`).
- AC2 (one queue aggregating all workspaces, workspace chip) → Tasks 2, 3, 4.
- AC3 (three item kinds) → Task 1 (mapping) + Task 2 (merge).
- AC4 (documented urgency order) → Task 1 (`needsYouComparator`, plan header note).
- AC5 (tap routes to spec detail / thread / task detail) → Task 4 callbacks + Task 5 nav wiring.
- AC6 (acted item leaves queue on refresh) → emergent: items are derived from `needs_review`/`awaiting_reply`/`blocked_reason`, so a refresh after approving/replying drops them. Verified manually in Task 8 Step 3 (no unit test — depends on live server state).
- AC7 (scoped single-workspace view + scoped creation) → Tasks 6, 7.
- AC8 (one connection fails, others still load + indicator) → Task 2 (`one_failing_workspace_isolates…` test) + Task 4 (error chips).
- AC9 (empty state) → Task 4 (HomeScreen empty branch).
- AC10 (JVM unit tests for merge/sort + per-connection isolation) → Tasks 1, 2, 3, 6.

**Known simplifications (documented, not silent):** APPROVAL items sort by title (no spec timestamp over `/specs`); zero-item workspaces sort by name (no global last-activity signal). Both noted in the plan header; revisit if mothership later exposes timestamps. AC6 has no unit test (server-state dependent) — covered by manual smoke.

**Placeholder scan:** none — every step has concrete code or an exact command.

**Type consistency:** `NeedsYouItem`/`UrgencyTier` (Task 1) used consistently in Tasks 2–4; `HomeFeed`/`WorkspaceError` (Task 2) used in Task 3; `HomeUiState`/`WorkspaceChip` (Task 3) consumed in Task 4; `WorkspaceUiState` (Task 6) consumed in Task 7. `SpecSummary.status`, `ThreadSummary.awaitingReply`, `TaskSummary.blockedReason`/`slug`/`createdAt` match the verified DTOs.
