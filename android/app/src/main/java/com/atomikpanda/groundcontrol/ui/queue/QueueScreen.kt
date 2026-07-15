// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueScreen.kt
package com.atomikpanda.groundcontrol.ui.queue

import com.atomikpanda.groundcontrol.ui.components.MultilineComposeInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.CoachMarkStore
import com.atomikpanda.groundcontrol.data.WorkspaceError
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.messages.DecisionCard as DecisionPromptCard
import com.atomikpanda.groundcontrol.ui.specdetail.evidenceLabels
import com.atomikpanda.groundcontrol.ui.specdetail.isUnverified
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
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
    coachMark: CoachMarkStore,
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
    val scope = rememberCoroutineScope()
    var rejectSheet by remember { mutableStateOf(false) }

    // First-run onboarding coach mark (AC3): show once when the persisted seen flag is a definite
    // false (null = still loading → don't flash it for a returning user). The header info affordance
    // re-opens it on demand; "Got it" persists the flag so it never reappears.
    val seen by coachMark.seen.collectAsStateWithLifecycle()
    var showCoach by remember { mutableStateOf(false) }
    var autoShown by remember { mutableStateOf(false) }
    LaunchedEffect(seen) {
        if (seen == false && !autoShown) { showCoach = true; autoShown = true }
    }

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

    // Whole-spec confirmation (AC4): a swipe that finalized an entire spec shows a longer-duration
    // 'Approved spec: <title>' — no Undo, because a whole-spec approve can't be reversed server-side.
    // Keyed per finalized spec so each ship re-triggers.
    val specApproved = (state as? QueueUiState.Content)?.specApproved
    LaunchedEffect(specApproved?.key) {
        if (specApproved != null) {
            snackbar.showSnackbar("Approved spec: ${specApproved.title}", duration = SnackbarDuration.Long)
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
                            // Header: position indicator + a small info affordance that re-opens the swipe
                            // coach mark on demand (AC3) — always reachable once onboarding is dismissed.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${s.position} of ${s.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { showCoach = true }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.HelpOutline,
                                        contentDescription = "How swiping works",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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

    if (showCoach) {
        SwipeCoachMark(onDismiss = {
            showCoach = false
            coachMark.markSeenAsync()   // persist on the store's app-level scope: never reappears
        })
    }
}

/**
 * First-run onboarding for the Queue's swipe model (AC3): a one-time, dismissible overlay that
 * demonstrates swipe-right = approve and swipe-left = request changes. "Got it" persists the seen flag
 * (via the caller) so it never reappears; the header info affordance re-opens it on demand.
 */
@Composable
private fun SwipeCoachMark(onDismiss: () -> Unit) {
    val approval = LocalSemanticColors.current.approval
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Swipe to review") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This is where you clear the queue. Swipe each card:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = approval)
                    Text(
                        "  Swipe right to approve",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = approval,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        "  Swipe left to request changes",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Questions and decisions ask you to answer or choose instead. Skip sends a card to the back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
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
                    val velocityPast = abs(velocity) > FLING_VELOCITY_THRESHOLD
                    val past = abs(offsetX.value) > w * FLING_DISTANCE_FRACTION || velocityPast
                    // Direction from the flick's velocity when it's a fast fling, else from the resting
                    // offset — so a fast left flick that lifts at a small positive offset still rejects
                    // (opens the sheet) rather than approving the card.
                    val goingRight = if (velocityPast) velocity > 0f else offsetX.value > 0f
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
    ) {
        content()
        // Drag directional stamps (AC1): as the card is dragged, a cue fades in whose alpha grows with
        // drag distance toward the fling threshold — green Approve toward the right, red Request changes
        // toward the left — and clears on release / spring-back (offset → 0). Shown only for the direction
        // that's a valid action on this card. Driven by a graphicsLayer lambda so a drag never recomposes.
        val approval = LocalSemanticColors.current.approval
        val error = MaterialTheme.colorScheme.error
        if (canFlingRight) {
            DragStamp("✓ ${QueueHints.APPROVE}", approval, Alignment.TopEnd) {
                (offsetX.value / (widthPx.toFloat() * FLING_DISTANCE_FRACTION)).coerceIn(0f, 1f)
            }
        }
        if (canFlingLeft) {
            DragStamp("⚑ ${QueueHints.REQUEST_CHANGES}", error, Alignment.TopStart) {
                (-offsetX.value / (widthPx.toFloat() * FLING_DISTANCE_FRACTION)).coerceIn(0f, 1f)
            }
        }
    }
}

/** A directional drag cue stamped over a corner of the flung card. [alpha] is read in the draw phase
 *  (a lambda) so following the finger never triggers recomposition. */
@Composable
private fun BoxScope.DragStamp(
    text: String,
    color: Color,
    alignment: Alignment,
    alpha: () -> Float,
) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(alignment)
            .padding(16.dp)
            .graphicsLayer { this.alpha = alpha() }
            .border(BorderStroke(2.dp, color), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
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
            // Spec-review cards (#B) carry their WorkItem context: title + kind + the repos it touches,
            // so an approver knows *what* they're approving without opening the spec detail.
            val specMeta = when (card) {
                is ProseCard -> card.meta
                is CriteriaCard -> card.meta
                is QuestionsCard -> card.meta
                else -> null
            }
            if (specMeta != null) {
                Spacer(Modifier.height(4.dp))
                QueueCardMeta(specMeta)
            }
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
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
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
                            // Criterion text + its evidence, so the operator sees what backs each one while
                            // approving from the stack: labeled refs, or a muted "unverified" when nothing does.
                            Column(Modifier.padding(start = 4.dp, top = 8.dp)) {
                                Text(item.text, color = MaterialTheme.colorScheme.onSurface)
                                if (isUnverified(item.evidence)) {
                                    Text("unverified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    evidenceLabels(item.evidence).forEach { line ->
                                        Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
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
            Spacer(Modifier.height(12.dp))
            // Always-visible resting hint (AC2/AC5): a subtle, non-tappable line teaching what a swipe
            // does before the operator tries it — both directions for approve-capable cards, or what's
            // needed for questions/decisions. Muted + centered so it never reads as a tappable button.
            Text(
                queueCardHint(card),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The WorkItem context line on a spec-review card (#B): the spec/WorkItem title in prominent
 *  weight, then a muted `kind · repo, repo` line so the approver sees the item's kind and the repos
 *  the change touches at a glance. Blank fields are simply omitted. */
@Composable
private fun QueueCardMeta(meta: SpecCardMeta) {
    if (meta.title.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            meta.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val bits = buildList {
        meta.workItemKind?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (meta.affectedRepos.isNotEmpty()) add(meta.affectedRepos.joinToString(", "))
    }
    if (bits.isNotEmpty()) {
        Spacer(Modifier.height(2.dp))
        // Cap the kind · repos line so a spec touching many repos can't wrap into a wall of text
        // that shoves the review body down the scrollable card (Greptile #50 P2).
        Text(
            bits.joinToString("  ·  "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
            tint = if (approved) LocalSemanticColors.current.approval else MaterialTheme.colorScheme.outline,
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
            // Shared multiline compose input (#282) — the flag-comment consumer (#283).
            MultilineComposeInput(
                value = reason,
                onValueChange = { reason = it },
                onSend = {
                    val toSend = reason
                    reason = ""
                    onSend(toSend)
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                },
                placeholder = "Reason for changes…",
                sendDescription = "Send request-changes",
            )
        }
    }
}

private fun cardLabel(card: QueueV2Card): String = when (card) {
    is ProseCard -> "Review prose"
    is CriteriaCard -> "Review acceptance criteria"
    is QuestionsCard -> "Open questions"
    is DecisionCard -> "Needs decision"
}
