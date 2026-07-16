package com.atomikpanda.groundcontrol.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.components.ExternalLinksRow
import com.atomikpanda.groundcontrol.ui.specdetail.evidenceLabels
import com.atomikpanda.groundcontrol.ui.specdetail.isUnverified
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle
import com.atomikpanda.groundcontrol.ui.theme.SemanticColors

/**
 * Review cockpit for a single work item: header + one row per [PrRow] (repo · task slug ·
 * GitHub tap-through · local test status) + a "Request changes" action that posts a reason to
 * the item's thread via [ReviewViewModel.requestChanges]. Mirrors
 * [com.atomikpanda.groundcontrol.ui.console.ConsoleScreen] for the Scaffold + back TopAppBar and
 * [com.atomikpanda.groundcontrol.ui.tasks.TaskDetailScreen] for the PR tap-through pattern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: ReviewViewModel, title: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    val displayTitle = (state as? ReviewUiState.Content)?.c?.item?.title?.takeIf { it.isNotBlank() } ?: title

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
                ReviewUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is ReviewUiState.Failed ->
                    Text(
                        s.reason,
                        color = LocalSemanticColors.current.error,
                        modifier = Modifier.padding(24.dp),
                    )
                is ReviewUiState.Content -> ReviewContentView(s.c, vm)
            }
        }
    }
}

@Composable
private fun ReviewContentView(c: ReviewContent, vm: ReviewViewModel) {
    val colors = LocalSemanticColors.current

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { HeaderSection(c.item) }

            if (c.prs.isEmpty()) {
                item {
                    Text(
                        "No PRs yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                        modifier = Modifier.padding(16.dp, 8.dp),
                    )
                }
            } else {
                items(c.prs, key = { "${it.taskSlug}:${it.repo}" }) { pr -> PrRowView(pr, colors) }
            }

            if (c.criteria.isNotEmpty()) {
                item {
                    Text(
                        "Acceptance criteria",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(c.criteria, key = { it.id }) { crit ->
                    CriterionEvidenceRow(crit, c.prUrls, colors)
                }
            }
        }
        val sending by vm.sending.collectAsStateWithLifecycle()
        val sendError by vm.sendError.collectAsStateWithLifecycle()
        RequestChangesBar(
            c.threadId,
            sending = sending,
            sendError = sendError,
            onSubmit = { vm.requestChanges(it) },
            onDismissError = { vm.clearSendError() },
        )
    }
}

@Composable
private fun HeaderSection(item: WorkItemSummary) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        Text("Review", style = MonoStyle, color = MaterialTheme.colorScheme.outline)
        ExternalLinksRow(item.externalLinks, Modifier.padding(top = 4.dp))
    }
}

/** One acceptance criterion + its evidence. Commit-kind evidence (and artifact URLs) tap through
 *  to GitHub via [evidenceOpenUrl]; test refs and multi-repo commits render read-only. Labels reuse
 *  the shared specdetail [evidenceLabels] helper. */
@Composable
private fun CriterionEvidenceRow(crit: ReviewCriterion, prUrls: List<String>, colors: SemanticColors) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(crit.text, style = MaterialTheme.typography.bodyMedium)
        if (isUnverified(crit.evidence)) {
            Text("unverified", style = MonoStyle, color = colors.muted)
        } else {
            val labels = evidenceLabels(crit.evidence)
            crit.evidence.forEachIndexed { i, e ->
                val url = evidenceOpenUrl(e.kind, e.ref, prUrls)
                val base = Modifier.fillMaxWidth().padding(top = 2.dp)
                if (url != null) {
                    Text(
                        labels[i],
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = base.clickable { runCatching { uriHandler.openUri(url) } },
                    )
                } else {
                    Text(labels[i], style = MonoStyle, color = colors.muted, modifier = base)
                }
            }
        }
    }
}

@Composable
private fun PrRowView(pr: PrRow, colors: SemanticColors) {
    val uriHandler = LocalUriHandler.current
    ListItem(
        headlineContent = { Text(pr.taskSlug, style = MonoStyle) },
        supportingContent = {
            Text(
                "${pr.repo} PR ↗",
                modifier = Modifier.clickable { uriHandler.openUri(pr.url) },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Text(
                pr.testStatus ?: "—",
                style = MonoStyle,
                color = when (pr.testStatus) {
                    "pass" -> colors.approval
                    "skip" -> colors.muted
                    null -> colors.muted
                    else -> colors.error
                },
            )
        },
    )
}

/** "Request changes" action: disabled with a hint when the item has no thread to post to,
 *  and disabled (with a spinner) while a send is in flight so it can't be double-submitted.
 *  Surfaces [sendError] (from a prior failed send) until dismissed or the next attempt. */
@Composable
private fun RequestChangesBar(
    threadId: String?,
    sending: Boolean,
    sendError: String?,
    onSubmit: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    var showReason by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            sendError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSemanticColors.current.error,
                    modifier = Modifier.padding(bottom = 8.dp).clickable { onDismissError() },
                )
            }
            Button(
                onClick = {
                    onDismissError()
                    showReason = true
                },
                enabled = threadId != null && !sending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Request changes")
                }
            }
            if (threadId == null) {
                Text(
                    "No conversation on this item.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSemanticColors.current.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (showReason) {
        ReasonDialog(onDismiss = { showReason = false }) { reason ->
            showReason = false
            onSubmit(reason)
        }
    }
}

/**
 * Inlined here rather than reused from `ui/specdetail/SpecDetailScreen.kt`: that screen's
 * `ReasonDialog` is `private` to its file, so this is a minimal copy of the same shape
 * (`AlertDialog` + single `OutlinedTextField`, confirm disabled until non-blank).
 */
@Composable
private fun ReasonDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request changes") },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = reason.isNotBlank(), onClick = { onSubmit(reason) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
