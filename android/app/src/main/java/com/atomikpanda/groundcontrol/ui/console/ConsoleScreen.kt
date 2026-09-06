package com.atomikpanda.groundcontrol.ui.console

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.ReviewSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.activity.PhaseStepper
import com.atomikpanda.groundcontrol.ui.activity.phaseStepFor
import com.atomikpanda.groundcontrol.ui.components.ExternalLinksRow
import com.atomikpanda.groundcontrol.ui.components.JournalEntryRow
import com.atomikpanda.groundcontrol.ui.components.MultilineComposeInput
import com.atomikpanda.groundcontrol.ui.components.WorkspaceBadge
import com.atomikpanda.groundcontrol.ui.messages.DecisionCard
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle
import com.atomikpanda.groundcontrol.ui.theme.SemanticColors
import com.atomikpanda.groundcontrol.ui.theme.WorkspaceIdentity
import kotlinx.coroutines.launch

/**
 * Console for a single in-flight work item: its factual status summary, parallel
 * task rows, work review, task-scoped journal/test detail, an optional hosted
 * decision card, and a free-text Steer bar. Mirrors
 * [com.atomikpanda.groundcontrol.ui.workspace.WorkspaceScreen] /
 * [com.atomikpanda.groundcontrol.ui.farm.FarmScreen] for the Scaffold + back TopAppBar.
 *
 * `vm.load()` + `vm.startPolling()` are started once for the composable's lifetime;
 * the polling Job is cancelled `onDispose` so it doesn't keep hitting the network
 * after the screen is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(vm: ConsoleViewModel, title: String, identity: WorkspaceIdentity? = null, onBack: () -> Unit) {
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        identity?.let { WorkspaceBadge(it, size = 20.dp); Spacer(Modifier.width(8.dp)) }
                        Text(displayTitle, maxLines = 1)
                    }
                },
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
                is ConsoleUiState.Unavailable ->
                    Text(s.message, modifier = Modifier.padding(24.dp))
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
    val focusedTask = c.tasks.firstOrNull()
    val activeDecision = c.activeDecision
    val activeDecisionText = c.activeDecisionText
    val hasAnswerableDecision = activeDecision != null && activeDecisionText != null
    val sending by vm.sending.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val decisionFocusRequester = remember { FocusRequester() }
    // Header, status, and the task label precede each task row.
    val decisionIndex = c.tasks.size + 3
    val onOpenDecision: (() -> Unit)? = if (hasAnswerableDecision) {
        {
            scope.launch {
                listState.animateScrollToItem(decisionIndex)
                decisionFocusRequester.requestFocus()
            }
        }
    } else {
        null
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            item { HeaderSection(c.item) }
            item { StatusSummary(c, colors, onOpenDecision) }

            item { SectionLabel("TASKS") }
            items(c.tasks, key = { it.slug }) { task -> TaskRow(task, colors) }

            if (hasAnswerableDecision) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(decisionFocusRequester)
                            .focusable(),
                    ) {
                        DecisionCard(
                            text = activeDecisionText.orEmpty(),
                            decision = activeDecision!!,
                            enabled = !sending,
                            onOption = { vm.answerOption(it) },
                        )
                    }
                }
            }

            c.review?.let { review ->
                item { SectionLabel("WORK REVIEW") }
                item { AcProgress(review, colors) }
            }
            if (c.journal.isNotEmpty()) {
                item { SectionLabel("JOURNAL · TASK ${c.item.taskSlugs.firstOrNull() ?: "UNKNOWN"}") }
                items(c.journal.takeLast(5)) { entry -> JournalEntryRow(entry) }
            }
            focusedTask?.testResults?.entries?.toList()?.takeIf { it.isNotEmpty() }?.let { results ->
                item { SectionLabel("TESTS · TASK ${focusedTask.slug}") }
                items(results) { (repo, status) -> TestResultRow(repo, status, colors) }
            }
        }
        SteerBar(vm)
    }
}

@Composable
private fun StatusSummary(
    content: ConsoleContent,
    colors: SemanticColors,
    onOpenDecision: (() -> Unit)?,
) {
    val summary = content.summary
    val normalizedPhase = summary.phase.lowercase()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("STATUS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                "Current phase: ${summary.phase}",
                style = MaterialTheme.typography.titleMedium,
            )
            PhaseStepper(
                current = phaseStepFor(
                    taskPhase = when (normalizedPhase) {
                        "unknown", "in_flight", "dispatched" -> null
                        else -> normalizedPhase
                    },
                    done = normalizedPhase in setOf("done", "completed", "merged"),
                    dispatched = normalizedPhase in setOf("in_flight", "dispatched"),
                ),
            )
            Text(
                "Latest reported activity: ${summary.latestActivityAt ?: "Unknown"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (summary.activityUnknownTaskSlugs.isNotEmpty()) {
                Text(
                    "Activity timestamp unknown for: ${summary.activityUnknownTaskSlugs.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                summary.blockers.isNotEmpty() -> {
                    Text("Blockers", style = MaterialTheme.typography.labelLarge, color = colors.blocker)
                    summary.blockers.forEach { blocker ->
                        Text(
                            "${blocker.taskSlug}: ${blocker.reason}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.blocker,
                        )
                    }
                    if (summary.blockerReasonUnavailable) {
                        Text(
                            "Another blocker is reported, but its task or reason is unavailable.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.blocker,
                        )
                    }
                }
                summary.blockerReasonUnavailable -> Text(
                    "A blocker is reported, but its task or reason is unavailable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.blocker,
                )
                else -> Text("Blockers: none reported", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "User input: ${if (summary.userInputPending) "Pending" else "Not pending"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (summary.userInputPending) colors.question else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onOpenDecision?.let { openDecision ->
                Button(onClick = openDecision, modifier = Modifier.fillMaxWidth()) {
                    Text("Answer decision")
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(item: WorkItemSummary) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        Text(
            item.kind,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExternalLinksRow(item.externalLinks, Modifier.padding(top = 4.dp))
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
            MultilineComposeInput(
                value = draft,
                onValueChange = vm::onDraftChange,
                onSend = {
                    if (draft.isNotBlank()) {
                        vm.clearSendError()
                        vm.sendDraft()
                    }
                },
                placeholder = "Steer…",
                enabled = !sending,
                inFlight = sending,
                sendDescription = "Send",
            )
        }
    }
}
