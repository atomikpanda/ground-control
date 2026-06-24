# Ground Control Terminal Design System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `ground-control-design-system` (mship spec, dispatched → this task). Slice 3 of the Ground Control UX overhaul; slices 1 & 2 are merged.

**Goal:** Replace the stock Material3 theme with a deliberate terminal-style design system — Dracula accent hues on near-black (dark) + contrast-tuned light, JetBrains Mono for technical tokens, small corners / tight density — and apply semantic neon color so the "Needs you" queue triages at a glance.

**Architecture:** A pure, JVM-testable semantic-color core (`UrgencyTier` → accent `Color`, palettes as constants) plus a bundled font and custom `MaterialTheme` wiring (color/typography/shapes), then mechanical application on the list screens. No ViewModel/repo/logic changes; no mothership change.

**Tech Stack:** Kotlin, Jetpack Compose, Material3 1.2.1 (BOM 2024.06.00). Tests: JUnit4 (JVM only, no emulator) — the testable surface is the pure color mapping; theme wiring + application is compile/smoke-verified.

---

## Prerequisites

```bash
source ~/toolchains/android-env.sh
test -f android/local.properties || (cd android && printf "sdk.dir=%s\n" "$ANDROID_HOME" > local.properties)
```

## Palette (single source of truth)

```
 role        DARK (Dracula on near-black)   LIGHT (contrast-tuned)
 background  #0A0E14                          #F8FAFC
 surface     #12161F                          #FFFFFF
 elevated    #1A1F2B                          #F1F5F9
 divider     #2A2F3C                          #E2E8F0
 text        #F8F8F2                          #1E2230
 muted       #6272A4                          #64748B
 approval    #50FA7B (green)                  #16A34A
 question    #8BE9FD (cyan, = primary)        #0E7490
 blocker     #FFB86C (orange)                 #B45309
 error       #FF5555 (red)                    #DC2626
 chip hues   #FF79C6 #BD93F9 #8BE9FD #50FA7B  #DB2777 #7C3AED #0E7490 #16A34A
```

## File Structure

| File | Responsibility |
|---|---|
| `ui/theme/Color.kt` (create) | Dark+light palette constants, `SemanticColors` + `SemanticDark`/`SemanticLight`, pure `accentFor(tier)` + `chipHue(id)`. |
| `ui/theme/Type.kt` (create) | JetBrains Mono `FontFamily`, app `Typography`, a `MonoStyle` `TextStyle` for technical tokens. |
| `ui/theme/Shape.kt` (create) | Small-radius `Shapes`. |
| `ui/theme/Theme.kt` (modify) | Wire custom light/dark `ColorScheme` + typography + shapes; provide `LocalSemanticColors`. |
| `res/font/jetbrains_mono_*.ttf` (add) | Bundled font (OFL). |
| `ui/home/HomeScreen.kt` (modify) | Semantic accents per item kind, chip hues, error color, mono tokens. |
| `ui/tasks/TasksScreen.kt`, `ui/specdetail/SpecDetailScreen.kt`, `ui/messages/ConversationScreen.kt` (modify) | Mono on technical identifiers. |

---

<!-- mship:task id=1 -->
### Task 1: Semantic color core (palettes + accentFor) — pure & tested

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Color.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SemanticColorsTest.kt`

Pure Kotlin over `androidx.compose.ui.graphics.Color` (a value class — JVM-testable). Keyed on `UrgencyTier` (already defined in `ui/home/NeedsYouItem.kt`: `BLOCKER`, `QUESTION`, `APPROVAL`).

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/atomikpanda/groundcontrol/SemanticColorsTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.home.UrgencyTier
import com.atomikpanda.groundcontrol.ui.theme.SemanticDark
import com.atomikpanda.groundcontrol.ui.theme.SemanticLight
import com.atomikpanda.groundcontrol.ui.theme.accentFor
import com.atomikpanda.groundcontrol.ui.theme.chipHue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticColorsTest {
    @Test fun accent_maps_each_kind_dark() {
        assertEquals(SemanticDark.blocker, accentFor(UrgencyTier.BLOCKER, SemanticDark))
        assertEquals(SemanticDark.question, accentFor(UrgencyTier.QUESTION, SemanticDark))
        assertEquals(SemanticDark.approval, accentFor(UrgencyTier.APPROVAL, SemanticDark))
    }

    @Test fun accent_maps_each_kind_light() {
        assertEquals(SemanticLight.blocker, accentFor(UrgencyTier.BLOCKER, SemanticLight))
        assertEquals(SemanticLight.question, accentFor(UrgencyTier.QUESTION, SemanticLight))
        assertEquals(SemanticLight.approval, accentFor(UrgencyTier.APPROVAL, SemanticLight))
    }

    @Test fun roles_differ_between_dark_and_light() {
        assertNotEquals(SemanticDark.approval, SemanticLight.approval)
        assertNotEquals(SemanticDark.blocker, SemanticLight.blocker)
        assertNotEquals(SemanticDark.error, SemanticLight.error)
    }

    @Test fun chip_hue_is_stable_and_in_palette() {
        assertEquals(chipHue("conn-a", SemanticDark), chipHue("conn-a", SemanticDark))
        assertTrue(SemanticDark.chipHues.contains(chipHue("conn-a", SemanticDark)))
        // distinct ids generally land on different hues (not guaranteed, but the set is used)
        assertTrue(SemanticDark.chipHues.size >= 4)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SemanticColorsTest"`
Expected: FAIL — unresolved `SemanticDark`/`accentFor`/`chipHue`.

> If this test fails to *compile/run* on the JVM with a "Method ... not mocked" error referencing `Color`, switch the assertions to compare `Color.value` (the packed `ULong`) — e.g. `assertEquals(SemanticDark.blocker.value, accentFor(...).value)`. In practice `androidx.compose.ui.graphics.Color` construction and equality are pure and run under `testDebugUnitTest`, so the above should work as-is.

- [ ] **Step 3: Write the implementation**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Color.kt
package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.ui.graphics.Color
import com.atomikpanda.groundcontrol.ui.home.UrgencyTier

/** Dracula accents on near-black (dark) + contrast-tuned (light). Single source of truth. */
object Palette {
    // dark
    val darkBackground = Color(0xFF0A0E14)
    val darkSurface = Color(0xFF12161F)
    val darkElevated = Color(0xFF1A1F2B)
    val darkDivider = Color(0xFF2A2F3C)
    val darkText = Color(0xFFF8F8F2)
    val darkMuted = Color(0xFF6272A4)
    val darkApproval = Color(0xFF50FA7B)
    val darkQuestion = Color(0xFF8BE9FD)
    val darkBlocker = Color(0xFFFFB86C)
    val darkError = Color(0xFFFF5555)
    val darkChips = listOf(Color(0xFFFF79C6), Color(0xFFBD93F9), Color(0xFF8BE9FD), Color(0xFF50FA7B))
    // light
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightElevated = Color(0xFFF1F5F9)
    val lightDivider = Color(0xFFE2E8F0)
    val lightText = Color(0xFF1E2230)
    val lightMuted = Color(0xFF64748B)
    val lightApproval = Color(0xFF16A34A)
    val lightQuestion = Color(0xFF0E7490)
    val lightBlocker = Color(0xFFB45309)
    val lightError = Color(0xFFDC2626)
    val lightChips = listOf(Color(0xFFDB2777), Color(0xFF7C3AED), Color(0xFF0E7490), Color(0xFF16A34A))
}

/** Semantic accent roles for one scheme. */
data class SemanticColors(
    val approval: Color,
    val question: Color,
    val blocker: Color,
    val error: Color,
    val muted: Color,
    val chipHues: List<Color>,
)

val SemanticDark = SemanticColors(
    approval = Palette.darkApproval, question = Palette.darkQuestion,
    blocker = Palette.darkBlocker, error = Palette.darkError,
    muted = Palette.darkMuted, chipHues = Palette.darkChips,
)
val SemanticLight = SemanticColors(
    approval = Palette.lightApproval, question = Palette.lightQuestion,
    blocker = Palette.lightBlocker, error = Palette.lightError,
    muted = Palette.lightMuted, chipHues = Palette.lightChips,
)

/** Pure: an item's urgency tier → its semantic accent. */
fun accentFor(tier: UrgencyTier, colors: SemanticColors): Color = when (tier) {
    UrgencyTier.BLOCKER -> colors.blocker
    UrgencyTier.QUESTION -> colors.question
    UrgencyTier.APPROVAL -> colors.approval
}

/** Stable per-workspace chip hue: same connection id → same hue across sessions. */
fun chipHue(connectionId: String, colors: SemanticColors): Color =
    colors.chipHues[(connectionId.hashCode() and Int.MAX_VALUE) % colors.chipHues.size]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SemanticColorsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Color.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/SemanticColorsTest.kt
git commit -m "feat(theme): semantic color palettes + accentFor/chipHue (pure, tested)"
mship journal "design-system: palettes + accentFor/chipHue; 4 tests passing" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=2 -->
### Task 2: Bundle JetBrains Mono + Type/Shape + Theme wiring

**Files:**
- Add: `android/app/src/main/res/font/jetbrains_mono_regular.ttf`, `jetbrains_mono_medium.ttf`, and the OFL license at `android/app/src/main/res/font/LICENSE_jetbrains_mono.txt` is INVALID (res/font allows only fonts) → put the license at `android/THIRD_PARTY_LICENSES/JetBrainsMono-OFL.txt`.
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Type.kt`, `ui/theme/Shape.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Theme.kt`

UI/theme — verify by compile + the existing suite staying green.

- [ ] **Step 1: Download the font + license**

Run (internet is available):
```bash
mkdir -p android/app/src/main/res/font android/THIRD_PARTY_LICENSES
curl -sSL -o android/app/src/main/res/font/jetbrains_mono_regular.ttf \
  "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Regular.ttf"
curl -sSL -o android/app/src/main/res/font/jetbrains_mono_medium.ttf \
  "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Medium.ttf"
curl -sSL -o android/THIRD_PARTY_LICENSES/JetBrainsMono-OFL.txt \
  "https://github.com/JetBrains/JetBrainsMono/raw/master/OFL.txt"
# sanity: both ttf files are non-empty and start with the TrueType magic
ls -l android/app/src/main/res/font/*.ttf
```
Expected: two `.ttf` files (~200KB+ each) and a non-empty OFL.txt. (res/font filenames must be lowercase with underscores — they are.)

- [ ] **Step 2: Create `Type.kt`**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Type.kt
package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.atomikpanda.groundcontrol.R

/** Bundled JetBrains Mono for technical tokens (ids, slugs, branches, counts). */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** Prose typography — system sans default scale (tuned later if needed). */
val AppTypography = Typography()

/** Apply to identifier Text composables: `style = MonoStyle` or `fontFamily = JetBrainsMono`. */
val MonoStyle: TextStyle = AppTypography.bodySmall.copy(fontFamily = JetBrainsMono)
```

- [ ] **Step 3: Create `Shape.kt`**

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Shape.kt
package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Small radii — an instrument/terminal feel. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
)
```

- [ ] **Step 4: Wire `Theme.kt`**

Replace `Theme.kt` with custom schemes + typography + shapes, and a `LocalSemanticColors` so screens read accents without hard-coding:

```kotlin
// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Theme.kt
package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val DarkScheme = darkColorScheme(
    primary = Palette.darkQuestion,
    onPrimary = Palette.darkBackground,
    background = Palette.darkBackground,
    onBackground = Palette.darkText,
    surface = Palette.darkSurface,
    onSurface = Palette.darkText,
    surfaceVariant = Palette.darkElevated,
    onSurfaceVariant = Palette.darkMuted,
    outline = Palette.darkDivider,
    error = Palette.darkError,
)

private val LightScheme = lightColorScheme(
    primary = Palette.lightQuestion,
    onPrimary = Palette.lightSurface,
    background = Palette.lightBackground,
    onBackground = Palette.lightText,
    surface = Palette.lightSurface,
    onSurface = Palette.lightText,
    surfaceVariant = Palette.lightElevated,
    onSurfaceVariant = Palette.lightMuted,
    outline = Palette.lightDivider,
    error = Palette.lightError,
)

/** Semantic accent roles for the active scheme; read via `LocalSemanticColors.current`. */
val LocalSemanticColors = staticCompositionLocalOf { SemanticDark }

@Composable
fun GroundControlTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSemanticColors provides if (dark) SemanticDark else SemanticLight) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
```

- [ ] **Step 5: Build + suite**

Run: `cd android && ./gradlew compileDebugKotlin && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL; suite green (the font resources resolve `R.font.*`; the app now renders the custom theme).

- [ ] **Step 6: Commit + journal**

```bash
git add android/app/src/main/res/font android/THIRD_PARTY_LICENSES \
        android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Type.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Shape.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Theme.kt
git commit -m "feat(theme): bundle JetBrains Mono; custom light/dark scheme + typography + shapes"
mship journal "design-system: font + Type/Shape + Theme wiring (LocalSemanticColors); compile + suite green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=3 -->
### Task 3: Apply the system on Home

**Files:**
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeScreen.kt`

Semantic accents per item kind, per-workspace chip hues, error color, mono tokens. Compile + smoke verified (composables aren't unit-tested here).

- [ ] **Step 1: Color the NeedsYouRow leading per kind + mono workspace tag**

Read `LocalSemanticColors.current` at the top of `NeedsYouRow`, replace the plain emoji leading with a leading `Icon` (or keep the emoji but add a tinted indicator) tinted via `accentFor(item.tier, colors)`, and render the workspace `overlineContent` in the chip hue + mono. Concretely, change `NeedsYouRow`:

```kotlin
@Composable
private fun NeedsYouRow(
    item: NeedsYouItem,
    onApproval: (String, String) -> Unit,
    onQuestion: (String, String) -> Unit,
    onBlocker: (String, String) -> Unit,
) {
    val colors = LocalSemanticColors.current
    val accent = accentFor(item.tier, colors)
    val (icon, title, supporting, onClick) = when (item) {
        is NeedsYouItem.Blocker -> RowSpec(Icons.Filled.Block, "Blocked: ${item.taskSlug}", item.reason) {
            onBlocker(item.connectionId, item.taskSlug)
        }
        is NeedsYouItem.Question -> RowSpec(Icons.AutoMirrored.Filled.Chat, item.subject, item.lastMessage) {
            onQuestion(item.connectionId, item.threadId)
        }
        is NeedsYouItem.Approval -> RowSpec(Icons.Filled.CheckCircle, item.title, "ready to review") {
            onApproval(item.connectionId, item.specId)
        }
    }
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = accent) },
        overlineContent = { Text(item.workspaceName, style = MonoStyle, color = chipHue(item.connectionId, colors)) },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        modifier = Modifier.clickable { onClick() },
    )
}

private data class RowSpec(val a: androidx.compose.ui.graphics.vector.ImageVector, val b: String, val c: String, val d: () -> Unit)
```

Replace the old `private data class Quad(...)` with `RowSpec`. Add imports: `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.Block`, `androidx.compose.material.icons.filled.CheckCircle`, `androidx.compose.material.icons.automirrored.filled.Chat`, `androidx.compose.material3.Icon`, `com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors`, `com.atomikpanda.groundcontrol.ui.theme.MonoStyle`, `com.atomikpanda.groundcontrol.ui.theme.accentFor`, `com.atomikpanda.groundcontrol.ui.theme.chipHue`.

- [ ] **Step 2: Error chip uses the error color; rail chip count in mono**

In the error `AssistChip`, tint the label with `LocalSemanticColors.current.error`:
```kotlin
items(s.errors, key = { "err:${it.connectionId}" }) { err ->
    val colors = LocalSemanticColors.current
    AssistChip(
        onClick = {},
        label = { Text("${err.workspaceName} unreachable", color = colors.error) },
        modifier = Modifier.padding(12.dp, 4.dp),
    )
}
```
In the rail `FilterChip` label, render the `· count` portion in `MonoStyle` (keep the workspace name in the default style). Minimal: wrap the count using `Text(..., style = MonoStyle)` if you split it, or apply `MonoStyle` to the whole chip label — either is acceptable; keep it readable.

- [ ] **Step 3: Build + suite**

Run: `cd android && ./gradlew compileDebugKotlin && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL; suite green.

- [ ] **Step 4: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/HomeScreen.kt
git commit -m "feat(home): semantic neon accents per item kind + chip hues + mono tokens"
mship journal "design-system: applied semantic accents/chip hues/mono on Home" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=4 -->
### Task 4: Mono on technical tokens — Tasks / Spec detail / Conversation

**Files:**
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/tasks/TasksScreen.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/SpecDetailScreen.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/messages/ConversationScreen.kt`

Apply `MonoStyle` (or `fontFamily = JetBrainsMono`) to the `Text` composables that render technical identifiers: task slugs, branch names, spec ids, PR/repo names, thread ids/titles that are ids. Compile + smoke verified.

- [ ] **Step 1: Apply mono to identifiers**

In each screen, import `com.atomikpanda.groundcontrol.ui.theme.MonoStyle` and set `style = MonoStyle` on the `Text`s that display identifiers (e.g. in `TasksScreen` the task `slug` and `branch`; in `SpecDetailScreen` the spec id / criterion ids if shown; in `ConversationScreen` the thread id used as the title). Leave prose (descriptions, messages, titles meant for humans) in the default style. Do not change any layout or logic — only `style`/`fontFamily` on identifier `Text`s.

Read each file first to find the exact identifier `Text`s; apply `MonoStyle` to those only. If a screen has no clear identifier text, leave it unchanged and note that in the report.

- [ ] **Step 2: Build + suite**

Run: `cd android && ./gradlew compileDebugKotlin && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL; suite green.

- [ ] **Step 3: Commit + journal**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/tasks/TasksScreen.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/SpecDetailScreen.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/ui/messages/ConversationScreen.kt
git commit -m "feat(theme): mono technical tokens on Tasks/SpecDetail/Conversation"
mship journal "design-system: mono identifiers across list screens" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=5 -->
### Task 5: Verification + evidence

**Files:** none.

- [ ] **Step 1: Full unit suite**

Run: `mship test --repos ground-control`
Expected: all unit tests PASS (incl. new `SemanticColorsTest`; pre-existing suite green).

- [ ] **Step 2: Assemble**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (font resources packaged; theme compiles).

- [ ] **Step 3: Manual smoke (needs a running workspace / device)**

Verify in both light and dark system settings: near-black (dark) / near-white (light) surfaces; the "Needs you" rows show green/cyan/orange leading icons for approval/question/blocker; workspace tags are colored + monospace; identifiers render in JetBrains Mono; the unreachable chip is red; corners are tight. `mship capture --repo ground-control --platform android` if a device is attached.

- [ ] **Step 4: Journal + review phase**

```bash
mship journal "slice 3 (design system) complete; suite green; assembleDebug OK" --action verified --test-state pass
mship phase review
```
<!-- /mship:task -->

---

## Self-Review

**Spec coverage** (AC → task):
- AC1 (custom dark+light schemes, system-selected) → Task 2 (`DarkScheme`/`LightScheme` via `isSystemInDarkTheme()`).
- AC2 (semantic roles as named constants, both modes) → Task 1 (`Palette` + `SemanticDark`/`SemanticLight`).
- AC3 (pure kind→accent mapping, JVM-tested both modes) → Task 1 (`accentFor` + `SemanticColorsTest`).
- AC4 (JetBrains Mono bundled w/ OFL; technical tokens in mono) → Task 2 (font + `MonoStyle`) applied in Tasks 3–4.
- AC5 (custom Shapes + Typography via MaterialTheme) → Task 2 (`AppShapes`/`AppTypography` wired).
- AC6 (Home rows show kind accents; chips distinguishable; unreachable uses error color) → Task 3.
- AC7 (JVM tests cover mapping + roles; visual by compile/assemble/smoke) → Task 1 tests + Tasks 2–5 compile/assemble/smoke.

**Known notes (documented, not silent):** Compose `Color` is asserted directly under `testDebugUnitTest` (value class); Task 1 Step 2 gives the `.value` fallback if the JVM stub balks. Only the color mapping is unit-tested — theme wiring + per-screen application are inherently visual (compile + smoke), consistent with slices 1–2.

**Placeholder scan:** none — concrete code or exact commands in every step (Task 4 instructs reading each screen to locate identifier `Text`s, then applying `MonoStyle` only — a bounded, described action, not a placeholder).

**Type consistency:** `SemanticColors`/`SemanticDark`/`SemanticLight`/`accentFor`/`chipHue` (Task 1) are consumed in Task 2 (`LocalSemanticColors`) and Task 3 (`accentFor(item.tier, colors)`, `chipHue(...)`). `MonoStyle`/`JetBrainsMono` (Task 2) used in Tasks 3–4. `UrgencyTier` referenced matches `ui/home/NeedsYouItem.kt`. `R.font.jetbrains_mono_regular`/`_medium` match the downloaded filenames in Task 2 Step 1.
