# Ground Control Messages tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Spec:** `ground-control-messages-tab` (approved) — workspace `specs/2026-06-23-ground-control-messages-tab.md`.

**Goal:** A Messages tab in Ground Control for two-way agent chat over the merged mailbox endpoints — thread list + conversation + a "new thread" capture entry — repurposing the Capture tab (the chat supersedes the form).

**Architecture:** ground-control only. Mirrors the existing screens: list = `ui/specs` (SpecInbox), conversation = `ui/specdetail` (SpecDetail), new-thread = the old `ui/capture` workspace-picker. MVVM + StateFlow, reusing `mshipDefaults` error mapping, the `connectionId` nav carry, `runBlockingSnapshot` connection-resolve, fan-out repository, and pull-to-refresh. JVM unit tests; screens build-verified. `source ~/toolchains/android-env.sh` before gradle.

**Endpoints (merged in mothership):** `GET /threads` → `[ThreadSummary]`; `GET /threads/{id}` → `Thread` (with `messages` + `awaiting_reply`); `POST /threads {text, subject?}` → `Thread`; `POST /threads/{id}/messages {text}` → `Thread`.

---

<!-- mship:task id=1 -->
### Task 1: Thread DTOs + API + ThreadsRepository

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/dto/ThreadDtos.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/MshipClient.kt`
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/ThreadsRepository.kt`
- Test: `ThreadDtosTest.kt`, `ThreadsApiTest.kt`, `ThreadsRepositoryTest.kt`

- [ ] **Step 1: Write failing tests** (mirror `TaskDtosTest`/`TasksApiTest`/`TasksRepositoryTest`):

`ThreadDtosTest.kt`:
```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadDtosTest {
    private val json = buildJson()

    @Test fun parses_thread_summary() {
        val raw = """{"id":"t1","subject":"Idea","updated_at":"2026-06-23T00:00:00Z",
            "awaiting_reply":true,"last_message":"build a thing","message_count":1}"""
        val t = json.decodeFromString(ThreadSummary.serializer(), raw.trimIndent())
        assertEquals("t1", t.id)
        assertEquals("Idea", t.subject)
        assertTrue(t.awaitingReply)
        assertEquals("build a thing", t.lastMessage)
    }

    @Test fun parses_full_thread_with_messages() {
        val raw = """{"id":"t1","subject":"Idea","created_at":"x","updated_at":"y","task_slug":null,
            "awaiting_reply":false,
            "messages":[{"id":"m1","thread_id":"t1","role":"human","text":"hi","created_at":"x"},
                        {"id":"m2","thread_id":"t1","role":"agent","text":"drafted","created_at":"y"}]}"""
        val t = json.decodeFromString(Thread.serializer(), raw.trimIndent())
        assertEquals(2, t.messages.size)
        assertEquals("agent", t.messages[1].role)
        assertEquals(false, t.awaitingReply)
    }
}
```

`ThreadsApiTest.kt`:
```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
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

class ThreadsApiTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(h: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(h)) { mshipDefaults() }

    @Test fun list_threads_path_and_auth() = runTest {
        var url: String? = null; var auth: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString(); auth = req.headers[HttpHeaders.Authorization]
            respond("""[{"id":"t1","subject":"s"}]""", HttpStatusCode.OK, jsonHdr)
        })
        assertEquals(1, api.listThreads(conn).size)
        assertTrue(url!!.endsWith("/threads")); assertEquals("Bearer secret", auth)
    }

    @Test fun create_thread_posts_text() = runTest {
        var body: String? = null
        val api = SpecApi(client { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"id":"t9","subject":"My idea","messages":[]}""", HttpStatusCode.OK, jsonHdr)
        })
        val t = api.createThread(conn, "My idea", null)
        assertEquals("t9", t.id)
        assertTrue(body!!.contains("\"text\":\"My idea\""))
    }

    @Test fun post_message_hits_messages_path() = runTest {
        var url: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString()
            respond("""{"id":"t1","subject":"s","messages":[]}""", HttpStatusCode.OK, jsonHdr)
        })
        api.postMessage(conn, "t1", "hello")
        assertTrue(url!!.endsWith("/threads/t1/messages"))
    }

    @Test(expected = com.atomikpanda.groundcontrol.data.NotFoundException::class)
    fun get_thread_maps_404() = runTest {
        val api = SpecApi(client { respond("""{"detail":"no thread"}""", HttpStatusCode.NotFound, jsonHdr) })
        api.getThread(conn, "missing")
    }
}
```

`ThreadsRepositoryTest.kt`: mirror `TasksRepositoryTest` — two connections, one OK one 500, assert `listAllThreads` returns both with per-workspace `Result` (success + failure isolated).

- [ ] **Step 2: Run to verify they fail** — `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.Thread*"` → FAIL.

- [ ] **Step 3: Implement**

`data/dto/ThreadDtos.kt`:
```kotlin
package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThreadSummary(
    val id: String,
    val subject: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("awaiting_reply") val awaitingReply: Boolean = false,
    @SerialName("last_message") val lastMessage: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
)

@Serializable
data class Message(
    val id: String,
    @SerialName("thread_id") val threadId: String = "",
    val role: String,
    val text: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Thread(
    val id: String,
    val subject: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("task_slug") val taskSlug: String? = null,
    val messages: List<Message> = emptyList(),
    @SerialName("awaiting_reply") val awaitingReply: Boolean = false,
)

@Serializable data class NewThreadBody(val text: String, val subject: String? = null)
@Serializable data class NewMessageBody(val text: String)
```

In `data/MshipClient.kt`, add imports for `Thread`, `ThreadSummary`, `NewThreadBody`, `NewMessageBody`, and add to `SpecApi` (reuse the private `auth`/`jsonBody` helpers):
```kotlin
    suspend fun listThreads(conn: WorkspaceConnection): List<ThreadSummary> =
        client.get("${conn.baseUrl}/threads") { auth(conn) }.body()

    suspend fun getThread(conn: WorkspaceConnection, id: String): Thread =
        client.get("${conn.baseUrl}/threads/$id") { auth(conn) }.body()

    suspend fun createThread(conn: WorkspaceConnection, text: String, subject: String?): Thread =
        client.post("${conn.baseUrl}/threads") { auth(conn); jsonBody(NewThreadBody(text, subject)) }.body()

    suspend fun postMessage(conn: WorkspaceConnection, id: String, text: String): Thread =
        client.post("${conn.baseUrl}/threads/$id/messages") { auth(conn); jsonBody(NewMessageBody(text)) }.body()
```

`data/ThreadsRepository.kt` (mirror `TasksRepository`):
```kotlin
package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class WorkspaceThreads(
    val connection: WorkspaceConnection,
    val threads: Result<List<ThreadSummary>>,
)

class ThreadsRepository(private val api: SpecApi) {
    suspend fun listAllThreads(connections: List<WorkspaceConnection>): List<WorkspaceThreads> =
        coroutineScope {
            connections.map { conn ->
                async { WorkspaceThreads(conn, runCatching { api.listThreads(conn) }) }
            }.awaitAll()
        }

    suspend fun getThread(conn: WorkspaceConnection, id: String) = api.getThread(conn, id)
    suspend fun createThread(conn: WorkspaceConnection, text: String, subject: String?) = api.createThread(conn, text, subject)
    suspend fun postMessage(conn: WorkspaceConnection, id: String, text: String) = api.postMessage(conn, id, text)
}
```

- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit** (`feat(gc): Thread DTOs + threads API + ThreadsRepository`), `mship journal ... --repo ground-control`.
<!-- /mship:task -->

<!-- mship:task id=2 -->
### Task 2: Messages list + nav repurpose (retire Capture)

**Files:**
- Create: `ui/messages/MessagesViewModel.kt`, `ui/messages/MessagesScreen.kt`
- Modify: `ui/nav/Section.kt`, `GroundControlApp.kt`
- Delete: `ui/capture/CaptureScreen.kt`, `ui/capture/CaptureViewModel.kt`, `tests/.../CaptureViewModelTest.kt`
- Test: `MessagesViewModelTest.kt`

- [ ] **Step 1: Write the failing test** — `MessagesViewModelTest.kt` mirrors `TasksViewModelTest`: one connection, GET /threads returns two summaries; assert the section carries `workspaceName` + `connectionId` and the threads render (sorted as returned). `MessagesViewModel` mirrors `TasksViewModel` but without active/finished groups — a single list per workspace section.

- [ ] **Step 2: Run → fail.**

- [ ] **Step 3: Implement the ViewModel** — `ui/messages/MessagesViewModel.kt` (mirror `TasksViewModel`, simpler — no group split):
```kotlin
package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ThreadsSection(
    val workspaceName: String,
    val connectionId: String,
    val threads: Result<List<ThreadSummary>>,
)

sealed interface MessagesUiState {
    data object Loading : MessagesUiState
    data object EmptyConfig : MessagesUiState
    data class Content(val sections: List<ThreadsSection>) : MessagesUiState
}

class MessagesViewModel(
    private val repo: ThreadsRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<MessagesUiState>(MessagesUiState.Loading)
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = MessagesUiState.EmptyConfig; return null }
        _state.value = MessagesUiState.Loading
        return (testScope ?: viewModelScope).launch {
            val results = repo.listAllThreads(connections)
            _state.value = MessagesUiState.Content(results.map { ws ->
                ThreadsSection(
                    workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                    connectionId = ws.connection.id,
                    threads = ws.threads,
                )
            })
        }
    }
}
```

- [ ] **Step 4: Run → pass.**

- [ ] **Step 5: Write the screen** — `ui/messages/MessagesScreen.kt`, mirror `ui/specs/SpecInboxScreen.kt` (pull-to-refresh, section header, error chip, empty-config). Row per thread (`ListItem`): headline = `thread.subject.ifBlank { "(no subject)" }`; supporting = `thread.lastMessage` + an awaiting marker (e.g. `⏳ waiting` when `awaitingReply` else `✓ replied`) + `thread.updatedAt`; `Modifier.clickable { onThreadClick(section.connectionId, thread.id) }`. Add a `FloatingActionButton` (or top action) calling `onNewThread()`. Signature: `fun MessagesScreen(vm: MessagesViewModel, onThreadClick: (String, String) -> Unit, onNewThread: () -> Unit)`.

- [ ] **Step 6: Repurpose nav** —
  - `ui/nav/Section.kt`: replace the `CAPTURE("capture", "Capture", Icons.Filled.MicNone)` entry with `MESSAGES("messages", "Messages", Icons.AutoMirrored.Filled.Chat)` (add `import androidx.compose.material.icons.automirrored.filled.Chat`; remove the now-unused `MicNone` import).
  - `GroundControlApp.kt`: remove the `ui.capture.*` imports; add `import com.atomikpanda.groundcontrol.data.ThreadsRepository`, `ui.messages.MessagesScreen`, `ui.messages.MessagesViewModel`; add `val threadsRepo = remember { ThreadsRepository(api) }`. Replace the `composable(Section.CAPTURE.route) { … CaptureScreen … }` block with:
    ```kotlin
            composable(Section.MESSAGES.route) {
                val vm = viewModel {
                    MessagesViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                MessagesScreen(
                    vm,
                    onThreadClick = { connId, id -> nav.navigate("thread/$connId/$id") },
                    onNewThread = { nav.navigate("newThread") },
                )
            }
    ```
    (The `thread/...` and `newThread` routes are added in Tasks 3 and 4.)
  - Delete `ui/capture/CaptureScreen.kt`, `ui/capture/CaptureViewModel.kt`, and `tests/.../CaptureViewModelTest.kt` (`git rm`). Keep `SpecApi.createSpec` + `NewSpecBody` (server contract; used later). Confirm nothing else references the deleted Capture symbols (`grep -rn Capture android/app/src`).

- [ ] **Step 7: Build** — `cd android && ./gradlew assembleDebug` → SUCCESSFUL (the `thread`/`newThread` routes don't exist yet but `nav.navigate` to them compiles).

- [ ] **Step 8: Commit** (`feat(gc): Messages list + repurpose Capture nav -> Messages; retire quick-capture form`), journal `--repo ground-control`.
<!-- /mship:task -->

<!-- mship:task id=3 -->
### Task 3: Conversation screen

**Files:**
- Create: `ui/messages/ConversationViewModel.kt`, `ui/messages/ConversationScreen.kt`
- Modify: `GroundControlApp.kt`
- Test: `ConversationViewModelTest.kt`

- [ ] **Step 1: Write failing tests** — mirror `TaskDetailViewModelTest`: load success → `Content(thread)` with messages; `send(text)` posts and updates `thread` from the returned `Thread`; 404→NOT_FOUND, 401→AUTH. The MockEngine handler branches on `req.url.encodedPath` (`/threads/t1` GET vs `/threads/t1/messages` POST).

- [ ] **Step 2: Run → fail.**

- [ ] **Step 3: Implement the ViewModel** — `ui/messages/ConversationViewModel.kt`: `StateFlow<ConversationUiState>` = `Loading | Error(kind: ErrorKind, message) | Content(thread: Thread, inFlight: Boolean = false)`. `load()` fetches `repo.getThread(conn, id)`; `send(text)` (no-op if blank/inFlight) sets inFlight, calls `repo.postMessage(conn, id, text)`, replaces `thread` from the returned Thread, clears inFlight; map `AuthException→AUTH`, `NotFoundException→NOT_FOUND`, else `NETWORK`. Reuse `ErrorKind` from `ui.specdetail` (import) or define locally. Constructor `(repo: ThreadsRepository, conn: WorkspaceConnection, threadId: String, testScope: CoroutineScope? = null)`.

- [ ] **Step 4: Run → pass.**

- [ ] **Step 5: Write the screen** — `ui/messages/ConversationScreen.kt`, mirror `SpecDetailScreen`'s scaffold (TopAppBar + back + pull-to-refresh + Loading/Error/Content). Content: a `LazyColumn` of message rows — human vs agent distinguished (e.g. align/color by `message.role == "human"`); a compose `Row` pinned at the bottom (an `OutlinedTextField` + Send) calling `vm.send(text)`; an awaiting hint when `thread.awaitingReply`. `fun ConversationScreen(vm: ConversationViewModel, title: String, onBack: () -> Unit)`; top-bar title = `(state as? Content)?.thread?.subject ?: title`.

- [ ] **Step 6: Add the route** in `GroundControlApp.kt` — `thread/{connectionId}/{threadId}` mirroring the `taskDetail/...` route (resolve connection via `runBlockingSnapshot`, null fallback, build `ConversationViewModel(threadsRepo, conn, threadId)`, render `ConversationScreen(vm, title = threadId, onBack = { nav.popBackStack() })`). Add the imports.

- [ ] **Step 7: Build** → SUCCESSFUL.
- [ ] **Step 8: Commit** (`feat(gc): conversation screen (message timeline + compose)`), journal `--repo ground-control`.
<!-- /mship:task -->

<!-- mship:task id=4 -->
### Task 4: New thread (the capture entry)

**Files:**
- Create: `ui/messages/NewThreadViewModel.kt`, `ui/messages/NewThreadScreen.kt`
- Modify: `GroundControlApp.kt`
- Test: `NewThreadViewModelTest.kt`

- [ ] **Step 1: Write failing tests** — `NewThreadViewModel` mirrors the (now-deleted) capture VM's workspace-selection logic but creates a THREAD: state `{connections, selectedConnectionId, subject, text, inFlight, createdThreadId?, error?}`; `defaultSelection` = single connection auto-selected; `canCreate` = text non-blank + a connection selected + !inFlight; `create()` calls `repo.createThread(conn, text, subject?)` and exposes the returned thread's id (for navigation). Tests: no-connections empty; single auto-select; blank-text blocks; create success exposes `createdThreadId`; 409/other → error. (Copy the structure from the git history of `CaptureViewModel` / `CaptureViewModelTest` deleted in Task 2 — same picker logic, `createThread` instead of `createSpec`.)

- [ ] **Step 2–4: TDD the ViewModel** (`ui/messages/NewThreadViewModel.kt`).

- [ ] **Step 5: Write the screen** — `ui/messages/NewThreadScreen.kt`: a workspace picker (when >1 connection; auto when 1; empty-state when none — mirror the deleted CaptureScreen), a subject field (optional), a multi-line message field, and a Create button gated by `canCreate`. `fun NewThreadScreen(vm: NewThreadViewModel, onCreated: (connectionId: String, threadId: String) -> Unit, onBack: () -> Unit)` — on `createdThreadId` becoming non-null, call `onCreated(selectedConnectionId, id)`.

- [ ] **Step 6: Add the route + wire** in `GroundControlApp.kt` — a `composable("newThread")` building `NewThreadViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })`, rendering `NewThreadScreen(vm, onCreated = { connId, id -> nav.navigate("thread/$connId/$id") { popUpTo("newThread") { inclusive = true } } }, onBack = { nav.popBackStack() })` so creating a thread replaces the compose screen with the conversation.

- [ ] **Step 7: Build + full unit suite** — `cd android && ./gradlew assembleDebug testDebugUnitTest` → green.
- [ ] **Step 8: Commit** (`feat(gc): new-thread compose (the chat capture entry)`), journal `--repo ground-control`.
<!-- /mship:task -->

<!-- mship:task id=5 -->
### Task 5: full verification + phase transition

- [ ] **Step 1:** `mship test` (or `source ~/toolchains/android-env.sh && cd android && ./gradlew assembleDebug testDebugUnitTest`) → green.
- [ ] **Step 2:** Confirm acceptance criteria against `specs/2026-06-23-ground-control-messages-tab.md` (ac1 nav repurpose+capture removed → T2; ac2 DTOs/API → T1; ac3 list → T2; ac4 new-thread → T4; ac5 conversation → T3; ac6 errors → T3; ac7 tests → all). `grep -rn Capture android/app/src` should show no dangling references (createSpec/NewSpecBody aside). Note any gap.
- [ ] **Step 3:** `mship journal "Messages tab (chat) implemented; Capture retired; suite green" --action completed --test-state pass --repo ground-control` then `mship phase review`.

> Then `mship finish --body-file <path>` to open the PR.
<!-- /mship:task -->

---

## Non-goals (from the spec)

capture→spec promotion (slice 3) · task-steering (slice 4) · notifications · SSE/real-time · edit/delete · voice · keeping the old Quick Capture form.
