package com.atomikpanda.groundcontrol

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.appHttpClient
import com.atomikpanda.groundcontrol.data.DataStoreCoachMarkStore
import com.atomikpanda.groundcontrol.data.DataStoreNotificationsSetting
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.notify.AndroidNeedsYouCanceller
import com.atomikpanda.groundcontrol.ui.home.HomeScreen
import com.atomikpanda.groundcontrol.ui.home.HomeViewModel
import com.atomikpanda.groundcontrol.ui.messages.ConversationScreen
import com.atomikpanda.groundcontrol.ui.messages.ConversationViewModel
import com.atomikpanda.groundcontrol.ui.messages.MessagesScreen
import com.atomikpanda.groundcontrol.ui.messages.MessagesUiState
import com.atomikpanda.groundcontrol.ui.messages.MessagesViewModel
import com.atomikpanda.groundcontrol.ui.messages.NewThreadScreen
import com.atomikpanda.groundcontrol.ui.messages.NewThreadViewModel
import com.atomikpanda.groundcontrol.ui.nav.Section
import com.atomikpanda.groundcontrol.ui.queue.QueueScreen
import com.atomikpanda.groundcontrol.ui.queue.QueueViewModel
import com.atomikpanda.groundcontrol.ui.console.ConsoleScreen
import com.atomikpanda.groundcontrol.ui.console.ConsoleViewModel
import com.atomikpanda.groundcontrol.ui.done.DoneScreen
import com.atomikpanda.groundcontrol.ui.done.DoneViewModel
import com.atomikpanda.groundcontrol.ui.farm.FarmScreen
import com.atomikpanda.groundcontrol.ui.farm.FarmViewModel
import com.atomikpanda.groundcontrol.ui.review.ReviewScreen
import com.atomikpanda.groundcontrol.ui.review.ReviewViewModel
import com.atomikpanda.groundcontrol.ui.projects.ProjectsScreen
import com.atomikpanda.groundcontrol.ui.projects.ProjectsViewModel
import com.atomikpanda.groundcontrol.ui.theme.LocalWorkspaceIdentityResolver
import com.atomikpanda.groundcontrol.ui.theme.WorkspaceIdentity
import com.atomikpanda.groundcontrol.ui.theme.resolveIdentity
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

internal fun newThreadRoute(connectionId: String?): String =
    if (connectionId != null) "newThread?connectionId=$connectionId" else "newThread"

internal fun connectionRoute(
    conn: WorkspaceConnection,
    destination: String,
    entityId: String,
): String = "$destination/${conn.id}/$entityId"

internal fun workItemRoute(conn: WorkspaceConnection, item: WorkItemSummary): String? = when {
    item.phase == "in_flight" -> connectionRoute(conn, "console", item.id)
    item.phase == "review" -> connectionRoute(conn, "review", item.id)
    item.phase == "done" -> connectionRoute(conn, "done", item.id)
    // Inbox / shaping / ready (spec-bearing) home to the spec cockpit; a spec-less
    // inbox capture falls through to its task or thread.
    item.specId != null -> connectionRoute(conn, "specDetail", item.specId)
    item.taskSlugs.isNotEmpty() -> connectionRoute(conn, "taskDetail", item.taskSlugs.first())
    item.threadIds.isNotEmpty() -> connectionRoute(conn, "thread", item.threadIds.first())
    else -> null
}

internal data class ConnectionRouteBinding(
    val connection: WorkspaceConnection,
    val viewModelKey: String,
)

/** Binds a route to the current canonical connection snapshot. */
internal fun connectionRouteBinding(
    connections: List<WorkspaceConnection>,
    connectionId: String,
    destinationKey: String,
): ConnectionRouteBinding? = connections.findByConnectionId(connectionId)?.let { connection ->
    ConnectionRouteBinding(connection, "$destinationKey-${ConnectionsCodec.encode(listOf(connection))}")
}

internal fun <T> Result<T>.getOrNullOrRethrowCancellation(): T? =
    onFailure { if (it is CancellationException) throw it }.getOrNull()

/** A route-entry-owned holder that keeps one child store for its active connection snapshot. */
internal class ConnectionRouteViewModelStore : ViewModel() {
    private var snapshotKey: String? = null
    private var owner = ConnectionRouteViewModelOwner()

    fun ownerFor(key: String): ConnectionRouteViewModelOwner {
        if (snapshotKey != key) {
            owner.clear()
            owner = ConnectionRouteViewModelOwner()
            snapshotKey = key
        }
        return owner
    }

    override fun onCleared() {
        owner.clear()
    }
}

internal class ConnectionRouteViewModelOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun clear() = viewModelStore.clear()
}

@Composable
private fun ConnectionRouteViewModelScope(
    binding: ConnectionRouteBinding,
    content: @Composable () -> Unit,
) {
    val routeStore: ConnectionRouteViewModelStore = viewModel()
    val owner = routeStore.ownerFor(binding.viewModelKey)
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        key(binding.viewModelKey) { content() }
    }
}

@Composable
fun GroundControlApp(
    context: Context,
    pendingThread: MutableStateFlow<Pair<String, String>?>? = null,
) {
    val nav = rememberNavController()
    val connRepo = remember { ConnectionsRepository(context.applicationContext) }
    val hostsRepo = remember { HostsRepository(context.applicationContext) }
    // The client mints and refreshes each host's short-lived bearer itself (#471
    // AC9), reading the persisted refresh credential off the stored host list.
    val hostClient = remember { appHttpClient(context.applicationContext) }
    val api = remember { SpecApi(hostClient.client) }
    val homeRepo = remember { HomeFeedRepository(api) }
    val queueRepo = remember { QueueRepository(api) }
    val detailRepo = remember { SpecDetailRepository(api) }
    val tasksRepo = remember { TasksRepository(api) }
    val threadsRepo = remember { ThreadsRepository(api) }
    // Composition-scoped (cancelled on disposal) — not MainScope(), which would leak its
    // stateIn collector across Activity recreations.
    val appScope = rememberCoroutineScope()
    val notificationsSetting = remember { DataStoreNotificationsSetting(context.applicationContext, appScope) }
    val coachMark = remember { DataStoreCoachMarkStore(context.applicationContext, appScope) }
    // Activity-scoped (not per-NavBackStackEntry): shared by the Home sticky threads card and the
    // "threads" drill-in list so the loaded sections + live-poll loop survive navigating between
    // them (spec: ground-control-thread-findability).
    val messagesVm = viewModel {
        MessagesViewModel(threadsRepo, connRepo.connections)
    }
    // Activity-scoped so relay links received on Home immediately trigger fleet
    // discovery; tying this observer to the Settings destination delays pairing.
    val settingsVm = viewModel {
        SettingsViewModel(connRepo, api, notificationsSetting, hostsRepo)
    }
    val initialConnections = remember { runBlockingSnapshot(connRepo) }
    val connsForBadges by connRepo.connections.collectAsStateWithLifecycle(initialValue = initialConnections)
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
        // remember keyed on the connections so the resolver identity is stable across recompositions;
        // staticCompositionLocalOf invalidates every badge reader on a by-reference change, so a fresh
        // lambda each recomposition would needlessly re-render all badge sites (Greptile P2).
        val identityResolver: (String, String) -> WorkspaceIdentity = remember(connsForBadges) {
            { id, name -> resolveIdentity(connsForBadges, id, name) }
        }
        CompositionLocalProvider(LocalWorkspaceIdentityResolver provides identityResolver) {
        NavHost(nav, startDestination = Section.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Section.HOME.route) {
                val vm = viewModel {
                    HomeViewModel(
                        homeRepo,
                        connectionsProvider = { runBlockingSnapshot(connRepo) },
                        hosts = hostsRepo.hosts,
                    )
                }
                HomeScreen(
                    vm,
                    messagesVm,
                    onApproval = { connId, specId -> nav.navigate("specDetail/$connId/$specId") },
                    onQuestion = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onBlocker = { connId, slug -> nav.navigate("taskDetail/$connId/$slug") },
                    onBrowseWorkspace = { connId -> nav.navigate("farm/$connId") },
                    onCapture = { nav.navigate("capture") },
                    onOpenThreads = { nav.navigate("threads") },
                    onReviewInQueue = { nav.navigate(Section.QUEUE.route) { launchSingleTop = true } },
                    onRePair = { nav.navigate(Section.SETTINGS.route) { launchSingleTop = true } },
                )
            }
            composable(Section.QUEUE.route) {
                val vm = viewModel {
                    QueueViewModel(
                        queueRepo,
                        connectionsProvider = { runBlockingSnapshot(connRepo) },
                        hosts = hostsRepo.hosts,
                    )
                }
                val uriHandler = LocalUriHandler.current
                QueueScreen(
                    vm,
                    coachMark = coachMark,
                    onOpenItem = { connId, itemId -> nav.navigate("item/$connId/$itemId") },
                    onOpenPr = { url -> uriHandler.openUri(url) },
                    onOpenTask = { connId, task -> nav.navigate("taskDetail/$connId/$task") },
                    onRePair = { nav.navigate(Section.SETTINGS.route) { launchSingleTop = true } },
                )
            }
            composable("threads") {
                MessagesScreen(
                    messagesVm,
                    onThreadClick = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onNewThread = {
                        val connId = (messagesVm.state.value as? MessagesUiState.Content)?.selectedConnectionId
                        nav.navigate(newThreadRoute(connId))
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Section.TASKS.route) {
                val vm = viewModel {
                    TasksViewModel(tasksRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                TasksScreen(vm) { connId, slug -> nav.navigate("taskDetail/$connId/$slug") }
            }
            composable(Section.PROJECTS.route) {
                val vm = viewModel { ProjectsViewModel(connRepo, hostsRepo) }
                ProjectsScreen(vm, onOpenWorkspace = { connId -> nav.navigate("workspace/$connId") })
            }
            composable(Section.SETTINGS.route) {
                SettingsScreen(settingsVm)
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
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "detail-$specId",
                )
                if (binding == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the inbox.") }
                } else ConnectionRouteViewModelScope(binding) {
                    val conn = binding.connection
                    val title = remember(specId) { specId }
                    val vm = viewModel {
                        SpecDetailViewModel(detailRepo, conn, specId)
                    }
                    SpecDetailScreen(vm, title = title, identity = LocalWorkspaceIdentityResolver.current(conn.id, conn.workspaceName.ifBlank { conn.baseUrl }), onBack = { nav.popBackStack() })
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
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "taskDetail-$slug",
                )
                if (binding == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to tasks.") }
                } else ConnectionRouteViewModelScope(binding) {
                    val conn = binding.connection
                    val vm = viewModel {
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
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "workspace",
                )
                if (binding == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to Home.") }
                } else ConnectionRouteViewModelScope(binding) {
                    val conn = binding.connection
                    val vm = viewModel {
                        WorkspaceViewModel(api, conn)
                    }
                    WorkspaceScreen(
                        vm,
                        workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                        onThread = { id -> nav.navigate(connectionRoute(conn, "thread", id)) },
                        onSpec = { id -> nav.navigate(connectionRoute(conn, "specDetail", id)) },
                        onTask = { slug -> nav.navigate(connectionRoute(conn, "taskDetail", slug)) },
                        onNewConversation = { nav.navigate(newThreadRoute(conn.id)) },
                        onBack = { nav.popBackStack() },
                        onRePair = { nav.navigate(Section.SETTINGS.route) { launchSingleTop = true } },
                    )
                }
            }
            composable(
                route = "farm/{connectionId}",
                arguments = listOf(navArgument("connectionId") { type = NavType.StringType }),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "farm",
                )
                if (binding == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed.") }
                } else ConnectionRouteViewModelScope(binding) {
                    val conn = binding.connection
                    val vm = viewModel { FarmViewModel(api, conn) }
                    FarmScreen(
                        vm = vm,
                        workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                        onOpen = { item ->
                            workItemRoute(conn, item)?.let { nav.navigate(it) }
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "console/{connectionId}/{itemId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "console-$itemId",
                )
                if (binding == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else ConnectionRouteViewModelScope(binding) {
                    val conn = binding.connection
                    val vm = viewModel {
                        ConsoleViewModel(api, conn, itemId)
                    }
                    ConsoleScreen(
                        vm,
                        title = conn.workspaceName.ifBlank { conn.baseUrl },
                        identity = LocalWorkspaceIdentityResolver.current(conn.id, conn.workspaceName.ifBlank { conn.baseUrl }),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "review/{connectionId}/{itemId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "review-$itemId",
                )
                if (binding == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else ConnectionRouteViewModelScope(binding) {
                    val conn = binding.connection
                    val vm = viewModel {
                        ReviewViewModel(api, conn, itemId)
                    }
                    ReviewScreen(
                        vm,
                        title = conn.workspaceName.ifBlank { conn.baseUrl },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "done/{connectionId}/{itemId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "done-$itemId",
                )
                if (binding == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else ConnectionRouteViewModelScope(binding) {
                    val conn = binding.connection
                    val vm = viewModel {
                        DoneViewModel(api, conn, itemId)
                    }
                    DoneScreen(
                        vm,
                        title = conn.workspaceName.ifBlank { conn.baseUrl },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "item/{connectionId}/{itemId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "item-$itemId",
                )
                LaunchedEffect(binding?.viewModelKey, itemId) {
                    // This route is a pure redirect with no fallback UI of its own, so every
                    // dead-end pops back to where the user came from instead of stranding them on
                    // the transient spinner (reachable from the related-item card and from OS-level
                    // groundcontrol://item deep links).
                    if (binding == null) {
                        nav.popBackStack(); return@LaunchedEffect
                    }
                    val item = runCatching { api.getItem(binding.connection, itemId) }
                        .getOrNullOrRethrowCancellation()
                    if (item == null) {
                        nav.popBackStack(); return@LaunchedEffect
                    }
                    val dest = workItemRoute(binding.connection, item)
                    if (dest == null) {
                        nav.popBackStack(); return@LaunchedEffect
                    }
                    nav.navigate(dest) { popUpTo("item/$connectionId/$itemId") { inclusive = true } }
                }
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
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
                    showKindPicker = true,
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
                val binding = connectionRouteBinding(
                    connections = connsForBadges,
                    connectionId = connectionId,
                    destinationKey = "thread-$threadId",
                )
                if (binding == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to messages.") }
                } else ConnectionRouteViewModelScope(binding) {
                    val conn = binding.connection
                    val vm = viewModel {
                        ConversationViewModel(
                            threadsRepo, conn, threadId,
                            canceller = AndroidNeedsYouCanceller(context.applicationContext),
                        )
                    }
                    ConversationScreen(
                        vm,
                        title = threadId,
                        identity = LocalWorkspaceIdentityResolver.current(conn.id, conn.workspaceName.ifBlank { conn.baseUrl }),
                        onBack = { nav.popBackStack() },
                        onViewSpec = { specId -> nav.navigate(connectionRoute(conn, "specDetail", specId)) },
                        onOpenEntity = { kind, id ->
                            when (kind) {
                                "item" -> nav.navigate(connectionRoute(conn, "item", Uri.encode(id)))
                                "spec" -> nav.navigate(connectionRoute(conn, "specDetail", Uri.encode(id)))
                                "task" -> nav.navigate(connectionRoute(conn, "taskDetail", Uri.encode(id)))
                            }
                        },
                        onOpenWorkItem = { id -> nav.navigate(connectionRoute(conn, "item", Uri.encode(id))) },
                    )
                }
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
