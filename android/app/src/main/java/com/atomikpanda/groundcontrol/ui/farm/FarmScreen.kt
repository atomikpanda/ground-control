package com.atomikpanda.groundcontrol.ui.farm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            is FarmUiState.Loading -> Box(Modifier.fillMaxSize().padding(pad)) {
                Text("Loading…", Modifier.padding(24.dp))
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
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FarmCard(item: WorkItemSummary, onClick: () -> Unit, onToggleUnattended: (Boolean) -> Unit) {
    // Only present a tap affordance when there's somewhere to go. A brand-new inbox item can
    // legally have no spec/task/thread yet; making such a card non-clickable avoids a silent
    // dead-tap (until the per-phase cockpits give every item its own destination).
    val routable = item.specId != null || item.taskSlugs.isNotEmpty() || item.threadIds.isNotEmpty()
    ListItem(
        leadingContent = { Icon(kindIcon(item.kind), contentDescription = item.kind) },
        headlineContent = { Text(item.title) },
        supportingContent = {
            // The unattended toggle lives here (not trailingContent) so it gets a full row's
            // width for its label rather than squeezing next to the attention badges — this is
            // a per-card control with its own tap target, so it needs to sit inside the row's
            // touch-transparent area without fighting the whole-card click for routable items
            // (Compose gives the inner Switch's own gesture detector priority for taps on it).
            Column {
                Text(subLine(item), style = MonoStyle)
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
            }
        },
        trailingContent = { AttentionBadges(item.attention) },
        modifier = if (routable) Modifier.clickable { onClick() } else Modifier,
    )
}

@Composable
private fun AttentionBadges(a: Attention) {
    val c = LocalSemanticColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
