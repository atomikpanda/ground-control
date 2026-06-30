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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.DataStoreNotificationsSetting
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.defaultHttpClient
import com.atomikpanda.groundcontrol.ui.home.HomeScreen
import com.atomikpanda.groundcontrol.ui.home.HomeViewModel
import com.atomikpanda.groundcontrol.ui.messages.ConversationScreen
import com.atomikpanda.groundcontrol.ui.messages.ConversationViewModel
import com.atomikpanda.groundcontrol.ui.messages.NewThreadScreen
import com.atomikpanda.groundcontrol.ui.messages.NewThreadViewModel
import com.atomikpanda.groundcontrol.ui.nav.Section
import com.atomikpanda.groundcontrol.ui.settings.SettingsScreen
import com.atomikpanda.groundcontrol.ui.settings.SettingsViewModel
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailScreen
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailViewModel
import com.atomikpanda.groundcontrol.ui.tasks.TaskDetailScreen
import com.atomikpanda.groundcontrol.ui.tasks.TaskDetailViewModel
import com.atomikpanda.groundcontrol.ui.tasks.TasksScreen
import com.atomikpanda.groundcontrol.ui.tasks.TasksViewModel
import com.atomikpanda.groundcontrol.ui.workspace.WorkspaceScreen
import com.atomikpanda.groundcontrol.ui.workspace.WorkspaceViewModel
import kotlinx.coroutines.runBlocking

@Composable
fun GroundControlApp(
    context: Context,
    pendingThread: MutableStateFlow<Pair<String, String>?>? = null,
) {
    val nav = rememberNavController()
    val connRepo = remember { ConnectionsRepository(context.applicationContext) }
    val api = remember { SpecApi(defaultHttpClient()) }
    val homeRepo = remember { HomeFeedRepository(api) }
    val detailRepo = remember { SpecDetailRepository(api) }
    val tasksRepo = remember { TasksRepository(api) }
    val threadsRepo = remember { ThreadsRepository(api) }
    val appScope = remember { kotlinx.coroutines.MainScope() }
    val notificationsSetting = remember { DataStoreNotificationsSetting(context.applicationContext, appScope) }

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
        NavHost(nav, startDestination = Section.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Section.HOME.route) {
                val vm = viewModel {
                    HomeViewModel(homeRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                HomeScreen(
                    vm,
                    onApproval = { connId, specId -> nav.navigate("specDetail/$connId/$specId") },
                    onQuestion = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onBlocker = { connId, slug -> nav.navigate("taskDetail/$connId/$slug") },
                    onBrowseWorkspace = { connId -> nav.navigate("workspace/$connId") },
                    onCapture = { nav.navigate("capture") },
                )
            }
            composable(Section.TASKS.route) {
                val vm = viewModel {
                    TasksViewModel(tasksRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                TasksScreen(vm) { connId, slug -> nav.navigate("taskDetail/$connId/$slug") }
            }
            composable(Section.SETTINGS.route) {
                val vm = viewModel { SettingsViewModel(connRepo, api, notificationsSetting) }
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
            composable(
                route = "taskDetail/{connectionId}/{slug}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("slug") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val slug = entry.arguments?.getString("slug").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to tasks.") }
                } else {
                    val vm = viewModel(key = "taskDetail-$connectionId-$slug") {
                        TaskDetailViewModel(tasksRepo, conn, slug)
                    }
                    TaskDetailScreen(vm, title = slug, onBack = { nav.popBackStack() })
                }
            }
            composable(
                route = "workspace/{connectionId}",
                arguments = listOf(navArgument("connectionId") { type = NavType.StringType }),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to Home.") }
                } else {
                    val vm = viewModel(key = "workspace-$connectionId") {
                        WorkspaceViewModel(api, conn)
                    }
                    WorkspaceScreen(
                        vm,
                        workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                        onThread = { id -> nav.navigate("thread/$connectionId/$id") },
                        onSpec = { id -> nav.navigate("specDetail/$connectionId/$id") },
                        onTask = { slug -> nav.navigate("taskDetail/$connectionId/$slug") },
                        onNewConversation = { nav.navigate("newThread?connectionId=$connectionId") },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable("capture") {
                val vm = viewModel {
                    NewThreadViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                NewThreadScreen(
                    vm,
                    title = "Capture",
                    showSubject = false,
                    bodyLabel = "What's up?",
                    submitLabel = "Send",
                    onCreated = { connId, id ->
                        nav.navigate("thread/$connId/$id") {
                            popUpTo("capture") { inclusive = true }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "newThread?connectionId={connectionId}",
                arguments = listOf(navArgument("connectionId") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                }),
            ) { entry ->
                val preselect = entry.arguments?.getString("connectionId")
                val vm = viewModel {
                    NewThreadViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                NewThreadScreen(
                    vm,
                    initialConnectionId = preselect,
                    onCreated = { connId, id ->
                        nav.navigate("thread/$connId/$id") {
                            popUpTo("newThread?connectionId={connectionId}") { inclusive = true }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "thread/{connectionId}/{threadId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("threadId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val threadId = entry.arguments?.getString("threadId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to messages.") }
                } else {
                    val vm = viewModel(key = "thread-$connectionId-$threadId") {
                        ConversationViewModel(threadsRepo, conn, threadId)
                    }
                    ConversationScreen(
                        vm,
                        title = threadId,
                        onBack = { nav.popBackStack() },
                        onViewSpec = { specId -> nav.navigate("specDetail/$connectionId/$specId") },
                    )
                }
            }
        }
    }

    if (pendingThread != null) {
        val pending by pendingThread.collectAsStateWithLifecycle()
        LaunchedEffect(pending) {
            pending?.let { (connId, threadId) ->
                nav.navigate("thread/$connId/$threadId")
                pendingThread.value = null
            }
        }
    }
}

/** Bridge the suspend snapshot to the VM's sync provider on first refresh. */
private fun runBlockingSnapshot(repo: ConnectionsRepository): List<WorkspaceConnection> =
    runBlocking { repo.snapshot() }
