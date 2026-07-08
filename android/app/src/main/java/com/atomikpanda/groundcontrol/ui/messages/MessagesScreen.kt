package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle

/**
 * Threads drill-in list (not a bottom-nav tab): reached from the Home sticky threads card.
 * Renders `filteredThreads` (already workspace + state filtered, newest-first) and starts the
 * ViewModel's live long-poll loop so the list updates as messages arrive.
 *
 * Pull-to-refresh uses the material3 1.2.1 API (`PullToRefreshContainer` +
 * `rememberPullToRefreshState()`): `PullToRefreshBox` was only added in 1.3.0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    vm: MessagesViewModel,
    onThreadClick: (connectionId: String, threadId: String) -> Unit,
    onNewThread: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        // The activity-scoped VM may already hold fresh Content (loaded by HomeScreen) — only
        // force a reload+spinner when there's nothing to show yet. Always (re)start polling so
        // this screen stays live even if Home's own startLivePolling() call raced or was skipped.
        if (vm.state.value !is MessagesUiState.Content) {
            vm.refresh()?.join()
        }
        vm.startLivePolling()
    }

    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            vm.refresh()?.join()
            pullState.endRefresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Threads") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewThread) {
                Icon(Icons.Filled.Edit, contentDescription = "New thread")
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            when (val s = state) {
                MessagesUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                MessagesUiState.EmptyConfig -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Add a workspace in Settings to see messages.")
                }
                is MessagesUiState.Content -> LazyColumn(Modifier.fillMaxSize()) {
                    item { ThreadsWorkspaceRail(s, vm::selectWorkspace) }
                    item { ThreadStateChipRow(s.stateFilter, vm::selectStateFilter) }
                    if (s.filteredThreads.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                                Text("No threads here yet.")
                            }
                        }
                    }
                    items(s.filteredThreads.size, key = { s.filteredThreads[it].thread.id }) { i ->
                        val filtered = s.filteredThreads[i]
                        ThreadRow(filtered.thread) { onThreadClick(filtered.connectionId, filtered.thread.id) }
                    }
                }
            }
            PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

/** Row 1: workspace rail (All + per-workspace), styled to match the Home workspace rail
 *  (`HomeScreen`'s `FilterChip` + monospace count). Scopes [MessagesUiState.Content.filteredThreads]
 *  via [MessagesViewModel.selectWorkspace]. */
@Composable
private fun ThreadsWorkspaceRail(state: MessagesUiState.Content, onSelect: (String?) -> Unit) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(12.dp, 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            WorkspaceChip(
                label = "All",
                count = state.unreadCount,
                selected = state.selectedConnectionId == null,
                onClick = { onSelect(null) },
            )
        }
        items(state.sections, key = { it.connectionId }) { sec ->
            WorkspaceChip(
                label = sec.workspaceName,
                count = state.unreadCountsByWorkspace[sec.connectionId] ?: 0,
                selected = state.selectedConnectionId == sec.connectionId,
                onClick = { onSelect(sec.connectionId) },
            )
        }
    }
}

@Composable
private fun WorkspaceChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            if (count > 0) {
                Text(buildAnnotatedString {
                    append("$label · ")
                    withStyle(SpanStyle(fontFamily = MonoStyle.fontFamily)) {
                        append("$count")
                    }
                })
            } else {
                Text(label)
            }
        },
    )
}

/** Row 2: thread-STATE filter chips (All / Unread / Needs-you) — its own row, never merged into
 *  the workspace rail. Shared by the Home sticky-card area and this drill-in list (spec:
 *  ground-control-thread-findability) so both surfaces look and behave identically. */
@Composable
internal fun ThreadStateChipRow(selected: ThreadStateFilter, onSelect: (ThreadStateFilter) -> Unit) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(12.dp, 0.dp, 12.dp, 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ThreadStateFilter.entries.toList(), key = { it.name }) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.chipLabel()) },
            )
        }
    }
}

private fun ThreadStateFilter.chipLabel(): String = when (this) {
    ThreadStateFilter.ALL -> "All"
    ThreadStateFilter.UNREAD -> "Unread"
    ThreadStateFilter.NEEDS_YOU -> "Needs you"
}

/** One thread row. `unseen` threads get a bold headline + a small leading dot — the "unread
 *  badge/highlight" from AC4 — on top of the list's newest-first ordering. */
@Composable
private fun ThreadRow(thread: ThreadSummary, onClick: () -> Unit) {
    val replyMarker = if (thread.awaitingReply) "⏳ waiting" else "✓ replied"
    val supporting = buildString {
        if (thread.lastMessage.isNotBlank()) append(thread.lastMessage)
        append(" · $replyMarker")
        if (thread.updatedAt != null) append(" · ${thread.updatedAt}")
    }
    ListItem(
        leadingContent = if (thread.unseen) {
            {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        } else null,
        headlineContent = {
            Text(
                thread.subject.ifBlank { "(no subject)" },
                fontWeight = if (thread.unseen) FontWeight.Bold else FontWeight.Normal,
            )
        },
        supportingContent = { Text(supporting) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
