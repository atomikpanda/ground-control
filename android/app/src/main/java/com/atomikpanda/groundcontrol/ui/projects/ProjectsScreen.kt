package com.atomikpanda.groundcontrol.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.ui.components.WorkspaceBadge
import com.atomikpanda.groundcontrol.ui.theme.WorkspacePalette
import com.atomikpanda.groundcontrol.ui.theme.toHex

@Composable
fun ProjectsScreen(
    vm: ProjectsViewModel,
    onOpenWorkspace: (connectionId: String) -> Unit,
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProjectRow?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.connectionId }) { row ->
            ListItem(
                leadingContent = { WorkspaceBadge(row.identity, size = 32.dp) },
                headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
                trailingContent = {
                    IconButton(onClick = { editing = row }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit ${row.name} identity")
                    }
                },
                modifier = Modifier.clickable { onOpenWorkspace(row.connectionId) },
            )
        }
    }
    editing?.let { row ->
        IdentityEditDialog(
            row = row,
            onDismiss = { editing = null },
            onSave = { hex, glyph -> vm.setOverride(row.connectionId, hex, glyph); editing = null },
            onReset = { vm.setOverride(row.connectionId, null, null); editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentityEditDialog(
    row: ProjectRow,
    onDismiss: () -> Unit,
    onSave: (hex: String?, glyph: String?) -> Unit,
    onReset: () -> Unit,
) {
    var picked by remember { mutableStateOf(row.identity.color) }
    var glyph by remember { mutableStateOf(row.identity.glyph) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Identity: ${row.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = glyph,
                    onValueChange = { glyph = it.take(1).uppercase() },
                    label = { Text("Glyph") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspacePalette.swatches.forEach { c ->
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(percent = 28))
                                .background(c)
                                .border(
                                    width = if (c == picked) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(percent = 28),
                                )
                                .clickable { picked = c },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(picked.toHex(), glyph.trim().takeIf { it.isNotEmpty() })
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Reset to auto") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
