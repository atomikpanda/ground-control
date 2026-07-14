// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueScreen.kt
package com.atomikpanda.groundcontrol.ui.queue

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.WorkspaceError
import com.atomikpanda.groundcontrol.ui.messages.DecisionCard as DecisionPromptCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Queue v2 card stack. The head card is acted on by a directional swipe — right
// (StartToEnd) approve-alls a Prose/Criteria card and auto-approves its spec when
// that makes it approvable; left (EndToStart) opens a reject comment sheet
// (request-changes on the whole spec). Inside a card, per-item Check/Flag toggles
// (criteria) and answer fields (questions) edit in place without advancing. Skip
// sends the head to the back. All transitions live in the tested QueueViewModel.
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
    val cs = rememberCoroutineScope()
    var rejectSheet by remember { mutableStateOf(false) }

    // Surface a failed inline action (approve/reject/answer/decision) — the card is NOT dismissed on failure.
    val actionError = (state as? QueueUiState.Content)?.actionError
    LaunchedEffect(actionError) { if (actionError != null) snackbar.showSnackbar(actionError) }

    // Approve-all the head + arm an undo snackbar (shared by swipe-right and the Prose Check toggle).
    val approveAll: () -> Unit = {
        vm.approveAllCurrent()
        cs.launch {
            val r = snackbar.showSnackbar("Approved", actionLabel = "Undo")
            if (r == SnackbarResult.ActionPerformed) vm.undo()
        }
    }

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
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (s.errors.isNotEmpty()) { WorkspaceErrorLine(s.errors); Spacer(Modifier.height(8.dp)) }
                            Text("${s.position} of ${s.total}")
                            Spacer(Modifier.height(12.dp))
                            // Directional swipe. Both directions return false (never actually dismiss) —
                            // the ViewModel drives the advance, so a failed action leaves the card in place
                            // rather than a stuck-dismissed box. Keyed to the card so each head gets a fresh
                            // state (and confirmValueChange closes over the right card).
                            key(card.key) {
                                val canApprove = card is ProseCard || card is CriteriaCard
                                val hasSpec = card !is DecisionCard
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { target ->
                                        when (target) {
                                            SwipeToDismissBoxValue.StartToEnd -> if (canApprove) approveAll()  // right = approve-all
                                            SwipeToDismissBoxValue.EndToStart -> if (hasSpec) rejectSheet = true  // left = reject sheet
                                            SwipeToDismissBoxValue.Settled -> {}
                                        }
                                        false
                                    },
                                )
                                SwipeToDismissBox(state = dismissState, backgroundContent = {}) {
                                    CardFace(
                                        card = card,
                                        vm = vm,
                                        enabled = !s.inFlight,
                                        onApproveAll = approveAll,
                                        onReject = { rejectSheet = true },
                                        onOption = { text ->
                                            vm.answerDecision(text)
                                            cs.launch {
                                                val r = snackbar.showSnackbar("Sent", actionLabel = "Undo")
                                                if (r == SnackbarResult.ActionPerformed) vm.undo()
                                            }
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.skip() }, enabled = !s.inFlight) { Text("Skip") }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(card.workspaceName, textAlign = TextAlign.Start)
            Spacer(Modifier.height(4.dp))
            Text(cardLabel(card), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            when (card) {
                is ProseCard -> {
                    Text(card.sectionLabel, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(card.text)
                    Spacer(Modifier.height(8.dp))
                    // Whole-section Check/Flag: mirror the swipe gestures (approve-all / reject sheet).
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
                                    vm.setItemVerdict(card.specId, item.id, if (item.verdict == "approved") "unreviewed" else "approved")
                                },
                                onFlag = {
                                    vm.setItemVerdict(card.specId, item.id, if (item.verdict == "flagged") "unreviewed" else "flagged")
                                },
                            )
                            Text(item.text, Modifier.padding(start = 4.dp))
                        }
                    }
                }
                is QuestionsCard -> {
                    card.items.forEach { item ->
                        QuestionAnswerRow(item, enabled) { answer -> vm.answerQuestion(card.specId, item.id, answer) }
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
        Text(item.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
