# Ground Control Home Capture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `ground-control-home-capture` (mship spec, dispatched → this task). Slice 2 of the Ground Control UX overhaul; slice 1 (`ground-control-ia-overhaul`) is merged into `main`.

**Goal:** Add a single "Capture" entry to the Home screen — a `+` FAB → a capture screen (workspace picker + message → Send) that creates a thread and drops the user into its conversation, where the host agent triages it (quick fix, or shape into a spec via `from-thread`).

**Architecture:** Pure reuse + wiring. Capture rides the existing thread machinery: the FAB navigates to a new `capture` route that renders the existing `NewThreadScreen` (parameterized with capture-oriented copy) backed by the existing `NewThreadViewModel`. On create it navigates into the existing `ConversationScreen` (which carries the shipped "Make this a spec"). No new ViewModel, no new thread-creation path, no mothership change.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Navigation-Compose. Tests: JUnit4 + kotlinx-coroutines-test (no emulator) — but note slice 2 adds **no new unit-testable logic** (it reuses `NewThreadViewModel`, already covered by `NewThreadViewModelTest`); new code is UI/nav, verified by compile + the existing suite staying green.

---

## Prerequisites

Run once in the worktree (`.worktrees/ground-control-home-capture/ground-control`):

```bash
source ~/toolchains/android-env.sh
test -f android/local.properties || (cd android && printf "sdk.dir=%s\n" "$ANDROID_HOME" > local.properties)
```

## File Structure

| File | Responsibility |
|---|---|
| `ui/messages/NewThreadScreen.kt` (modify) | Add capture-oriented copy params (`title`, `showSubject`, `bodyLabel`, `submitLabel`) with defaults that preserve current behavior. |
| `GroundControlApp.kt` (modify) | Register a `capture` route rendering `NewThreadScreen` with capture copy; add `onCapture` to the Home block wired to it. |
| `ui/home/HomeScreen.kt` (modify) | Wrap content in a `Scaffold` and add the Capture FAB + an `onCapture` callback param. |

`NewThreadViewModel` (create logic + workspace-selection rules) and `ConversationScreen` (+ "Make this a spec") are reused unchanged.

---

<!-- mship:task id=1 -->
### Task 1: Parameterize NewThreadScreen for capture + register the `capture` route

**Files:**
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/messages/NewThreadScreen.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt`

No test: this is UI/nav over the already-tested `NewThreadViewModel`. Verify by compile; the existing `NewThreadViewModelTest` must stay green (the new params default to current behavior).

- [ ] **Step 1: Add capture copy params to `NewThreadScreen`**

Change the function signature (add four trailing defaulted params) and use them. Current signature ends `..., initialConnectionId: String? = null,`. New:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewThreadScreen(
    vm: NewThreadViewModel,
    onCreated: (connectionId: String, threadId: String) -> Unit,
    onBack: () -> Unit,
    initialConnectionId: String? = null,
    title: String = "New thread",
    showSubject: Boolean = true,
    bodyLabel: String = "Message",
    submitLabel: String = "Create",
) {
```

Then, inside the composable, replace these three hardcoded strings/blocks:

1. The `TopAppBar` title — change `title = { Text("New thread") }` to `title = { Text(title) }`.

2. Wrap the subject field in `if (showSubject)`:

```kotlin
            if (showSubject) {
                OutlinedTextField(
                    value = state.subject,
                    onValueChange = vm::onSubjectChange,
                    label = { Text("Subject (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
```

3. The body field label — change `label = { Text("Message") }` to `label = { Text(bodyLabel) }`.

4. The submit button text — change the `else` branch `Text("Create")` to `Text(submitLabel)` (leave the `CircularProgressIndicator` in-flight branch as is).

- [ ] **Step 2: Verify the screen still compiles with existing callers**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. The `newThread` route and the workspace "New conversation" path still pass defaults → unchanged "New thread" UI.

- [ ] **Step 3: Register the `capture` route in `GroundControlApp.kt`**

Add this composable next to the `newThread` route (model it on the existing `newThread` block). It is unscoped — no `connectionId` arg — so the workspace picker shows when there is more than one connection:

```kotlin
            composable("capture") {
                val vm = viewModel {
                    NewThreadViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                NewThreadScreen(
                    vm,
                    title = "Capture",
                    showSubject = false,
                    bodyLabel = "What's up?",
                    submitLabel = "Send",
                    onCreated = { connId, id ->
                        nav.navigate("thread/$connId/$id") {
                            popUpTo("capture") { inclusive = true }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
```

(`NewThreadViewModel`, `NewThreadScreen`, `threadsRepo`, `runBlockingSnapshot`, `viewModel`, `connRepo` are all already imported/in scope from the existing `newThread` route.)

- [ ] **Step 4: Build + run the existing suite**

Run: `cd android && ./gradlew compileDebugKotlin` then `mship test --repos ground-control`
Expected: BUILD SUCCESSFUL; full unit suite still green (no behavior change to existing tests).

- [ ] **Step 5: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/messages/NewThreadScreen.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt
git commit -m "feat(capture): capture-copy params on NewThreadScreen + capture route"
mship journal "added capture route reusing NewThreadScreen/VM with capture copy; suite green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=2 -->
### Task 2: Capture FAB on Home

**Files:**
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeScreen.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt`

UI/nav — verify by compile. The FAB navigates to the `capture` route registered in Task 1.

- [ ] **Step 1: Wrap `HomeScreen` in a Scaffold and add the FAB**

`HomeScreen` currently returns the `when (val s = state)` directly. Add an `onCapture` param and wrap the `when` in a `Scaffold` whose `floatingActionButton` is the Capture FAB; pass `innerPadding` down to each branch's root modifier.

Add these imports:
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
```

Change the signature to add the param:
```kotlin
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onApproval: (connectionId: String, specId: String) -> Unit,
    onQuestion: (connectionId: String, threadId: String) -> Unit,
    onBlocker: (connectionId: String, slug: String) -> Unit,
    onBrowseWorkspace: (connectionId: String) -> Unit,
    onCapture: () -> Unit,
) {
```

Replace the body (`val state by ...; LaunchedEffect ...; when (val s = state) { ... }`) so the `when` lives inside a `Scaffold`:

```kotlin
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCapture,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Capture") },
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            is HomeUiState.Loading -> Box(Modifier.fillMaxSize().padding(innerPadding), Alignment.Center) {
                CircularProgressIndicator()
            }
            is HomeUiState.EmptyConfig -> Box(Modifier.fillMaxSize().padding(innerPadding), Alignment.Center) {
                Text("Add a workspace in Settings to get started.")
            }
            is HomeUiState.Content -> LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                // ... existing rail / errors / browse / empty / queue items unchanged ...
            }
        }
    }
```

Keep the entire existing `LazyColumn` body (rail, error chips, "Browse all in this workspace →", empty state, `NeedsYouRow` items) exactly as-is — only its root `Modifier` gains `.padding(innerPadding)` and it now sits inside the `Scaffold` content lambda.

- [ ] **Step 2: Wire `onCapture` in the Home block of `GroundControlApp.kt`**

In the `composable(Section.HOME.route)` block, add `onCapture` to the `HomeScreen(...)` call:

```kotlin
                HomeScreen(
                    vm,
                    onApproval = { connId, specId -> nav.navigate("specDetail/$connId/$specId") },
                    onQuestion = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onBlocker = { connId, slug -> nav.navigate("taskDetail/$connId/$slug") },
                    onBrowseWorkspace = { connId -> nav.navigate("workspace/$connId") },
                    onCapture = { nav.navigate("capture") },
                )
```

- [ ] **Step 3: Build + run the suite**

Run: `cd android && ./gradlew compileDebugKotlin` then `mship test --repos ground-control`
Expected: BUILD SUCCESSFUL; suite still green. Home now shows a "Capture" FAB; tapping it opens the capture screen.

- [ ] **Step 4: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeScreen.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt
git commit -m "feat(home): Capture FAB → capture flow"
mship journal "Home Capture FAB wired to capture route; compile + suite green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=3 -->
### Task 3: Verification + evidence

**Files:** none (verification only).

- [ ] **Step 1: Full unit suite via mship (records evidence for finish)**

Run: `mship test --repos ground-control`
Expected: all unit tests PASS (no new tests; existing `NewThreadViewModelTest` and the slice-1 suite still green).

- [ ] **Step 2: Assemble the debug APK**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke (needs ≥1 reachable workspace)**

Launch the app: Home shows a **Capture** FAB. Tap it → capture screen (workspace picker when >1 connection; body field labeled "What's up?"; **Send** button; no subject field; titled "Capture"). Type a note, Send → lands in the **Conversation** for the new thread, which shows the "Make this a spec" affordance. (Capture a screenshot with `mship capture --repo ground-control --platform android` if a device/emulator is attached.)

- [ ] **Step 4: Journal complete + move to review**

```bash
mship journal "slice 2 (Home capture) complete; suite green; assembleDebug OK" --action verified --test-state pass
mship phase review
```
<!-- /mship:task -->

---

## Self-Review

**Spec coverage** (acceptance criterion → task):
- AC1 (Capture FAB on Home; not Tasks/Settings) → Task 2 (FAB is on `HomeScreen` only).
- AC2 (workspace picker: auto-select one / dropdown several / empty-state none) → reused from `NewThreadScreen`/`NewThreadViewModel`, exercised via the `capture` route (Task 1); already covered by `NewThreadViewModelTest`.
- AC3 (non-empty message + Send → creates thread, navigates into Conversation) → Task 1 (`capture` route `onCreated` → `thread/...`); create path is `NewThreadViewModel.create()`.
- AC4 (reuse creation logic; no duplicate path) → Task 1 (capture renders the same `NewThreadScreen`/`NewThreadViewModel`; no new thread-creation code).
- AC5 (Conversation exposes "Make this a spec") → reused `ConversationScreen` (shipped); reached by Task 1's `onCreated` navigation.
- AC6 (reads as "Capture") → Task 1 (`title = "Capture"`, `submitLabel = "Send"`, `bodyLabel = "What's up?"`).
- AC7 (JVM tests cover create-then-navigate + workspace-selection, reusing NewThreadViewModel coverage; nav by compile) → satisfied by existing `NewThreadViewModelTest` (create_success_exposes_created_thread_id[_no_subject], single_connection_auto_selected, multi_connection_requires_explicit_pick, blank_text_blocks_create, can_create_requires_connection_and_non_blank_text); nav/UI verified by `compileDebugKotlin` (Tasks 1–2) + smoke (Task 3).

**Known note (documented, not silent):** slice 2 introduces no new unit tests because it adds no new logic — it is UI/nav reuse over the already-tested `NewThreadViewModel`. AC7 is met by that existing coverage plus compile/smoke, consistent with how slice 1 handled UI-only changes.

**Placeholder scan:** none — every step has concrete code or an exact command.

**Type consistency:** `NewThreadScreen`'s new params (`title`/`showSubject`/`bodyLabel`/`submitLabel`) are referenced consistently in Task 1's screen edits and the Task 1 `capture` route. `HomeScreen`'s new `onCapture` param (Task 2) matches the `onCapture = { nav.navigate("capture") }` wiring. The `capture` route string matches between the FAB navigation (Task 2) and the route registration + `popUpTo("capture")` (Task 1).
