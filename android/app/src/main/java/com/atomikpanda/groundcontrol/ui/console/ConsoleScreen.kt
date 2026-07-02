package com.atomikpanda.groundcontrol.ui.console

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.ReviewSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.messages.DecisionCard
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle
import com.atomikpanda.groundcontrol.ui.theme.SemanticColors

/**
 * Console for a single in-flight work item: header + parallel task rows + the
 * "focused" task's live detail (AC progress, journal tail, test results) + the
 * hosted decision card (when the item's thread has one pending) + a free-text
 * Steer bar. Mirrors [com.atomikpanda.groundcontrol.ui.workspace.WorkspaceScreen] /
 * [com.atomikpanda.groundcontrol.ui.farm.FarmScreen] for the Scaffold + back TopAppBar.
 *
 * `vm.load()` + `vm.startPolling()` are started once for the composable's lifetime;
 * the polling Job is cancelled `onDispose` so it doesn't keep hitting the network
 * after the screen is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(vm: ConsoleViewModel, title: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        vm.load()
        val pollJob = vm.startPolling()
        onDispose { pollJob.cancel() }
    }

    val displayTitle = (state as? ConsoleUiState.Content)?.c?.item?.title?.takeIf { it.isNotBlank() } ?: title

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                ConsoleUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is ConsoleUiState.Failed ->
                    Text(
                        s.reason,
                        color = LocalSemanticColors.current.error,
                        modifier = Modifier.padding(24.dp),
                    )
                is ConsoleUiState.Content -> ConsoleContentView(s.c, vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleContentView(c: ConsoleContent, vm: ConsoleViewModel) {
    val colors = LocalSemanticColors.current
    // The journal + focused test results are fetched for the item's first task slug
    // (same fan-out as ConsoleViewModel.fetch); mirror that "focus" here.
    val focusedTask = c.tasks.firstOrNull()
    val activeDecision = c.activeDecision
    val activeDecisionText = c.activeDecisionText

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { HeaderSection(c.item) }

            item { SectionLabel("TASKS") }
            items(c.tasks, key = { it.slug }) { task -> TaskRow(task, colors) }

            item { SectionLabel("LIVE DETAIL") }
            c.review?.let { review -> item { AcProgress(review, colors) } }
            if (c.journal.isNotEmpty()) {
                items(c.journal.takeLast(5)) { entry -> JournalRow(entry) }
            }
            focusedTask?.testResults?.entries?.toList()?.takeIf { it.isNotEmpty() }?.let { results ->
                items(results) { (repo, status) -> TestResultRow(repo, status, colors) }
            }

            if (activeDecision != null && activeDecisionText != null) {
                item {
                    DecisionCard(
                        text = activeDecisionText.orEmpty(),
                        decision = activeDecision,
                        enabled = true,
                        onOption = { vm.answerOption(it) },
                    )
                }
            }
        }
        SteerBar(vm)
    }
}

@Composable
private fun HeaderSection(item: WorkItemSummary) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        Text(
            "${item.kind} · ${item.phase}",
            style = MonoStyle,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(task: TaskSummary, colors: SemanticColors) {
    ListItem(
        headlineContent = { Text(task.slug, style = MonoStyle) },
        supportingContent = {
            Column {
                Text(task.phase, style = MaterialTheme.typography.bodySmall)
                if (task.testResults.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        task.testResults.forEach { (repo, status) ->
                            Text(
                                "$repo:$status",
                                style = MonoStyle,
                                color = when (status) {
                                    "pass" -> colors.approval
                                    "skip" -> colors.muted
                                    else -> colors.error
                                },
                            )
                        }
                    }
                }
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.blockedReason != null) Badge("blocked", colors.blocker)
                if (task.prUrls.isNotEmpty()) Badge("PR", colors.approval)
            }
        },
    )
}

@Composable
private fun AcProgress(review: ReviewSummary, colors: SemanticColors) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
        Text(
            "${review.approved}/${review.criteriaTotal} AC approved",
            style = MaterialTheme.typography.bodySmall,
        )
        val fraction = if (review.criteriaTotal > 0) review.approved.toFloat() / review.criteriaTotal else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = colors.approval,
        )
    }
}

@Composable
private fun JournalRow(entry: JournalEntry) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 2.dp)) {
        Text(entry.timestamp, style = MonoStyle, color = MaterialTheme.colorScheme.outline)
        Text(entry.message, style = MonoStyle)
    }
}

@Composable
private fun TestResultRow(repo: String, status: String, colors: SemanticColors) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp, 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(repo, style = MonoStyle)
        Text(
            status,
            style = MonoStyle,
            color = when (status) {
                "pass" -> colors.approval
                "skip" -> colors.muted
                else -> colors.error
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
)

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MonoStyle,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Free-text escape hatch: posts straight to the work-item thread via `vm.sendDraft`. The
 *  draft is VM-owned (not local Compose state) so it survives a failed send instead of
 *  being cleared on tap — [ConsoleViewModel.sendDraft] only clears it on success. Disabled
 *  (with a spinner) while a send is in flight so it can't be double-submitted, and surfaces
 *  [ConsoleViewModel.sendError] (from a prior failed send) until dismissed or the next attempt. */
@Composable
private fun SteerBar(vm: ConsoleViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val sendError by vm.sendError.collectAsStateWithLifecycle()
    Surface(tonalElevation = 3.dp) {
        Column {
            sendError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSemanticColors.current.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { vm.clearSendError() },
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
                    placeholder = { Text("Steer…") },
                    singleLine = true,
                    enabled = !sending,
                )
                if (sending) {
                    CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                vm.clearSendError()
                                vm.sendDraft()
                            }
                        },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
