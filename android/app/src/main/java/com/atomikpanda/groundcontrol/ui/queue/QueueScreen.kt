// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueScreen.kt
package com.atomikpanda.groundcontrol.ui.queue

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.ui.messages.DecisionCard
import kotlinx.coroutines.launch

@Composable
fun QueueScreen(
    vm: QueueViewModel,
    onOpenItem: (connectionId: String, itemId: String) -> Unit,
    onOpenPr: (url: String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    val snackbar = remember { SnackbarHostState() }
    val cs = rememberCoroutineScope()

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val s = state) {
                QueueUiState.Loading -> CircularProgressIndicator()
                QueueUiState.EmptyConfig -> Text("No workspaces connected. Add one in Settings.")
                is QueueUiState.Content -> {
                    val card = s.current
                    if (card == null) {
                        Text("You're all caught up ✓", textAlign = TextAlign.Center)
                    } else {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${s.position} of ${s.total}")
                            Spacer(Modifier.height(12.dp))
                            CardFace(
                                card = card,
                                decision = s.focusedDecision,
                                enabled = !s.inFlight,
                                onApprove = { vm.approveCurrent() },
                                onOption = { text ->
                                    vm.answerDecision(text)
                                    cs.launch {
                                        val r = snackbar.showSnackbar("Sent", actionLabel = "Undo")
                                        if (r == SnackbarResult.ActionPerformed) vm.undo()
                                    }
                                },
                                onOpenItem = { vm.openCurrent(); onOpenItem(card.connectionId, card.workItemId) },
                                onOpenPr = { vm.openCurrent(); card.prUrl?.let(onOpenPr) ?: onOpenItem(card.connectionId, card.workItemId) },
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.defer() }, enabled = !s.inFlight) { Text("Defer") }
                        }
                    }
                }
            }
        }
    }

    // Undo affordance for an inline approve.
    LaunchedEffect(state) {
        val c = state as? QueueUiState.Content ?: return@LaunchedEffect
        if (c.undo != null && c.undo!!.kind == QueueKind.NEEDS_APPROVAL) {
            val r = snackbar.showSnackbar("Approved", actionLabel = "Undo")
            if (r == SnackbarResult.ActionPerformed) vm.undo()
        }
    }
}

@Composable
private fun CardFace(
    card: QueueCard,
    decision: com.atomikpanda.groundcontrol.ui.queue.DecisionPrompt?,
    enabled: Boolean,
    onApprove: () -> Unit,
    onOption: (String) -> Unit,
    onOpenItem: () -> Unit,
    onOpenPr: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(card.workspaceName, textAlign = TextAlign.Start)
            Spacer(Modifier.height(4.dp))
            Text(card.title)
            Spacer(Modifier.height(12.dp))
            when (card.kind) {
                QueueKind.NEEDS_APPROVAL ->
                    Button(onClick = onApprove, enabled = enabled && card.specId != null) { Text("Approve") }
                QueueKind.NEEDS_DECISION ->
                    if (decision != null)
                        DecisionCard(text = decision.text, decision = decision.decision, enabled = enabled, onOption = onOption)
                    else CircularProgressIndicator()
                QueueKind.BLOCKED -> {
                    if (card.blockedTasks > 0) { Text("${card.blockedTasks} blocked task(s)"); Spacer(Modifier.height(8.dp)) }
                    Button(onClick = onOpenItem, enabled = enabled) { Text("Open") }
                }
                QueueKind.NEEDS_REVIEW ->
                    Button(onClick = onOpenPr, enabled = enabled) { Text("Open PR") }
            }
        }
    }
}
