package com.atomikpanda.groundcontrol.ui.farm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.data.dto.Attention
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmScreen(
    vm: FarmViewModel,
    workspaceName: String,
    onOpen: (WorkItemSummary) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workspaceName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        when (val s = state) {
            FarmUiState.Loading -> Box(Modifier.fillMaxSize().padding(pad)) {
                Text("Loading…", Modifier.padding(24.dp))
            }
            is FarmUiState.Unavailable -> Box(Modifier.fillMaxSize().padding(pad)) {
                Text(s.message, Modifier.padding(24.dp))
            }
            is FarmUiState.Content -> {
                if (s.errored && s.groups.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        Text("Couldn't reach this workspace.", Modifier.padding(24.dp),
                            color = LocalSemanticColors.current.error)
                    }
                } else if (s.groups.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        Text("Nothing here yet.", Modifier.padding(24.dp),
                            color = LocalSemanticColors.current.muted)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                        s.groups.forEach { group ->
                            item(key = "hdr-${group.phase.name}") {
                                Text(
                                    "${group.phase.label}   ${group.items.size}",
                                    style = MonoStyle,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(group.items, key = { it.id }) { wi ->
                                FarmCard(
                                    item = wi,
                                    onClick = { onOpen(wi) },
                                    onToggleUnattended = { on -> vm.setUnattended(wi, on) },
                                    onMarkDone = { vm.setItemPhase(wi, "done") },
                                    onReopen = { vm.setItemPhase(wi, null) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FarmCard(
    item: WorkItemSummary,
    onClick: () -> Unit,
    onToggleUnattended: (Boolean) -> Unit,
    onMarkDone: () -> Unit,
    onReopen: () -> Unit,
) {
    // Only present a tap affordance when there's somewhere to go. A brand-new inbox item can
    // legally have no spec/task/thread yet; making such a card non-clickable avoids a silent
    // dead-tap (until the per-phase cockpits give every item its own destination).
    val routable = item.specId != null || item.taskSlugs.isNotEmpty() || item.threadIds.isNotEmpty()
    val uriHandler = LocalUriHandler.current
    ListItem(
        leadingContent = { Icon(kindIcon(item.kind), contentDescription = item.kind) },
        headlineContent = { Text(item.title) },
        supportingContent = {
            // Keep metadata and actions in the full-width content column; trailing badges
            // otherwise squeeze long titles and make action labels wrap inside their buttons.
            Column {
                Text(subLine(item), style = MonoStyle)
                AttentionBadges(item.attention)
                if (item.effectivePhase() == "done") {
                    if (item.affectedRepos.isNotEmpty()) {
                        Text(
                            "repos: ${item.affectedRepos.joinToString(", ")}",
                            style = MonoStyle,
                            color = LocalSemanticColors.current.muted,
                        )
                    }
                    item.prUrls.forEachIndexed { index, url ->
                        Text(
                            "PR ${index + 1} ↗",
                            modifier = Modifier.clickable { uriHandler.openUri(url) },
                            color = MaterialTheme.colorScheme.primary,
                            style = MonoStyle,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("unattended", style = MonoStyle, color = LocalSemanticColors.current.muted)
                    Switch(
                        checked = item.unattended,
                        onCheckedChange = onToggleUnattended,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // Quick actions only (no full phase picker, per spec non-goals): "Mark done"
                // sets an override to done; "Reopen" clears it so the item falls back to its
                // server-derived phase. Both are always offered — the item's own phase already
                // tells the operator which one is a no-op.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    TextButton(onClick = onMarkDone) { Text("Mark done", maxLines = 1) }
                    TextButton(onClick = onReopen) { Text("Reopen", maxLines = 1) }
                }
            }
        },
        modifier = if (routable) Modifier.clickable { onClick() } else Modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttentionBadges(a: Attention) {
    val c = LocalSemanticColors.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (a.needsApproval) Badge("approve", c.approval)
        if (a.needsDecision) Badge("decide", c.question)
        if (a.blocked) Badge("blocked ${a.blockedTasks}/${a.totalTasks}", c.blocker)
        if (a.needsReview) Badge("review", c.question)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MonoStyle, color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "bug" -> Icons.Filled.BugReport
    "chore" -> Icons.Filled.Build
    "question" -> Icons.AutoMirrored.Filled.HelpOutline
    else -> Icons.Filled.Description // feature
}

private fun subLine(item: WorkItemSummary): String = when {
    item.attention.totalTasks > 0 ->
        "${item.attention.totalTasks} task(s)" +
            (if (item.attention.blockedTasks > 0) " · ${item.attention.blockedTasks} blocked" else "")
    item.specId != null -> "spec"
    item.threadIds.isNotEmpty() -> "conversation"
    else -> item.kind
}
