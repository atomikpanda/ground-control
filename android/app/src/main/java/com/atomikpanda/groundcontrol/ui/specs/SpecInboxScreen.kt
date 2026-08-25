package com.atomikpanda.groundcontrol.ui.specs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.atomikpanda.groundcontrol.data.dto.InboxAction
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.ui.inbox.InboxTab

/** Pull-to-refresh uses the material3 1.2.1 API (`PullToRefreshContainer` +
 * `rememberPullToRefreshState()`): `PullToRefreshBox` was only added in 1.3.0. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecInboxScreen(vm: SpecInboxViewModel, onSpecClick: (connectionId: String, specId: String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            vm.refresh()?.join()
            pullState.endRefresh()
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        when (val s = state) {
            InboxUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            InboxUiState.EmptyConfig -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Add an mship serve endpoint in Settings.")
            }
            is InboxUiState.Content -> {
                val hasSpecs = s.sections.any { section ->
                    section.groups.getOrDefault(emptyList()).any { it.specs.isNotEmpty() }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        TabRow(selectedTabIndex = s.tab.ordinal) {
                            InboxTab.entries.forEach { tab ->
                                Tab(
                                    selected = s.tab == tab,
                                    onClick = { vm.selectInboxTab(tab) },
                                    text = { Text(tab.label) },
                                )
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = s.searchQuery,
                            onValueChange = vm::onSearchQueryChange,
                            label = { Text("Search ${s.tab.label.lowercase()} specs") },
                            modifier = Modifier.fillMaxWidth().padding(12.dp, 8.dp),
                            singleLine = true,
                        )
                    }
                    if (!hasSpecs) {
                        item {
                            Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                                Text(if (s.tab == InboxTab.ACTIVE) "No active specs." else "No archived specs.")
                            }
                        }
                    }
                    s.sections.forEach { section ->
                        item {
                            Text(
                                section.workspaceName,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(16.dp, 12.dp),
                            )
                        }
                        section.groups.fold(
                            onSuccess = { blocks ->
                                blocks.forEach { block ->
                                    item {
                                        Text(
                                            block.group.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(16.dp, 4.dp),
                                        )
                                    }
                                    items(block.specs, key = { it.id }) { spec ->
                                        SpecInboxRow(
                                            spec = spec,
                                            onClick = { onSpecClick(section.connectionId, spec.id) },
                                            onAction = { action -> vm.mutateInbox(section.connectionId, spec.id, action) },
                                        )
                                    }
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
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** Direct labels make the durable inbox actions available to TalkBack and keyboard users. */
@Composable
private fun SpecInboxRow(
    spec: SpecSummary,
    onClick: () -> Unit,
    onAction: (InboxAction) -> Unit,
) {
    ListItem(
        headlineContent = { Text(spec.title) },
        supportingContent = { Text("${spec.status} · ${spec.affectedRepos.joinToString().ifBlank { "—" }}") },
        trailingContent = {
            Row {
                TextButton(onClick = { onAction(if (spec.pinned) InboxAction.UNPIN else InboxAction.PIN) }) {
                    Text(if (spec.pinned) "Unpin" else "Pin")
                }
                TextButton(
                    onClick = {
                        onAction(
                            if (spec.inboxState == InboxTab.ARCHIVED.state) InboxAction.RESTORE
                            else InboxAction.ARCHIVE,
                        )
                    },
                ) {
                    Text(if (spec.inboxState == InboxTab.ARCHIVED.state) "Restore" else "Archive")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
