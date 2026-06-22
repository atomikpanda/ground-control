package com.atomikpanda.groundcontrol.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(vm: TaskDetailViewModel, title: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    // Show the loaded task description once available; fall back to the slug pre-load.
    val displayTitle = (state as? TaskDetailUiState.Content)?.task?.description?.takeIf { it.isNotBlank() } ?: title

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                TaskDetailUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is TaskDetailUiState.Error -> ErrorView(s, vm, onBack)
                is TaskDetailUiState.Content -> ContentView(s, vm)
            }
        }
    }
}

@Composable
private fun ErrorView(s: TaskDetailUiState.Error, vm: TaskDetailViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        val msg = when (s.kind) {
            ErrorKind.AUTH -> "Token rejected. Fix this connection in Settings."
            ErrorKind.NOT_FOUND -> "This task is no longer available."
            ErrorKind.NETWORK -> s.message
        }
        Text(msg, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.kind == ErrorKind.NETWORK) Button(onClick = { vm.load() }) { Text("Retry") }
            if (s.kind == ErrorKind.NOT_FOUND) Button(onClick = onBack) { Text("Back to tasks") }
            if (s.kind == ErrorKind.AUTH) OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentView(s: TaskDetailUiState.Content, vm: TaskDetailViewModel) {
    val task = s.task
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) LaunchedEffect(true) { vm.load().join(); pull.endRefresh() }

    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(Modifier.fillMaxSize()) {
            // Header: description + phase + branch
            item {
                Column(Modifier.padding(16.dp, 8.dp)) {
                    Text(
                        "● ${task.phase}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (task.branch.isNotBlank()) {
                        Text(
                            "branch: ${task.branch}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (task.description.isNotBlank()) {
                        Text(
                            task.description,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // Blocked banner
            task.blockedReason?.takeIf { it.isNotBlank() }?.let { reason ->
                item {
                    Surface(
                        Modifier.fillMaxWidth().padding(16.dp, 4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        tonalElevation = 2.dp,
                    ) {
                        Text(
                            "Blocked: $reason",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Affected repos
            if (task.affectedRepos.isNotEmpty()) {
                item { SectionLabel("AFFECTED REPOS") }
                items(task.affectedRepos) { BulletText(it) }
            }

            // Per-repo test results
            if (task.testResults.isNotEmpty()) {
                item { SectionLabel("TEST RESULTS") }
                items(task.testResults.entries.toList()) { (repo, status) ->
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp, 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(repo, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status == "green") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // PR links
            if (task.prUrls.isNotEmpty()) {
                item { SectionLabel("PULL REQUESTS") }
                items(task.prUrls.entries.toList()) { (repo, url) ->
                    val uriHandler = LocalUriHandler.current
                    Text(
                        "$repo PR ↗",
                        modifier = Modifier
                            .clickable { uriHandler.openUri(url) }
                            .padding(16.dp, 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Depends on
            if (task.dependsOn.isNotEmpty()) {
                item { SectionLabel("DEPENDS ON") }
                items(task.dependsOn) { BulletText(it) }
            }

            // Journal timeline
            if (s.journal.isNotEmpty()) {
                item { SectionLabel("JOURNAL") }
                items(s.journal) { entry -> JournalEntryRow(entry) }
            }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun JournalEntryRow(entry: JournalEntry) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                entry.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            // Labels: action, testState, repo
            val labels = listOfNotNull(entry.action, entry.testState, entry.repo)
            if (labels.isNotEmpty()) {
                Text(
                    labels.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Text(entry.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        // Highlight open question
        entry.openQuestion?.takeIf { it.isNotBlank() }?.let { q ->
            Text(
                "? $q",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
    )

@Composable
private fun BulletText(text: String) =
    Text("• $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 2.dp))
