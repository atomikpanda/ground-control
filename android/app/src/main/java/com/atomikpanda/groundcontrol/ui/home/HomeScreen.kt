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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle
import com.atomikpanda.groundcontrol.ui.theme.chipHue

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
                // Empty state
                if (s.items.isEmpty() && s.notes.isEmpty() && s.errors.isEmpty()) {
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

@Composable
private fun NeedsYouRow(
    item: NeedsYouItem,
    onApproval: (String, String) -> Unit,
    onQuestion: (String, String) -> Unit,
    onBlocker: (String, String) -> Unit,
) {
    val colors = LocalSemanticColors.current
    val accent = accentFor(item.tier, colors)
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
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = accent) },
        overlineContent = { Text(item.workspaceName, style = MonoStyle, color = chipHue(item.connectionId, colors)) },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        modifier = Modifier.clickable { onClick() },
    )
}

@Composable
private fun NewMessageRow(note: NewMessageNote, onQuestion: (String, String) -> Unit) {
    ListItem(
        leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
        overlineContent = { Text(note.workspaceName, style = MonoStyle) },
        headlineContent = { Text(note.subject) },
        supportingContent = { Text(note.lastMessage) },
        modifier = Modifier.clickable { onQuestion(note.connectionId, note.threadId) },
    )
}

/** Tiny holder so the when-expression can destructure. */
private data class RowSpec(val a: androidx.compose.ui.graphics.vector.ImageVector, val b: String, val c: String, val d: () -> Unit)
