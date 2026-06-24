package com.atomikpanda.groundcontrol.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    vm: WorkspaceViewModel,
    workspaceName: String,
    onThread: (threadId: String) -> Unit,
    onSpec: (specId: String) -> Unit,
    onTask: (slug: String) -> Unit,
    onNewConversation: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewConversation,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New conversation") },
            )
        },
    ) { padding ->
        when (val s = state) {
            is WorkspaceUiState.Loading ->
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            is WorkspaceUiState.Content -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (s.errored) {
                    item {
                        AssistChip(
                            onClick = {},
                            label = { Text("Couldn't load everything — pull to refresh.") },
                            modifier = Modifier.padding(12.dp, 4.dp),
                        )
                    }
                }
                item { Header("Conversations") }
                items(s.threads.size, key = { "t:${s.threads[it].id}" }) { i ->
                    val t = s.threads[i]
                    ListItem(
                        headlineContent = { Text(t.subject.ifBlank { t.id }) },
                        supportingContent = { Text(t.lastMessage) },
                        modifier = Modifier.clickable { onThread(t.id) },
                    )
                }
                item { Header("Specs") }
                items(s.specs.size, key = { "s:${s.specs[it].id}" }) { i ->
                    val sp = s.specs[i]
                    ListItem(
                        headlineContent = { Text(sp.title) },
                        supportingContent = { Text(sp.status) },
                        modifier = Modifier.clickable { onSpec(sp.id) },
                    )
                }
                item { Header("Tasks") }
                items(s.tasks.size, key = { "k:${s.tasks[it].slug}" }) { i ->
                    val k = s.tasks[i]
                    ListItem(
                        headlineContent = { Text(k.slug) },
                        supportingContent = { Text(k.phase) },
                        modifier = Modifier.clickable { onTask(k.slug) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
