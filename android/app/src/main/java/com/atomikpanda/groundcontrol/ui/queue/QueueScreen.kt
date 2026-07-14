// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueScreen.kt
package com.atomikpanda.groundcontrol.ui.queue

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.WorkspaceError
import com.atomikpanda.groundcontrol.ui.messages.DecisionCard as DecisionPromptCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// Fling tuning: how far/fast a drag must go to count as a throw, the tilt at full
// width, and how far off-screen an approved card is thrown.
private const val FLING_DISTANCE_FRACTION = 0.30f  // of card width
private const val FLING_VELOCITY_THRESHOLD = 1200f // px/s
private const val FLING_MAX_TILT_DEG = 10f
private const val FLING_THROW_FACTOR = 1.6f        // × card width

// Queue v2 card stack. The head card is acted on by a directional fling — right
// throws a Prose/Criteria card off-screen and approve-alls it (auto-approving its
// spec when that makes it approvable); left opens a reject comment sheet
// (request-changes on the whole spec) and springs the card back. Inside a card,
// per-item Check/Flag toggles (criteria) and answer fields (questions) edit in
// place without advancing. Long content scrolls inside the card; Skip is pinned in
// a footer so it's always reachable. All transitions live in the tested QueueViewModel.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    vm: QueueViewModel,
    onOpenItem: (connectionId: String, itemId: String) -> Unit,
    onOpenPr: (url: String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // AC9: initial load + live auto-refresh. The poll is cancelled when the tab
    // leaves composition (correct — we only refresh while the Queue is on screen).
    LaunchedEffect(Unit) {
        vm.refresh()
        while (true) {
            delay(15_000)
            vm.refresh()
        }
    }

    val snackbar = remember { SnackbarHostState() }
    var rejectSheet by remember { mutableStateOf(false) }

    // Surface a failed inline action (approve/reject/answer/decision) — the card is NOT dismissed on failure.
    val actionError = (state as? QueueUiState.Content)?.actionError
    LaunchedEffect(actionError) { if (actionError != null) snackbar.showSnackbar(actionError) }

    // Reactive undo snackbar: only shows once an action has actually armed undo (dropped exactly one
    // card) — never optimistically at the call site, so a failed action can't flash a false "Approved".
    // Keyed to the armed card so each new undo re-triggers; a null undo (approve success / skip / undone)
    // shows nothing. Decision cards report "Sent", chunk approvals "Approved".
    val undo = (state as? QueueUiState.Content)?.undo
    LaunchedEffect(undo?.key) {
        if (undo != null) {
            val label = if (undo is DecisionCard) "Sent" else "Approved"
            val r = snackbar.showSnackbar(label, actionLabel = "Undo")
            if (r == SnackbarResult.ActionPerformed) vm.undo()
        }
    }

    // Approve-all the head (shared by fling-right and the Prose Check toggle). The undo snackbar is
    // driven reactively above — do NOT show one here.
    val approveAll: () -> Unit = { vm.approveAllCurrent() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val s = state) {
                QueueUiState.Loading -> CircularProgressIndicator()
                QueueUiState.EmptyConfig -> Text("No workspaces connected. Add one in Settings.")
                is QueueUiState.Content -> {
                    val card = s.current
                    if (card == null) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (s.errors.isNotEmpty()) { WorkspaceErrorLine(s.errors); Spacer(Modifier.height(8.dp)) }
                            Text("You're all caught up ✓", textAlign = TextAlign.Center)
                        }
                    } else {
                        // Fixed header + weighted (bounded) card region + pinned Skip footer, so long
                        // card content scrolls inside the card and Skip is never pushed off-screen.
                        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (s.errors.isNotEmpty()) { WorkspaceErrorLine(s.errors); Spacer(Modifier.height(8.dp)) }
                            Text("${s.position} of ${s.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                // Keyed to the card so each head gets a fresh fling offset + scroll state.
                                key(card.key) {
                                    val canFlingRight = card is ProseCard || card is CriteriaCard  // right = approve-all
                                    val canFlingLeft = card !is DecisionCard                         // left = reject sheet
                                    FlingCard(
                                        cardKey = card.key,
                                        canFlingRight = canFlingRight,
                                        canFlingLeft = canFlingLeft,
                                        enabled = !s.inFlight,
                                        // Fling-right approves + awaits the result; returns true if the head
                                        // advanced (card left) so the thrown card isn't sprung back. A failed
                                        // or no-op approve returns false → the card flies back to center.
                                        onFlingRight = {
                                            vm.approveAllCurrent()?.join()
                                            (vm.state.value as? QueueUiState.Content)?.current?.key != card.key
                                        },
                                        onFlingLeft = { rejectSheet = true },  // open sheet; card stays until it sends
                                    ) {
                                        CardFace(
                                            card = card,
                                            vm = vm,
                                            enabled = !s.inFlight,
                                            onApproveAll = approveAll,
                                            onReject = { rejectSheet = true },
                                            onOption = { text -> vm.answerDecision(text) },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { vm.skip() }, enabled = !s.inFlight, modifier = Modifier.fillMaxWidth()) { Text("Skip") }
                        }
                    }
                }
            }
        }
    }

    if (rejectSheet) {
        RejectSheet(
            onDismiss = { rejectSheet = false },
            onSend = { reason -> vm.rejectCurrent(reason) },
        )
    }
}

/**
 * A Tinder-style fling wrapper for the head card. During a horizontal drag the card follows the
 * finger and tilts ([FLING_MAX_TILT_DEG] at full width). On release past a distance or velocity
 * threshold it either throws off-screen right (approve — [onFlingRight] runs and, if the card didn't
 * actually leave, it springs back) or springs back while opening the reject sheet left ([onFlingLeft]);
 * otherwise it springs back. Vertical scroll of the content underneath is unaffected (orthogonal axis).
 */
@Composable
private fun FlingCard(
    cardKey: String,
    canFlingRight: Boolean,
    canFlingLeft: Boolean,
    enabled: Boolean,
    onFlingRight: suspend () -> Boolean,
    onFlingLeft: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember(cardKey) { Animatable(0f) }
    var widthPx by remember(cardKey) { mutableIntStateOf(1) }
    val dragState = rememberDraggableState { delta -> scope.launch { offsetX.snapTo(offsetX.value + delta) } }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .graphicsLayer {
                translationX = offsetX.value
                rotationZ = if (size.width > 0f) (offsetX.value / size.width) * FLING_MAX_TILT_DEG else 0f
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = { velocity ->
                    val w = widthPx.toFloat().coerceAtLeast(1f)
                    val past = abs(offsetX.value) > w * FLING_DISTANCE_FRACTION || abs(velocity) > FLING_VELOCITY_THRESHOLD
                    val goingRight = offsetX.value > 0f
                    when {
                        past && goingRight && canFlingRight -> {
                            offsetX.animateTo(w * FLING_THROW_FACTOR, tween(180), initialVelocity = velocity)
                            val left = onFlingRight()
                            if (!left) offsetX.animateTo(0f, spring())  // approve failed/no-op → fly back
                        }
                        past && !goingRight && canFlingLeft -> {
                            onFlingLeft()
                            offsetX.animateTo(0f, spring())  // card stays; the sheet drives the advance
                        }
                        else -> offsetX.animateTo(0f, spring())  // under threshold / not allowed → back
                    }
                },
            ),
    ) { content() }
}

/** AC11: a compact per-workspace error banner (mirrors HomeScreen's error chips). */
@Composable
private fun WorkspaceErrorLine(errors: List<WorkspaceError>) {
    Text(
        "⚠ ${errors.size} workspace(s) unreachable: ${errors.joinToString { it.workspaceName }}",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CardFace(
    card: QueueV2Card,
    vm: QueueViewModel,
    enabled: Boolean,
    onApproveAll: () -> Unit,
    onReject: () -> Unit,
    onOption: (String) -> Unit,
) {
    // High-contrast elevated surface: the highest container tone + shadow + a hairline border so the
    // card clearly floats above the screen background in both light and dark.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // Content scrolls inside the (bounded) card so overflow never pushes the pinned Skip off-screen.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(card.workspaceName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(cardLabel(card), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when (card) {
                is ProseCard -> {
                    Text(card.sectionLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(card.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    // Whole-section Check/Flag: mirror the fling gestures (approve-all / reject sheet).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VerdictToggles(
                            approved = card.verdict == "approved",
                            flagged = card.verdict == "flagged",
                            enabled = enabled,
                            onApprove = onApproveAll,
                            onFlag = onReject,
                        )
                    }
                }
                is CriteriaCard -> {
                    card.items.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            VerdictToggles(
                                approved = item.verdict == "approved",
                                flagged = item.verdict == "flagged",
                                enabled = enabled,
                                onApprove = {
                                    vm.setItemVerdict(card.connectionId, card.specId, item.id, if (item.verdict == "approved") "unreviewed" else "approved")
                                },
                                onFlag = {
                                    vm.setItemVerdict(card.connectionId, card.specId, item.id, if (item.verdict == "flagged") "unreviewed" else "flagged")
                                },
                            )
                            Text(item.text, Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                is QuestionsCard -> {
                    card.items.forEach { item ->
                        QuestionAnswerRow(item, enabled) { answer -> vm.answerQuestion(card.connectionId, card.specId, item.id, answer) }
                    }
                }
                is DecisionCard -> {
                    DecisionPromptCard(
                        text = card.text,
                        decision = card.decision,
                        enabled = enabled,
                        onOption = onOption,
                    )
                }
            }
        }
    }
}

/** The two-toggle Check/Flag idiom reused from SpecDetailScreen's CriterionRow: Check tints
 *  primary when approved, Flag tints error when flagged, both outline otherwise. */
@Composable
private fun VerdictToggles(
    approved: Boolean,
    flagged: Boolean,
    enabled: Boolean,
    onApprove: () -> Unit,
    onFlag: () -> Unit,
) {
    IconToggleButton(checked = approved, enabled = enabled, onCheckedChange = { onApprove() }) {
        Icon(
            Icons.Filled.Check,
            "approve",
            tint = if (approved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
    IconToggleButton(checked = flagged, enabled = enabled, onCheckedChange = { onFlag() }) {
        Icon(
            Icons.Filled.Flag,
            "flag",
            tint = if (flagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun QuestionAnswerRow(item: QuestionItem, enabled: Boolean, onAnswer: (String) -> Unit) {
    var draft by remember(item.id) { mutableStateOf(item.answer ?: "") }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(item.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = enabled,
                label = { Text(if (item.answer.isNullOrBlank()) "answer" else "edit answer") },
            )
            TextButton(onClick = { if (draft.isNotBlank()) onAnswer(draft) }, enabled = enabled) { Text("Send") }
        }
    }
}

/** Reject reason sheet — the ModalBottomSheet idiom from ui/messages/DecisionCard's CommentSheet,
 *  wired to `vm.rejectCurrent`. Clears the field before send so the button disables (no double-submit). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RejectSheet(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Request changes", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val toSend = reason
                    reason = ""
                    onSend(toSend)
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                },
                enabled = reason.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send") }
        }
    }
}

private fun cardLabel(card: QueueV2Card): String = when (card) {
    is ProseCard -> "Review prose"
    is CriteriaCard -> "Review acceptance criteria"
    is QuestionsCard -> "Open questions"
    is DecisionCard -> "Needs decision"
}
