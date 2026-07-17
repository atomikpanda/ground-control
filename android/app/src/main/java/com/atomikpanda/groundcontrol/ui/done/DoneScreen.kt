package com.atomikpanda.groundcontrol.ui.done

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.ReviewSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.components.ExternalLinksRow
import com.atomikpanda.groundcontrol.ui.review.acceptanceCriteriaSection
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle
import com.atomikpanda.groundcontrol.ui.theme.SemanticColors

/**
 * Read-only completion summary for a finished work item: header (title + kind + completed-at) +
 * "Repos touched" + one row per [TaskSummary] (slug · branch · per-repo test-status chips · PR
 * tap-through) + the spec's AC-approval line when the item has a spec. Mirrors
 * [com.atomikpanda.groundcontrol.ui.review.ReviewScreen] for the Scaffold + back TopAppBar and
 * the PR tap-through pattern.
 *
 * Deliberately honest: PR links are only rendered when a task actually has `pr_urls` — an
 * organically-`done` item (no review/PR round-trip) shows none, rather than implying every
 * completion shipped a PR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoneScreen(vm: DoneViewModel, title: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    val displayTitle = (state as? DoneUiState.Content)?.c?.item?.title?.takeIf { it.isNotBlank() } ?: title

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
                DoneUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is DoneUiState.Failed ->
                    Text(
                        s.reason,
                        color = LocalSemanticColors.current.error,
                        modifier = Modifier.padding(24.dp),
                    )
                is DoneUiState.Content -> DoneContentView(s.c)
            }
        }
    }
}

@Composable
private fun DoneContentView(c: DoneContent) {
    val colors = LocalSemanticColors.current

    LazyColumn(Modifier.fillMaxSize()) {
        item { HeaderSection(c.item, c.completedAt) }
        item { ReposTouchedRow(c.reposTouched) }
        items(c.tasks, key = { it.slug }) { task -> TaskRow(task, colors) }
        c.review?.let { review -> item { SpecLine(review) } }
        acceptanceCriteriaSection(c.criteria, c.prUrls)
    }
}

@Composable
private fun HeaderSection(item: WorkItemSummary, completedAt: String?) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        Text(item.kind, style = MonoStyle, color = MaterialTheme.colorScheme.outline)
        ExternalLinksRow(item.externalLinks, Modifier.padding(top = 4.dp))
        Text(
            "Completed ${completedAt ?: "—"}",
            style = MonoStyle,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ReposTouchedRow(reposTouched: List<String>) {
    Text(
        "Repos touched: ${reposTouched.joinToString(", ").ifEmpty { "—" }}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    )
}

@Composable
private fun TaskRow(task: TaskSummary, colors: SemanticColors) {
    val uriHandler = LocalUriHandler.current
    ListItem(
        headlineContent = { Text(task.slug, style = MonoStyle) },
        supportingContent = {
            Column {
                Text(task.branch, style = MonoStyle, color = MaterialTheme.colorScheme.outline)
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
                // Honesty constraint: only ever shown when this task actually has PRs —
                // organically-`done` items (no review round-trip) have none.
                if (task.prUrls.isNotEmpty()) {
                    Column {
                        task.prUrls.forEach { (repo, url) ->
                            Text(
                                "$repo PR ↗",
                                modifier = Modifier.clickable { uriHandler.openUri(url) },
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SpecLine(review: ReviewSummary) {
    Text(
        "Spec: ${review.approved}/${review.criteriaTotal} AC approved",
        style = MonoStyle,
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    )
}
