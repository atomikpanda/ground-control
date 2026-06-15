package com.atomikpanda.groundcontrol.ui.specs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
 * A user pull triggers `state.startRefresh()`; the LaunchedEffect bridges that
 * to `vm.refresh()` and ends the indicator when the refresh job completes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecInboxScreen(vm: SpecInboxViewModel) {
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
            is InboxUiState.Content -> LazyColumn(Modifier.fillMaxSize()) {
                s.sections.forEach { section ->
                    item { Text(section.workspaceName, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 12.dp)) }
                    section.groups.fold(
                        onSuccess = { blocks ->
                            blocks.forEach { block ->
                                item { Text(block.group.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 4.dp)) }
                                items(block.specs.size) { i ->
                                    val spec = block.specs[i]
                                    ListItem(
                                        headlineContent = { Text(spec.title) },
                                        supportingContent = { Text("${spec.status} · ${spec.affectedRepos.joinToString().ifBlank { "—" }}") },
                                    )
                                }
                            }
                        },
                        onFailure = { item { AssistChip(onClick = {}, label = { Text("unreachable") }, modifier = Modifier.padding(16.dp, 4.dp)) } },
                    )
                }
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}
