// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueScreen.kt
package com.atomikpanda.groundcontrol.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.WorkspaceError
import com.atomikpanda.groundcontrol.ui.messages.DecisionCard as DecisionPromptCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// PR2 note: this is the minimal card-stack UI kept compiling against the Queue v2
// model. The full v2 interactions — directional swipe (approve-all / reject sheet),
// per-item Check/Flag rows, per-question answer fields — land in PR3; here each
// card face shows its content plus the thin PR2 actions (approve a spec / answer a
// decision) with the existing defer / undo / poll machinery intact.
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

    // Surface a failed inline action (approve/decision) — the card is NOT dismissed on failure.
    val actionError = (state as? QueueUiState.Content)?.actionError
    LaunchedEffect(actionError) { if (actionError != null) snackbar.showSnackbar(actionError) }

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
                            // Swipe defers, then snaps back (confirmValueChange returns false so it never
                            // actually dismisses). PR3 makes this directional (approve / reject sheet).
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { vm.defer(); false },
                            )
                            SwipeToDismissBox(state = dismissState, backgroundContent = {}) {
                                CardFace(
                                    card = card,
                                    enabled = !s.inFlight,
                                    onApprove = {
                                        vm.approveCurrent()
                                        cs.launch {
                                            val r = snackbar.showSnackbar("Approved", actionLabel = "Undo")
                                            if (r == SnackbarResult.ActionPerformed) vm.undo()
                                        }
                                    },
                                    onOption = { text ->
                                        vm.answerDecision(text)
                                        cs.launch {
                                            val r = snackbar.showSnackbar("Sent", actionLabel = "Undo")
                                            if (r == SnackbarResult.ActionPerformed) vm.undo()
                                        }
                                    },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.defer() }, enabled = !s.inFlight) { Text("Defer") }
                        }
                    }
                }
            }
        }
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
    enabled: Boolean,
    onApprove: () -> Unit,
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
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onApprove, enabled = enabled) { Text("Approve") }
                }
                is CriteriaCard -> {
                    card.items.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onApprove, enabled = enabled) { Text("Approve") }
                }
                is QuestionsCard -> {
                    card.items.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium) }
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

private fun cardLabel(card: QueueV2Card): String = when (card) {
    is ProseCard -> "Review prose"
    is CriteriaCard -> "Review acceptance criteria"
    is QuestionsCard -> "Open questions"
    is DecisionCard -> "Needs decision"
}
