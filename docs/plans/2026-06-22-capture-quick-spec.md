# Ground Control Quick Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `ground-control-capture` (approved) — `specs/2026-06-22-ground-control-capture.md`.

**Goal:** Make Ground Control's Capture tab real (minimally): a form that creates a new stub spec on a chosen workspace via `POST /specs`, landing it in the inbox.

**Architecture:** MVVM + StateFlow, matching the existing screens. Reuses the merged detail work — `SpecRecord` DTO, `SpecApi` + `mshipDefaults` typed error mapping (`AuthException`/`ApiConflictException`), and the `runBlockingSnapshot(connRepo)` connections provider. JVM unit tests only (no emulator); the Compose screen is build-verified.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 1.2.1), Navigation-Compose 2.7.7, Ktor 2.3.12 + kotlinx.serialization, JUnit4 + Ktor MockEngine.

**Conventions:** Run gradle from `android/` after `source ~/toolchains/android-env.sh` (or use `mship test`). Commit from the worktree on `feat/ground-control-capture`; pair commits with `mship journal`.

---

<!-- mship:task id=1 -->
### Task 1: NewSpecBody DTO + SpecApi.createSpec

**Files:**
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/dto/SpecDetailDtos.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/MshipClient.kt`
- Modify: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecApiTest.kt`

- [ ] **Step 1: Write the failing tests** — append to `SpecApiTest.kt`:

```kotlin
    @Test fun create_spec_posts_to_specs_and_returns_record() = runTest {
        var url: String? = null
        var method: String? = null
        var body: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString(); method = req.method.value
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"id":"my-idea","title":"My idea","status":"drafting","body":""}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val rec = api.createSpec(conn, "My idea", listOf("ground-control"))
        assertEquals("my-idea", rec.id)
        assertEquals("POST", method)
        assertTrue(url!!.endsWith("/specs"))
        assertTrue(body!!.contains("\"title\":\"My idea\""))
        assertTrue(body!!.contains("\"affected_repos\":[\"ground-control\"]"))
    }

    @Test fun create_spec_409_maps_to_conflict() = runTest {
        val api = SpecApi(client { respond("""{"detail":"spec 'my-idea' already exists"}""",
            HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            api.createSpec(conn, "My idea", emptyList())
            throw AssertionError("expected ApiConflictException")
        } catch (e: com.atomikpanda.groundcontrol.data.ApiConflictException) {
            assertTrue(e.detail.contains("already exists"))
        }
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecApiTest"`
Expected: FAIL — `createSpec` unresolved.

- [ ] **Step 3: Add the DTO + method**

In `data/dto/SpecDetailDtos.kt`, add alongside the other request bodies:

```kotlin
@Serializable
data class NewSpecBody(
    val title: String,
    val id: String? = null,
    @SerialName("affected_repos") val affectedRepos: List<String> = emptyList(),
    @SerialName("task_slug") val taskSlug: String? = null,
)
```

In `data/MshipClient.kt`, add the import `import com.atomikpanda.groundcontrol.data.dto.NewSpecBody` and add this method to `SpecApi` (next to the other calls; it reuses the existing private `auth`/`jsonBody` helpers):

```kotlin
    suspend fun createSpec(conn: WorkspaceConnection, title: String, affectedRepos: List<String>): SpecRecord =
        client.post("${conn.baseUrl}/specs") { auth(conn); jsonBody(NewSpecBody(title = title, affectedRepos = affectedRepos)) }.body()
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecApiTest"`
Expected: PASS (existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/data/dto/SpecDetailDtos.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/data/MshipClient.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/SpecApiTest.kt
git commit -m "feat(gc): SpecApi.createSpec + NewSpecBody (POST /specs)"
mship journal "added SpecApi.createSpec posting NewSpecBody to /specs; tests green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=2 -->
### Task 2: CaptureViewModel

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/capture/CaptureViewModel.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/CaptureViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.capture.CaptureMessage
import com.atomikpanda.groundcontrol.ui.capture.CaptureViewModel
import com.atomikpanda.groundcontrol.ui.capture.canCreate
import com.atomikpanda.groundcontrol.ui.capture.parseRepos
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun conn(id: String) = WorkspaceConnection(id, "http://h-$id:47100", "tok", "ws-$id")

    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        conns: List<WorkspaceConnection>,
        handler: io.ktor.client.engine.mock.MockRequestHandler = { respond("""{"id":"x","title":"X","status":"drafting","body":""}""", HttpStatusCode.OK, jsonHdr) },
    ): CaptureViewModel =
        CaptureViewModel({ conns }, SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }), scope)

    @Test fun parse_repos_splits_trims_drops_empties() {
        assertEquals(listOf("a", "b"), parseRepos(" a , b , "))
        assertEquals(emptyList<String>(), parseRepos("   "))
    }

    @Test fun no_connections_blocks_create() = runTest {
        val vm = vm(this, emptyList()); vm.load()
        val s = vm.state.value
        assertTrue(s.connections.isEmpty())
        assertNull(s.selectedConnectionId)
        assertFalse(canCreate(s.copy(title = "hi")))   // no connection selected
    }

    @Test fun single_connection_auto_selected() = runTest {
        val vm = vm(this, listOf(conn("1"))); vm.load()
        assertEquals("1", vm.state.value.selectedConnectionId)
    }

    @Test fun multi_connection_requires_explicit_pick() = runTest {
        val vm = vm(this, listOf(conn("1"), conn("2"))); vm.load()
        assertNull(vm.state.value.selectedConnectionId)
    }

    @Test fun blank_title_blocks_create() = runTest {
        val vm = vm(this, listOf(conn("1"))); vm.load()
        assertFalse(canCreate(vm.state.value))                 // title blank
        assertNull(vm.create())                                // no-op
    }

    @Test fun create_success_clears_and_messages() = runTest {
        val vm = vm(this, listOf(conn("1")),
            { respond("""{"id":"my-idea","title":"My idea","status":"drafting","body":""}""", HttpStatusCode.OK, jsonHdr) })
        vm.load(); vm.onTitleChange("My idea"); vm.onReposChange("ground-control")
        vm.create()?.join()
        val s = vm.state.value
        assertEquals("", s.title)
        assertEquals("", s.repos)
        assertEquals(CaptureMessage.Created("my-idea"), s.message)
        assertFalse(s.inFlight)
    }

    @Test fun create_409_surfaces_collision_message() = runTest {
        val vm = vm(this, listOf(conn("1")),
            { respond("""{"detail":"spec 'my-idea' already exists"}""", HttpStatusCode.Conflict, jsonHdr) })
        vm.load(); vm.onTitleChange("My idea")
        vm.create()?.join()
        val m = vm.state.value.message as CaptureMessage.Error
        assertTrue(m.text.contains("already exists"))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.CaptureViewModelTest"`
Expected: FAIL — `CaptureViewModel` unresolved.

- [ ] **Step 3: Write the ViewModel**

Create `ui/capture/CaptureViewModel.kt`:

```kotlin
package com.atomikpanda.groundcontrol.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CaptureMessage {
    data class Created(val specId: String) : CaptureMessage
    data class Error(val text: String) : CaptureMessage
}

data class CaptureUiState(
    val connections: List<WorkspaceConnection> = emptyList(),
    val selectedConnectionId: String? = null,
    val title: String = "",
    val repos: String = "",
    val inFlight: Boolean = false,
    val message: CaptureMessage? = null,
)

/** Auto-select only when there is exactly one connection; otherwise require a pick. */
fun defaultSelection(connections: List<WorkspaceConnection>): String? = connections.singleOrNull()?.id

/** Comma-split the affected-repos field: trim each and drop blanks. */
fun parseRepos(input: String): List<String> =
    input.split(",").map { it.trim() }.filter { it.isNotEmpty() }

fun canCreate(state: CaptureUiState): Boolean =
    state.title.isNotBlank() && state.selectedConnectionId != null && !state.inFlight

class CaptureViewModel(
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val api: SpecApi,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()
    private fun scope() = testScope ?: viewModelScope

    fun load() {
        val conns = connectionsProvider()
        _state.value = _state.value.copy(
            connections = conns,
            selectedConnectionId = _state.value.selectedConnectionId ?: defaultSelection(conns),
        )
    }

    fun onTitleChange(t: String) { _state.value = _state.value.copy(title = t) }
    fun onReposChange(r: String) { _state.value = _state.value.copy(repos = r) }
    fun onSelectConnection(id: String) { _state.value = _state.value.copy(selectedConnectionId = id) }
    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    fun create(): Job? {
        val s = _state.value
        if (!canCreate(s)) return null
        val conn = s.connections.firstOrNull { it.id == s.selectedConnectionId } ?: return null
        _state.value = s.copy(inFlight = true, message = null)
        return scope().launch {
            runCatching { api.createSpec(conn, s.title.trim(), parseRepos(s.repos)) }
                .onSuccess { rec ->
                    _state.value = _state.value.copy(
                        inFlight = false, title = "", repos = "",
                        message = CaptureMessage.Created(rec.id),
                    )
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(inFlight = false, message = CaptureMessage.Error(errorText(t)))
                }
        }
    }

    private fun errorText(t: Throwable): String = when (t) {
        is ApiConflictException -> "A spec named that already exists."
        is AuthException -> "Token rejected — fix this connection in Settings."
        else -> "Couldn't reach the workspace — try again."
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.CaptureViewModelTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/capture/CaptureViewModel.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/CaptureViewModelTest.kt
git commit -m "feat(gc): CaptureViewModel — workspace pick, validation, create"
mship journal "added CaptureViewModel (default-selection, blank-title gate, create success/409); tests green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=3 -->
### Task 3: CaptureScreen + nav swap

Build-verified (no emulator). The VM it drives is covered by Task 2.

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/capture/CaptureScreen.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt`

- [ ] **Step 1: Write the screen**

Create `ui/capture/CaptureScreen.kt`:

```kotlin
package com.atomikpanda.groundcontrol.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CaptureScreen(vm: CaptureViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    if (state.connections.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("Add a workspace in Settings to capture a spec.")
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("New spec", style = MaterialTheme.typography.titleLarge)

        val selected = state.connections.firstOrNull { it.id == state.selectedConnectionId }
        WorkspacePicker(
            label = selected?.let { it.workspaceName.ifBlank { it.baseUrl } } ?: "Select workspace",
            options = state.connections.map { it.id to it.workspaceName.ifBlank { it.baseUrl } },
            onPick = vm::onSelectConnection,
        )

        OutlinedTextField(
            value = state.title, onValueChange = vm::onTitleChange,
            label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.repos, onValueChange = vm::onReposChange,
            label = { Text("Affected repos (comma-separated, optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { vm.create() },
            enabled = canCreate(state),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.inFlight) CircularProgressIndicator(Modifier.padding(end = 8.dp))
            Text("Create")
        }

        when (val m = state.message) {
            is CaptureMessage.Created -> MessageRow("Created ${m.specId} — pull-to-refresh the inbox to see it.") { vm.dismissMessage() }
            is CaptureMessage.Error -> MessageRow(m.text) { vm.dismissMessage() }
            null -> {}
        }
    }
}

@Composable
private fun WorkspacePicker(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Workspace: $label")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

@Composable
private fun MessageRow(text: String, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
```

- [ ] **Step 2: Swap the Capture route**

In `GroundControlApp.kt`: add imports
```kotlin
import com.atomikpanda.groundcontrol.ui.capture.CaptureScreen
import com.atomikpanda.groundcontrol.ui.capture.CaptureViewModel
```
and replace the line
```kotlin
            composable(Section.CAPTURE.route) { PlaceholderScreen("Capture", "C3") }
```
with
```kotlin
            composable(Section.CAPTURE.route) {
                val vm = viewModel {
                    CaptureViewModel(connectionsProvider = { runBlockingSnapshot(connRepo) }, api = api)
                }
                CaptureScreen(vm)
            }
```
(`PlaceholderScreen` is still imported/used by Decisions + Tasks, so leave its import.)

- [ ] **Step 3: Build to verify it compiles**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Adjust any Compose API mismatch minimally against Material3 1.2.1 (mirror idioms in `ui/specdetail/SpecDetailScreen.kt`) without changing behavior.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/capture/CaptureScreen.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt
git commit -m "feat(gc): CaptureScreen + wire it into the Capture tab"
mship journal "added CaptureScreen (workspace picker, title/repos, create) and swapped it into the Capture nav; assembleDebug green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=4 -->
### Task 4: Full verification + phase transition

**Files:** none (verification only).

- [ ] **Step 1: Run the full mship test**

Run: `mship test`
Expected: ground-control green (`assembleDebug` + `testDebugUnitTest`, all classes incl. SpecApi + Capture). Fix and re-run if red.

- [ ] **Step 2: Confirm acceptance criteria**

Re-read `specs/2026-06-22-ground-control-capture.md` and confirm each AC against the implementation (the form/selector/validation/create/error behaviors are exercised by the screen + Task 1/2 tests; the inbox-Drafting appearance follows from `POST /specs` returning a spec the inbox already groups). Note any gap in the journal.

- [ ] **Step 3: Journal + transition**

```bash
mship journal "quick-capture implemented; full app build + unit tests green" --action completed --test-state pass
mship phase review
```

> Then `mship finish --body-file <path>` with a real Summary + Test plan when ready to open the PR.
<!-- /mship:task -->

---

## Non-goals (from the spec)

Voice input; the `draft → agent → apply` authoring flow; body editing; auto-navigating to the new spec; any mothership changes; iOS; Compose/instrumentation tests.
