package com.atomikpanda.groundcontrol.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.messages.MessagesUiState
import com.atomikpanda.groundcontrol.ui.messages.MessagesViewModel
import com.atomikpanda.groundcontrol.ui.messages.ThreadStateChipRow
import com.atomikpanda.groundcontrol.ui.messages.unreadCountFor
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.components.WorkspaceBadge
import com.atomikpanda.groundcontrol.ui.theme.LocalWorkspaceIdentityResolver
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle
import com.atomikpanda.groundcontrol.ui.theme.chipHue

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    messagesVm: MessagesViewModel,
    onApproval: (connectionId: String, specId: String) -> Unit,
    onQuestion: (connectionId: String, threadId: String) -> Unit,
    onBlocker: (connectionId: String, slug: String) -> Unit,
    onBrowseWorkspace: (connectionId: String) -> Unit,
    onCapture: () -> Unit,
    onOpenThreads: () -> Unit,
    onReviewInQueue: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val messagesState by messagesVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) {
        // Start live polling from Home too — the sticky card's unread count + peek must stay live
        // even if the user never opens the Threads drill-in list (startLivePolling is idempotent
        // per connection, so MessagesScreen calling it again later is safe).
        messagesVm.refresh()?.join()
        messagesVm.startLivePolling()
    }

    Scaffold(
        floatingActionButton = {
            // Only offer Capture once a workspace exists; on Loading/EmptyConfig
            // it would dead-end at the "add a workspace" empty state.
            if (state is HomeUiState.Content) {
                ExtendedFloatingActionButton(
                    onClick = onCapture,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Capture") },
                )
            }
        },
    ) { innerPadding ->
        when (val s = state) {
            is HomeUiState.Loading -> Box(Modifier.fillMaxSize().padding(innerPadding), Alignment.Center) { CircularProgressIndicator() }
            is HomeUiState.EmptyConfig -> Box(Modifier.fillMaxSize().padding(innerPadding), Alignment.Center) {
                Text("Add a workspace in Settings to get started.")
            }
            is HomeUiState.Content -> LazyColumn(
                Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                // Workspace chip rail
                item {
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp, 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.rail, key = { it.connectionId ?: "all" }) { chip ->
                            FilterChip(
                                selected = chip.connectionId == s.selectedConnectionId,
                                onClick = {
                                    vm.select(chip.connectionId)
                                    // Keep the threads sticky card + drill-in list scoped to the
                                    // same workspace selection as the needs-you queue (AC5).
                                    messagesVm.selectWorkspace(chip.connectionId)
                                },
                                label = {
                                    if (chip.count > 0) {
                                        Text(buildAnnotatedString {
                                            append("${chip.label} · ")
                                            withStyle(SpanStyle(fontFamily = MonoStyle.fontFamily)) {
                                                append("${chip.count}")
                                            }
                                        })
                                    } else {
                                        Text(chip.label)
                                    }
                                },
                            )
                        }
                    }
                }
                // Per-connection error indicators
                items(s.errors, key = { "err:${it.connectionId}" }) { err ->
                    val colors = LocalSemanticColors.current
                    AssistChip(
                        onClick = {},
                        label = { Text("${err.workspaceName} unreachable", color = colors.error) },
                        modifier = Modifier.padding(12.dp, 4.dp),
                    )
                }
                // "Browse this workspace" when scoped to one
                val sel = s.selectedConnectionId
                if (sel != null) {
                    item {
                        TextButton(
                            onClick = { onBrowseWorkspace(sel) },
                            modifier = Modifier.padding(8.dp, 0.dp),
                        ) {
                            Text("Browse all in this workspace →")
                        }
                    }
                }
                // Needs-you leads the content (spec gc-uiux-finish; operator ordering: rail stays on
                // top, needs-you sits just above the threads card). Header + count + one-tap funnel
                // into the Queue tab; zero items shows a calm caught-up state with no funnel.
                item {
                    NeedsYouHeader(
                        count = s.items.size,
                        onReviewInQueue = onReviewInQueue,
                    )
                }
                items(s.items, key = { it.key }) { item ->
                    NeedsYouRow(item, onApproval, onQuestion, onBlocker)
                }
                // Sticky threads card — slim entry point into the full threads list, shown below the
                // needs-you queue (spec gc-uiux-finish reordered needs-you above it; the workspace
                // rail still leads). Home stays needs-you-first for its primary content.
                if (messagesState is MessagesUiState.Content) {
                    val ms = messagesState as MessagesUiState.Content
                    item {
                        ThreadsStickyCard(
                            unreadCount = ms.unreadCountFor(s.selectedConnectionId),
                            peek = messagesVm.topThreads(3, s.selectedConnectionId),
                            onClick = onOpenThreads,
                        )
                    }
                    item {
                        ThreadStateChipRow(
                            selected = ms.stateFilter,
                            onSelect = { messagesVm.selectStateFilter(it) },
                        )
                    }
                }
                // Quiet "New messages" section for unseen plain notes
                if (s.notes.isNotEmpty()) {
                    item {
                        Text(
                            "New messages",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(s.notes, key = { "note:${it.connectionId}:${it.threadId}" }) { note ->
                        NewMessageRow(note, onQuestion)
                    }
                }
            }
        }
    }
}

/**
 * Slim card pinned above the needs-you queue: an unread badge + a compact peek of the 2-3 most
 * recently active threads (answered or not). Any tap in the card — the header or a peek row —
 * opens the full drill-in threads list (spec: ground-control-thread-findability, AC1/AC3). Home
 * otherwise stays needs-you-only; this is the app's only new entry point into "every conversation."
 */
@Composable
private fun ThreadsStickyCard(unreadCount: Int, peek: List<ThreadSummary>, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp, 4.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp, 10.dp)) {
            // Header + unread count live in their own row slots (title leading, badge
            // trailing) rather than a BadgedBox anchored on the "Threads" text — a
            // BadgedBox's default corner anchor sat right on top of the last glyph of
            // short text like this, overlapping it instead of sitting beside it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Threads",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (unreadCount > 0) {
                    Badge { Text("$unreadCount") }
                }
            }
            if (peek.isEmpty()) {
                Text(
                    "No conversations yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                peek.forEach { thread ->
                    Text(
                        thread.subject.ifBlank { "(no subject)" },
                        style = MaterialTheme.typography.bodyMedium,
                        // Full contrast (matches the onSurface role agent prose bubbles
                        // use elsewhere) — the previous default content color read as a
                        // muted onSurfaceVariant-ish tone and was hard to read.
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (thread.unseen) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (thread.lastMessage.isNotBlank()) {
                        Text(
                            thread.lastMessage,
                            style = MaterialTheme.typography.bodySmall,
                            // Secondary line: legible but still visually subordinate to
                            // the subject line above.
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedsYouHeader(count: Int, onReviewInQueue: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp)) {
        Text(
            needsYouHeader(count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        reviewInQueueCta(count)?.let { cta ->
            TextButton(
                onClick = onReviewInQueue,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(cta)
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun NeedsYouRow(
    item: NeedsYouItem,
    onApproval: (String, String) -> Unit,
    onQuestion: (String, String) -> Unit,
    onBlocker: (String, String) -> Unit,
) {
    val colors = LocalSemanticColors.current
    val accent = accentFor(item.tier, colors)
    // Tier is otherwise signaled by accent color alone; surface it as a word (visible label + icon
    // contentDescription) so it survives color-blindness and screen readers.
    val tierLabel = when (item) {
        is NeedsYouItem.Blocker -> "Blocked"
        is NeedsYouItem.Question -> "Question"
        is NeedsYouItem.Approval -> "Review"
    }
    val (icon, title, supporting, onClick) = when (item) {
        is NeedsYouItem.Blocker -> RowSpec(Icons.Filled.Block, "Blocked: ${item.taskSlug}", item.reason) {
            onBlocker(item.connectionId, item.taskSlug)
        }
        is NeedsYouItem.Question -> RowSpec(Icons.AutoMirrored.Filled.Chat, item.subject, item.lastMessage) {
            onQuestion(item.connectionId, item.threadId)
        }
        is NeedsYouItem.Approval -> RowSpec(Icons.Filled.CheckCircle, item.title, "ready to review") {
            onApproval(item.connectionId, item.specId)
        }
    }
    val wsIdentity = LocalWorkspaceIdentityResolver.current(item.connectionId, item.workspaceName)
    ListItem(
        leadingContent = { Icon(icon, contentDescription = tierLabel, tint = accent) },
        overlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkspaceBadge(wsIdentity, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(item.workspaceName, style = MonoStyle)
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = { Text(tierLabel, style = MaterialTheme.typography.labelSmall, color = accent) },
        modifier = Modifier.clickable { onClick() },
    )
}

@Composable
private fun NewMessageRow(note: NewMessageNote, onQuestion: (String, String) -> Unit) {
    ListItem(
        leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "New message") },
        overlineContent = { Text(note.workspaceName, style = MonoStyle) },
        headlineContent = { Text(note.subject) },
        supportingContent = { Text(note.lastMessage) },
        modifier = Modifier.clickable { onQuestion(note.connectionId, note.threadId) },
    )
}

/** Tiny holder so the when-expression can destructure. */
private data class RowSpec(val a: androidx.compose.ui.graphics.vector.ImageVector, val b: String, val c: String, val d: () -> Unit)
