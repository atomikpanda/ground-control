package com.atomikpanda.groundcontrol

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.defaultHttpClient
import com.atomikpanda.groundcontrol.ui.capture.CaptureScreen
import com.atomikpanda.groundcontrol.ui.capture.CaptureViewModel
import com.atomikpanda.groundcontrol.ui.nav.Section
import com.atomikpanda.groundcontrol.ui.placeholder.PlaceholderScreen
import com.atomikpanda.groundcontrol.ui.settings.SettingsScreen
import com.atomikpanda.groundcontrol.ui.settings.SettingsViewModel
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailScreen
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailViewModel
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxScreen
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxViewModel
import com.atomikpanda.groundcontrol.ui.tasks.TasksScreen
import com.atomikpanda.groundcontrol.ui.tasks.TasksViewModel
import kotlinx.coroutines.runBlocking

@Composable
fun GroundControlApp(context: Context) {
    val nav = rememberNavController()
    val connRepo = remember { ConnectionsRepository(context.applicationContext) }
    val api = remember { SpecApi(defaultHttpClient()) }
    val specRepo = remember { SpecRepository(api) }
    val detailRepo = remember { SpecDetailRepository(api) }
    val tasksRepo = remember { TasksRepository(api) }

    Scaffold(bottomBar = {
        val current by nav.currentBackStackEntryAsState()
        NavigationBar {
            Section.entries.forEach { s ->
                NavigationBarItem(
                    selected = current?.destination?.route == s.route,
                    onClick = { nav.navigate(s.route) { launchSingleTop = true } },
                    icon = { Icon(s.icon, s.label) },
                    label = { Text(s.label) },
                )
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = Section.SPECS.route, modifier = Modifier.padding(padding)) {
            composable(Section.SPECS.route) {
                val vm = viewModel {
                    SpecInboxViewModel(specRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                SpecInboxScreen(vm) { connId, specId ->
                    nav.navigate("specDetail/$connId/$specId")
                }
            }
            composable(Section.CAPTURE.route) {
                val vm = viewModel {
                    CaptureViewModel(connectionsProvider = { runBlockingSnapshot(connRepo) }, api = api)
                }
                CaptureScreen(vm)
            }
            composable(Section.DECISIONS.route) { PlaceholderScreen("Decisions", "C7") }
            composable(Section.TASKS.route) {
                val vm = viewModel {
                    TasksViewModel(tasksRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                TasksScreen(vm) { connId, slug -> nav.navigate("taskDetail/$connId/$slug") }
            }
            composable(Section.SETTINGS.route) {
                val vm = viewModel { SettingsViewModel(connRepo, api) }
                SettingsScreen(vm)
            }
            composable(
                route = "specDetail/{connectionId}/{specId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("specId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val specId = entry.arguments?.getString("specId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the inbox.") }
                } else {
                    val title = remember(specId) { specId }
                    val vm = viewModel(key = "detail-$connectionId-$specId") {
                        SpecDetailViewModel(detailRepo, conn, specId)
                    }
                    SpecDetailScreen(vm, title = title, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

/** Bridge the suspend snapshot to the VM's sync provider on first refresh. */
private fun runBlockingSnapshot(repo: ConnectionsRepository): List<WorkspaceConnection> =
    runBlocking { repo.snapshot() }
