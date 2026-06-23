package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Pull-to-refresh uses the material3 1.2.1 API (`PullToRefreshContainer` +
 * `rememberPullToRefreshState()`): `PullToRefreshBox` was only added in 1.3.0.
 * Mirrors SpecInboxScreen — section/error-chip/empty-config, FAB for new thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    vm: MessagesViewModel,
    onThreadClick: (connectionId: String, threadId: String) -> Unit,
    onNewThread: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            vm.refresh()?.join()
            pullState.endRefresh()
        }
    }

    Scaffold(
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
                    s.sections.forEach { section ->
                        item {
                            Text(
                                section.workspaceName,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(16.dp, 12.dp),
                            )
                        }
                        section.threads.fold(
                            onSuccess = { threads ->
                                items(threads.size, key = { threads[it].id }) { i ->
                                    val thread = threads[i]
                                    val replyMarker = if (thread.awaitingReply) "⏳ waiting" else "✓ replied"
                                    val supporting = buildString {
                                        if (thread.lastMessage.isNotBlank()) append(thread.lastMessage)
                                        append(" · $replyMarker")
                                        if (thread.updatedAt != null) append(" · ${thread.updatedAt}")
                                    }
                                    ListItem(
                                        headlineContent = { Text(thread.subject.ifBlank { "(no subject)" }) },
                                        supportingContent = { Text(supporting) },
                                        modifier = Modifier.clickable { onThreadClick(section.connectionId, thread.id) },
                                    )
                                }
                            },
                            onFailure = {
                                item {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("unreachable") },
                                        modifier = Modifier.padding(16.dp, 4.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}
