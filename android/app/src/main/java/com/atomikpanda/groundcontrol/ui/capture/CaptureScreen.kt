package com.atomikpanda.groundcontrol.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@Composable
fun CaptureScreen(vm: CaptureViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (state.connections.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("Add a workspace in Settings to capture a spec.")
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("New spec", style = MaterialTheme.typography.titleLarge)

        val selected = state.connections.firstOrNull { it.id == state.selectedConnectionId }
        WorkspacePicker(
            label = selected?.let { it.workspaceName.ifBlank { it.baseUrl } } ?: "Select workspace",
            options = state.connections.map { it.id to it.workspaceName.ifBlank { it.baseUrl } },
            onPick = vm::onSelectConnection,
        )

        OutlinedTextField(
            value = state.title, onValueChange = vm::onTitleChange,
            label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.repos, onValueChange = vm::onReposChange,
            label = { Text("Affected repos (comma-separated, optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { vm.create() },
            enabled = canCreate(state),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.inFlight) CircularProgressIndicator(Modifier.size(18.dp))
            Text("Create")
        }

        when (val m = state.message) {
            is CaptureMessage.Created -> MessageRow("Created ${m.specId} — pull-to-refresh the inbox to see it.") { vm.dismissMessage() }
            is CaptureMessage.Error -> MessageRow(m.text) { vm.dismissMessage() }
            null -> {}
        }
    }
}

@Composable
private fun WorkspacePicker(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Workspace: $label")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

@Composable
private fun MessageRow(text: String, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
