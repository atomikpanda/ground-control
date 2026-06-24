package com.atomikpanda.groundcontrol.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onApproval: (connectionId: String, specId: String) -> Unit,
    onQuestion: (connectionId: String, threadId: String) -> Unit,
    onBlocker: (connectionId: String, slug: String) -> Unit,
    onBrowseWorkspace: (connectionId: String) -> Unit,
    onCapture: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

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
                                onClick = { vm.select(chip.connectionId) },
                                label = { Text(if (chip.count > 0) "${chip.label} · ${chip.count}" else chip.label) },
                            )
                        }
                    }
                }
                // Per-connection error indicators
                items(s.errors, key = { "err:${it.connectionId}" }) { err ->
                    AssistChip(
                        onClick = {},
                        label = { Text("${err.workspaceName} unreachable") },
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
                // Empty state
                if (s.items.isEmpty() && s.errors.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            Alignment.Center,
                        ) {
                            Text("Nothing needs you right now.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                // The "Needs you" queue
                items(s.items, key = { it.key }) { item ->
                    NeedsYouRow(item, onApproval, onQuestion, onBlocker)
                }
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
    val (leading, title, supporting, onClick) = when (item) {
        is NeedsYouItem.Blocker -> Quad("⛔", "Blocked: ${item.taskSlug}", item.reason) {
            onBlocker(item.connectionId, item.taskSlug)
        }
        is NeedsYouItem.Question -> Quad("💬", item.subject, item.lastMessage) {
            onQuestion(item.connectionId, item.threadId)
        }
        is NeedsYouItem.Approval -> Quad("✅", item.title, "ready to review") {
            onApproval(item.connectionId, item.specId)
        }
    }
    ListItem(
        leadingContent = { Text(leading) },
        overlineContent = { Text(item.workspaceName, fontWeight = FontWeight.SemiBold) },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        modifier = Modifier.clickable { onClick() },
    )
}

/** Tiny holder so the when-expression can destructure. */
private data class Quad(val a: String, val b: String, val c: String, val d: () -> Unit)
