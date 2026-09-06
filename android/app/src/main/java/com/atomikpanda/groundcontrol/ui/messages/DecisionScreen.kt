package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.dto.Thread

/** A short path for resolving the active question without entering the transcript. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionScreen(
    vm: DecisionViewModel,
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenSpec: (String) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var response by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Decision") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenConversation) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Open conversation")
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            DecisionUiState.Loading -> LoadingDecision(Modifier.padding(padding))
            is DecisionUiState.Unavailable -> MessageDecision(current.message, Modifier.padding(padding))
            is DecisionUiState.Error -> MessageDecision(current.message, Modifier.padding(padding))
            is DecisionUiState.NoLongerActionable -> StaticDecision(
                text = current.message,
                thread = current.thread,
                modifier = Modifier.padding(padding),
                onOpenItem = onOpenItem,
                onOpenSpec = onOpenSpec,
                onOpenTask = onOpenTask,
            )
            is DecisionUiState.ResponseRecorded -> StaticDecision(
                text = "Response sent and recorded.",
                thread = current.thread,
                modifier = Modifier.padding(padding),
                onOpenItem = onOpenItem,
                onOpenSpec = onOpenSpec,
                onOpenTask = onOpenTask,
            )
            is DecisionUiState.Content -> DecisionContent(
                content = current,
                response = response,
                onResponseChange = { response = it },
                onSubmitResponse = { vm.submit(response) },
                onOption = vm::submit,
                modifier = Modifier.padding(padding),
                onOpenItem = onOpenItem,
                onOpenSpec = onOpenSpec,
                onOpenTask = onOpenTask,
            )
        }
    }
}

@Composable
private fun LoadingDecision(modifier: Modifier) {
    Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun MessageDecision(message: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DecisionContent(
    content: DecisionUiState.Content,
    response: String,
    onResponseChange: (String) -> Unit,
    onSubmitResponse: () -> Unit,
    onOption: (String) -> Unit,
    modifier: Modifier,
    onOpenItem: (String) -> Unit,
    onOpenSpec: (String) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val decision = content.prompt.decision
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DecisionContext(content.thread, onOpenItem, onOpenSpec, onOpenTask)

        if (decision != null && decision.options.isNotEmpty()) {
            DecisionCard(
                text = content.prompt.text,
                decision = decision,
                enabled = !content.inFlight,
                onOption = onOption,
            )
        } else {
            Text(content.prompt.text, style = MaterialTheme.typography.titleMedium)
        }

        // A plain needs-you question has no Decision payload, while a typed decision can opt in
        // to the same short response field through allow_free_text. No alternatives are invented.
        if (decision?.allowFreeText != false) {
            OutlinedTextField(
                value = response,
                onValueChange = onResponseChange,
                label = { Text("Short response") },
                minLines = 2,
                maxLines = 4,
                enabled = !content.inFlight,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSubmitResponse,
                enabled = response.isNotBlank() && !content.inFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (content.inFlight) CircularProgressIndicator() else Text("Send response")
            }
        }

        content.sendError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StaticDecision(
    text: String,
    thread: Thread?,
    modifier: Modifier,
    onOpenItem: (String) -> Unit,
    onOpenSpec: (String) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        thread?.let { DecisionContext(it, onOpenItem, onOpenSpec, onOpenTask) }
    }
}

@Composable
private fun DecisionContext(
    thread: Thread,
    onOpenItem: (String) -> Unit,
    onOpenSpec: (String) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    thread.workItem?.let { item ->
        ElevatedCard(onClick = { onOpenItem(item.id) }, modifier = Modifier.fillMaxWidth()) {
            ListItem(
                overlineContent = { Text("Related work item") },
                headlineContent = { Text(item.title.ifBlank { item.id }) },
                supportingContent = {
                    val details = listOf(item.kind, item.phase).filter { it.isNotBlank() }
                    if (details.isNotEmpty()) Text(details.joinToString(" · ").replace('_', ' '))
                },
            )
        }
    }
    thread.specId?.let { specId ->
        OutlinedButton(onClick = { onOpenSpec(specId) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open linked spec")
        }
    }
    thread.taskSlug?.let { taskSlug ->
        OutlinedButton(onClick = { onOpenTask(taskSlug) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open linked task")
        }
    }
}
