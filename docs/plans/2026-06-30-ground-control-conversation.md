# Ground Control Conversation: Live Reply + Reply-Box Ergonomics — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `ground-control-conversation` (approved + dispatched). Repo: `ground-control` (Android/Kotlin/Compose). q1 answered "yes" — the ~25s server wait / ~35s client request timeout sit under the relay idle-read timeout.

**Goal:** Make the GC conversation screen show the agent's reply live, never lose the in-progress draft, and stay scrolled to the latest message when the keyboard is open.

**Architecture:** Three client-side changes: (1) move the compose draft from a Composable `remember` into `ConversationViewModel` (subsuming the restore-on-failure buffer); (2) add a `GET /threads?wait=1` long-poll (`SpecApi.listThreadsWait` + `HttpTimeout` so OkHttp doesn't abort the wait) that `ConversationViewModel` runs in a loop to refresh the open thread live; (3) scroll the message list to the bottom when the keyboard opens.

**Tech Stack:** Kotlin, Jetpack Compose, Ktor (OkHttp engine, `MockEngine` in tests), kotlinx-coroutines-test (`runTest`, `backgroundScope`), JUnit4. JVM unit tests only — **no emulator**; the keyboard-scroll fix is verified via `mship capture`.

**Test commands:** full GC suite `mship test --repos ground-control`; targeted (faster), with the toolchain sourced: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ConversationViewModelTest*"`.

---

## File Structure

- **Modify** `android/app/.../data/dto/ThreadDtos.kt` — add `ThreadsWaitResponse`.
- **Modify** `android/app/.../data/MshipClient.kt` — `install(HttpTimeout)` in `mshipDefaults()`; add `SpecApi.listThreadsWait(...)`.
- **Modify** `android/app/.../data/ThreadsRepository.kt` — add `waitForChange(...)`.
- **Modify** `android/app/.../ui/messages/ConversationViewModel.kt` — `draft` state + `onDraftChange`/`clearDraft`; the live-reply poll loop.
- **Modify** `android/app/.../ui/messages/ConversationScreen.kt` — `ComposeBar` reads `vm.draft`; IME-visibility scroll.
- **Test** `android/app/src/test/.../ConversationViewModelTest.kt` (draft + poll loop), `ThreadsApiTest.kt` (the wait call), `ThreadDtosTest.kt` (DTO).

(Package root: `android/app/src/main/java/com/atomikpanda/groundcontrol`. Test root: `android/app/src/test/java/com/atomikpanda/groundcontrol`.)

---

<!-- mship:task id=1 -->
### Task 1: Draft lives in the ViewModel

**Files:**
- Modify: `.../ui/messages/ConversationViewModel.kt`
- Modify: `.../ui/messages/ConversationScreen.kt` (`ComposeBar` becomes stateless)
- Test: `.../test/.../ConversationViewModelTest.kt`

- [ ] **Step 1: Add the failing tests** (append to `ConversationViewModelTest.kt`, reusing its existing `vm()`/`threadJson`/`afterSendJson` helpers)

```kotlin
@Test
fun draft_persists_across_a_reload() = runTest {
    val handler: MockRequestHandler = { respond(threadJson, HttpStatusCode.OK, jsonHdr) }
    val v = vm(this, handler)
    v.load()?.join()
    v.onDraftChange("half-typed steering note")
    v.load()?.join()                    // a reload flips state to Loading→Content
    assertEquals("half-typed steering note", v.draft.value)   // draft survives
}

@Test
fun send_clears_draft_on_success() = runTest {
    var n = 0
    val handler: MockRequestHandler = {
        if (it.method == HttpMethod.Post) respond(afterSendJson, HttpStatusCode.OK, jsonHdr)
        else respond(threadJson, HttpStatusCode.OK, jsonHdr)
    }
    val v = vm(this, handler)
    v.load()?.join()
    v.onDraftChange("ship it")
    v.send("ship it")?.join(); advanceUntilIdle()
    assertEquals("", v.draft.value)                 // cleared on success
}

@Test
fun send_keeps_draft_on_failure() = runTest {
    val handler: MockRequestHandler = {
        if (it.method == HttpMethod.Post) respondError(HttpStatusCode.InternalServerError)
        else respond(threadJson, HttpStatusCode.OK, jsonHdr)
    }
    val v = vm(this, handler)
    v.load()?.join()
    v.onDraftChange("don't lose me")
    v.send("don't lose me")?.join(); advanceUntilIdle()
    assertEquals("don't lose me", v.draft.value)     // kept on failure
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ConversationViewModelTest*"`
Expected: FAIL — `v.draft` / `onDraftChange` don't exist yet (compile error).

- [ ] **Step 3: Add draft state to `ConversationViewModel`**

Add fields + functions (after the existing `_state`/`state`):
```kotlin
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    fun onDraftChange(text: String) { _draft.value = text }
    fun clearDraft() { _draft.value = "" }
```
In `send(...)`, clear the draft on success only — change the `.onSuccess { ... }` block to:
```kotlin
                .onSuccess { updatedThread ->
                    _draft.value = ""
                    _state.value = ConversationUiState.Content(updatedThread, inFlight = false)
                }
```
(Leave the `.onFailure` path as-is; the draft is retained because we never cleared it.)

- [ ] **Step 4: Make `ComposeBar` stateless in `ConversationScreen.kt`**

Replace the whole `ComposeBar` composable with a version that reads the VM draft (drops the local `remember`/`pending`):
```kotlin
@Composable
private fun ComposeBar(state: ConversationUiState.Content, vm: ConversationViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    Surface(tonalElevation = 3.dp) {
        Column {
            state.sendError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = vm::onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    singleLine = true,
                    enabled = !state.inFlight,
                )
                if (state.inFlight) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                } else {
                    IconButton(
                        onClick = { if (draft.isNotBlank()) vm.send(draft) },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
```
Remove the now-unused imports (`mutableStateOf`, `remember`, `setValue`, `getValue` if unused elsewhere, `LaunchedEffect` if unused) — let the compiler/`./gradlew` flag them.

- [ ] **Step 5: Run to verify pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ConversationViewModelTest*"`
Expected: PASS (existing tests + the 3 new draft tests).

- [ ] **Step 6: Commit + journal**

```bash
git add android/app/src/main android/app/src/test
git commit -m "feat(conversation): draft lives in the ViewModel (survives reload/recompose)"
mship journal "conversation draft moved to VM; survives reload; send clears on success only" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=2 -->
### Task 2: `listThreadsWait` long-poll client call

**Files:**
- Modify: `.../data/dto/ThreadDtos.kt` (`ThreadsWaitResponse`)
- Modify: `.../data/MshipClient.kt` (`install(HttpTimeout)`; `SpecApi.listThreadsWait`)
- Modify: `.../data/ThreadsRepository.kt` (`waitForChange`)
- Test: `.../test/.../ThreadsApiTest.kt`

- [ ] **Step 1: Add the failing test** (append to `ThreadsApiTest.kt`; mirror its existing MockEngine setup)

```kotlin
@Test
fun listThreadsWait_hits_wait_endpoint_and_parses() = runTest {
    var capturedUrl = ""
    val handler: MockRequestHandler = { req ->
        capturedUrl = req.url.toString()
        respond(
            """{"threads":[{"id":"t1","subject":"s","updated_at":"2026-06-22T10:05:00Z"}],"cursor":"2026-06-22T10:05:00Z","timed_out":false}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    val api = SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() })
    val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    val resp = api.listThreadsWait(conn, since = "2026-06-22T10:00:00Z", timeoutSeconds = 25)
    assertTrue(capturedUrl.contains("/threads"))
    assertTrue(capturedUrl.contains("wait=1"))
    assertTrue(capturedUrl.contains("since=2026-06-22T10%3A00%3A00Z") || capturedUrl.contains("since="))
    assertTrue(capturedUrl.contains("timeout=25"))
    assertEquals(false, resp.timedOut)
    assertEquals("2026-06-22T10:05:00Z", resp.cursor)
    assertEquals("t1", resp.threads.single().id)
}
```
(Add imports as needed: `assertTrue`, `io.ktor.client.engine.mock.*`, etc. — match the file's existing imports.)

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ThreadsApiTest*"`
Expected: FAIL — `listThreadsWait` / `ThreadsWaitResponse` don't exist.

- [ ] **Step 3: Add the DTO to `ThreadDtos.kt`**

```kotlin
@Serializable
data class ThreadsWaitResponse(
    val threads: List<ThreadSummary> = emptyList(),
    val cursor: String = "",
    @SerialName("timed_out") val timedOut: Boolean = false,
)
```

- [ ] **Step 4: Install `HttpTimeout` in `mshipDefaults()` (`MshipClient.kt`)**

OkHttp's default read timeout (~10s) would abort a 25s long-poll, so enable per-request timeouts. Add to `mshipDefaults()`:
```kotlin
    install(io.ktor.client.plugins.HttpTimeout)
```
(Installing with no defaults changes nothing for existing calls; it just enables the per-request `timeout { }` block.)

- [ ] **Step 5: Add `SpecApi.listThreadsWait`**

```kotlin
    suspend fun listThreadsWait(conn: WorkspaceConnection, since: String, timeoutSeconds: Int): ThreadsWaitResponse =
        client.get("${conn.baseUrl}/threads") {
            auth(conn)
            io.ktor.client.request.parameter("wait", "1")
            io.ktor.client.request.parameter("since", since)
            io.ktor.client.request.parameter("timeout", timeoutSeconds)
            io.ktor.client.plugins.timeout {
                // Exceed the server wait so Ktor/OkHttp don't abort mid-poll.
                requestTimeoutMillis = (timeoutSeconds + 10) * 1000L
                socketTimeoutMillis = (timeoutSeconds + 10) * 1000L
            }
        }.body()
```
Add `import com.atomikpanda.groundcontrol.data.dto.ThreadsWaitResponse` at the top.

- [ ] **Step 6: Add `ThreadsRepository.waitForChange`**

```kotlin
    suspend fun waitForChange(conn: WorkspaceConnection, since: String, timeoutSeconds: Int) =
        api.listThreadsWait(conn, since, timeoutSeconds)
```
(Add the `ThreadsWaitResponse` import if the return type needs it — inferred here, so likely not.)

- [ ] **Step 7: Run to verify pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ThreadsApiTest*" --tests "*ThreadDtosTest*"`
Expected: PASS.

- [ ] **Step 8: Commit + journal**

```bash
git add android/app/src/main android/app/src/test
git commit -m "feat(data): GET /threads?wait=1 long-poll client (listThreadsWait + HttpTimeout)"
mship journal "listThreadsWait + ThreadsWaitResponse + HttpTimeout; repo.waitForChange" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=3 -->
### Task 3: Live-reply poll loop in `ConversationViewModel`

**Files:**
- Modify: `.../ui/messages/ConversationViewModel.kt`
- Modify: `.../ui/messages/ConversationScreen.kt` (start polling after load)
- Test: `.../test/.../ConversationViewModelTest.kt`

**Testability note (important):** the poll loop is an infinite `while (isActive)`, and `MockEngine` returns instantly (no real wait), so testing the loop directly would spin forever under `advanceUntilIdle()`. Extract ONE iteration into a terminating `internal suspend fun pollOnce(cursor): String` and unit-test THAT; the loop wrapper is a thin, untested shell. Do NOT auto-start the loop from `load()` — that would hang every existing test that calls `load()`; start it from the screen instead.

- [ ] **Step 1: Add the failing tests** (append to `ConversationViewModelTest.kt`)

```kotlin
private val waitHitJson = """{"threads":[{"id":"t1","subject":"s","updated_at":"2026-06-22T10:10:00Z"}],"cursor":"2026-06-22T10:10:00Z","timed_out":false}"""
private val waitMissJson = """{"threads":[{"id":"other","subject":"s","updated_at":"2026-06-22T10:10:00Z"}],"cursor":"2026-06-22T10:10:00Z","timed_out":false}"""
private val refreshedThreadJson = """
    {"id":"t1","subject":"Hello world","awaiting_reply":false,
     "messages":[
       {"id":"m1","thread_id":"t1","role":"human","text":"Hey there","created_at":"2026-06-22T10:00:00Z"},
       {"id":"m3","thread_id":"t1","role":"agent","text":"LIVE REPLY","created_at":"2026-06-22T10:10:00Z"}
     ]}
""".trimIndent()

@Test
fun pollOnce_refreshes_open_thread_and_advances_cursor() = runTest {
    val handler: MockRequestHandler = { req ->
        if (req.url.parameters["wait"] == "1") respond(waitHitJson, HttpStatusCode.OK, jsonHdr)
        else respond(refreshedThreadJson, HttpStatusCode.OK, jsonHdr)   // GET /threads/t1
    }
    val v = vm(this, handler)
    v.load()?.join()
    val next = v.pollOnce("2026-06-22T10:00:00Z")                       // one iteration, terminates
    val content = v.state.value as ConversationUiState.Content
    assertEquals("LIVE REPLY", content.thread.messages.last().text)     // refreshed live, no manual load
    assertEquals("2026-06-22T10:10:00Z", next)                          // cursor advanced
}

@Test
fun pollOnce_does_not_refresh_when_this_thread_unchanged() = runTest {
    val handler: MockRequestHandler = { req ->
        if (req.url.parameters["wait"] == "1") respond(waitMissJson, HttpStatusCode.OK, jsonHdr)
        else respond(threadJson, HttpStatusCode.OK, jsonHdr)
    }
    val v = vm(this, handler)
    v.load()?.join()
    val before = (v.state.value as ConversationUiState.Content).thread.messages.size
    v.pollOnce("2026-06-22T10:00:00Z")
    assertEquals(before, (v.state.value as ConversationUiState.Content).thread.messages.size)  // unchanged
}

@Test
fun pollOnce_returns_same_cursor_on_network_error() = runTest {
    val handler: MockRequestHandler = { req ->
        if (req.url.parameters["wait"] == "1") respondError(HttpStatusCode.InternalServerError)
        else respond(threadJson, HttpStatusCode.OK, jsonHdr)
    }
    val v = vm(this, handler)
    v.load()?.join()
    val next = v.pollOnce("2026-06-22T10:00:00Z")
    assertEquals("2026-06-22T10:00:00Z", next)                          // no crash; cursor unchanged
    assertNotNull(v.state.value as? ConversationUiState.Content)        // conversation intact
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ConversationViewModelTest*"`
Expected: FAIL — `pollOnce` doesn't exist (compile error).

- [ ] **Step 3: Implement `pollOnce` + the loop in `ConversationViewModel`**

Add imports: `kotlinx.coroutines.delay`, `kotlinx.coroutines.isActive`. Add:
```kotlin
    private var pollJob: Job? = null

    /** One long-poll iteration: wait for a change since `cursor`; if THIS thread
     *  changed (and no send is in flight), re-fetch it. Returns the next cursor
     *  (unchanged on a network error so the caller can back off). Terminating —
     *  unit-tested directly; the loop below is a thin wrapper. */
    internal suspend fun pollOnce(cursor: String): String {
        val resp = runCatching { repo.waitForChange(conn, cursor, 25) }.getOrNull() ?: return cursor
        if (resp.threads.any { it.id == threadId }) {
            val cur = _state.value
            if (cur is ConversationUiState.Content && !cur.inFlight) {
                runCatching { repo.getThread(conn, threadId) }
                    .onSuccess { _state.value = ConversationUiState.Content(it) }
            }
        }
        return resp.cursor
    }

    /** Start the live-reply loop (idempotent). Seeds from the loaded thread's
     *  high-water `updatedAt`; returns early until a thread is loaded, so the
     *  `since` is always a valid ISO timestamp (never "" the server would 422 on).
     *  Cancelled when the VM's scope is cleared. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        val seed = (_state.value as? ConversationUiState.Content)?.thread?.updatedAt ?: return
        pollJob = scope().launch {
            var cursor = seed
            while (isActive) {
                val next = pollOnce(cursor)
                if (next == cursor) delay(2000)   // no progress (error/no-change): back off
                cursor = next
            }
        }
    }
```
Leave `load()`'s `.onSuccess` as it is in Task 1 (just sets `Content`) — do NOT start polling from `load()`.

- [ ] **Step 4: Start polling from the screen** in `ConversationScreen.kt`

Change the top-level `LaunchedEffect(Unit) { vm.load() }` (≈ line 58) to load, then start polling once the thread is in:
```kotlin
    LaunchedEffect(Unit) { vm.load()?.join(); vm.startPolling() }
```

- [ ] **Step 5: Run to verify pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*ConversationViewModelTest*"`
Expected: PASS (Task 1 draft tests + the 3 `pollOnce` tests; the existing load/send tests are unaffected since they never call `startPolling`).

- [ ] **Step 6: Commit + journal**

```bash
git add android/app/src/main android/app/src/test
git commit -m "feat(conversation): live-reply poll loop (pollOnce + screen-started loop)"
mship journal "ConversationViewModel.pollOnce refreshes the open thread from GET /threads?wait=1; screen starts the loop after load; cursor advances; error-safe" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=4 -->
### Task 4: Keyboard-aware scroll-to-bottom

**Files:**
- Modify: `.../ui/messages/ConversationScreen.kt`
- Verification: `mship capture` (UI-only; no JVM/emulator test in this repo)

- [ ] **Step 1: Add the IME-visibility scroll in `ConversationContentView`**

Add imports:
```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
```
Inside `ConversationContentView`, after the existing `LaunchedEffect(itemCount) { … }` block, add a second effect that scrolls when the keyboard appears:
```kotlin
    // Keep the latest message visible when the keyboard opens — the existing
    // effect only scrolls on message-count changes, not on IME show.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `cd android && ./gradlew :app:assembleDebug` (or `:app:compileDebugKotlin`)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify behavior with a capture** (no unit test possible for IME/scroll)

With the app running on a device/emulator the operator has connected, open a conversation, focus the reply box, and run from the workspace:
```bash
mship capture --repo ground-control --platform android --kind image
```
Read the resulting screenshot and confirm the latest message is visible above the keyboard + reply box (not scrolled off). If no device is connected, note that this step is operator-verified and leave it unchecked.

- [ ] **Step 4: Commit + journal**

```bash
git add android/app/src/main
git commit -m "fix(conversation): scroll to latest message when the keyboard opens"
mship journal "IME-visibility scroll-to-bottom in ConversationContentView; capture-verified" --action committed
```
<!-- /mship:task -->

---

## Final verification (after all tasks)

- [ ] Full GC suite: `mship test --repos ground-control`. Expected: all JVM unit tests pass (new draft + poll + api/dto tests; no regressions to existing conversation/threads tests).
- [ ] Capture smoke (if a device is connected): focus the reply box → latest message visible; send → draft clears; an agent reply lands live within ~1s.
- [ ] Note for run-phase: the long-poll holds a connection ~25s; confirm it survives the relay idle-read timeout when GC connects over the relay (spec q1 = yes for the local/tailnet path).
