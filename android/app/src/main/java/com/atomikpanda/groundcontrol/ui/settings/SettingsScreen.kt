package com.atomikpanda.groundcontrol.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.ladderLabel
import com.atomikpanda.groundcontrol.notify.WatchController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val context = LocalContext.current
    val connections by vm.connections.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val relayAccount by vm.relayAccount.collectAsStateWithLifecycle()
    val hostRows by vm.hostRows.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    val notificationsOn by vm.notificationsEnabled.collectAsStateWithLifecycle()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.setNotificationsEnabled(true)
            WatchController.enable(context)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        if (contents == null) return@rememberLauncherForActivityResult // user cancelled
        val ok = vm.addFromLink(contents)
        Toast.makeText(
            context,
            if (ok) "Connection added" else "Invalid code",
            Toast.LENGTH_SHORT,
        ).show()
    }

    val relayScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        if (contents == null) return@rememberLauncherForActivityResult // user cancelled
        val ok = vm.addRelayFromLink(contents)
        Toast.makeText(
            context,
            if (ok) "Relay account added" else "Invalid relay code",
            Toast.LENGTH_SHORT,
        ).show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The primary path (#471): one relay account, then every host in the fleet
        // arrives by itself — the phone never holds a VM address.
        item { Text("Relay account", style = MaterialTheme.typography.titleMedium) }
        relayAccount?.let { account ->
            item { Text(account.relayDomain, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            Button(
                onClick = {
                    relayScanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan a Ground Control relay QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
            ) { Text(if (relayAccount == null) "Add relay account" else "Replace relay account") }
        }
        if (relayAccount != null) {
            item {
                TextButton(onClick = { vm.refreshFleetNow() }) { Text("Refresh fleet") }
            }
            items(hostRows, key = { "host:${it.hostId}" }) { host ->
                ListItem(
                    headlineContent = { Text(host.label) },
                    supportingContent = { Text(ladderLabel(host.state)) },
                )
            }
        }
        item { HorizontalDivider() }
        // Fallback only: a host reachable on LAN/tailnet, or a lone `mship serve`.
        item { Text("LAN / tailnet fallback", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(
                url,
                { url = it },
                label = { Text("Direct URL (LAN/tailnet)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                token,
                { token = it },
                label = { Text("Bearer token (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = { vm.addOrUpdate(null, url, token); url = ""; token = "" }) {
                Text("Add / test")
            }
        }
        // Host discovery (#472): one host URL + host token lists every workspace
        // its daemon discovered; picking one derives a per-workspace connection.
        item {
            Button(onClick = { vm.discoverOnHost(url, token) }) {
                Text("Discover workspaces on host")
            }
        }
        discovered?.let { found ->
            items(found.workspaces, key = { "discovered:${it.id}" }) { info ->
                ListItem(
                    headlineContent = { Text(info.name) },
                    supportingContent = {
                        Text(if (info.state == "healthy") info.path else "${info.state}: ${info.detail}")
                    },
                    trailingContent = {
                        TextButton(
                            enabled = info.state == "healthy",
                            onClick = { vm.addDiscovered(found, info) },
                        ) { Text("Add") }
                    },
                )
            }
        }
        item {
            Button(
                onClick = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan a Ground Control pairing QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
            ) { Text("Scan QR") }
        }
        testResult?.let { result ->
            item { Text(result, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            ListItem(
                headlineContent = { Text("Notifications") },
                supportingContent = { Text("Alert me when an agent needs me (all workspaces)") },
                trailingContent = {
                    Switch(checked = notificationsOn, onCheckedChange = { want ->
                        if (want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED
                        ) {
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setNotificationsEnabled(want)
                            if (want) WatchController.enable(context) else WatchController.disable(context)
                        }
                    })
                },
            )
        }
        item { HorizontalDivider() }
        items(connections, key = { "connection:${it.id}" }) { c: WorkspaceConnection ->
            ListItem(
                headlineContent = { Text(c.workspaceName.ifBlank { c.baseUrl }) },
                supportingContent = { Text(c.baseUrl) },
                trailingContent = {
                    TextButton(onClick = { vm.remove(c.id) }) { Text("Remove") }
                },
            )
        }
    }
}
