package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewThreadScreen(
    vm: NewThreadViewModel,
    onCreated: (connectionId: String, threadId: String) -> Unit,
    onBack: () -> Unit,
    initialConnectionId: String? = null,
    title: String = "New thread",
    showSubject: Boolean = true,
    bodyLabel: String = "Message",
    submitLabel: String = "Create",
    showKindPicker: Boolean = false,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    // Preselect the scoped workspace once connections are available.
    LaunchedEffect(state.connections, initialConnectionId) {
        if (initialConnectionId != null && state.connections.any { it.id == initialConnectionId }) {
            vm.onSelectConnection(initialConnectionId)
        }
    }

    // Navigate when creation succeeds
    LaunchedEffect(state.message) {
        val msg = state.message
        if (msg is NewThreadMessage.Created) {
            onCreated(msg.connectionId, msg.threadId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (state.connections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), Alignment.Center) {
                Text("Add a workspace in Settings to start a thread.")
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Workspace picker: only shown when >1 connection; auto-selected when exactly 1.
            if (state.connections.size > 1) {
                val selected = state.connections.firstOrNull { it.id == state.selectedConnectionId }
                WorkspacePickerDropdown(
                    label = selected?.let { it.workspaceName.ifBlank { it.baseUrl } } ?: "Select workspace",
                    options = state.connections.map { it.id to it.workspaceName.ifBlank { it.baseUrl } },
                    onPick = vm::onSelectConnection,
                )
            }

            if (showKindPicker) {
                WorkspacePickerDropdown(
                    label = when (state.kind) {
                        CaptureKind.QUICK_NOTE -> "Quick note"
                        CaptureKind.BRAINSTORM_SPEC -> "Brainstorm into a spec"
                    },
                    options = listOf(
                        CaptureKind.QUICK_NOTE.name to "Quick note",
                        CaptureKind.BRAINSTORM_SPEC.name to "Brainstorm into a spec",
                    ),
                    onPick = { vm.onSelectKind(CaptureKind.valueOf(it)) },
                    prefix = "",
                )
            }

            if (showSubject) {
                OutlinedTextField(
                    value = state.subject,
                    onValueChange = vm::onSubjectChange,
                    label = { Text("Subject (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = vm::onTextChange,
                label = { Text(bodyLabel) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { vm.create() },
                enabled = canCreate(state),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.inFlight) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                } else {
                    Text(submitLabel)
                }
            }

            when (val m = state.message) {
                is NewThreadMessage.Error -> {
                    Column(Modifier.fillMaxWidth()) {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = vm::dismissMessage) { Text("Dismiss") }
                    }
                }
                is NewThreadMessage.Created, null -> {}
            }
        }
    }
}

@Composable
private fun WorkspacePickerDropdown(
    label: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    prefix: String = "Workspace",
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (prefix.isBlank()) label else "$prefix: $label")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}
