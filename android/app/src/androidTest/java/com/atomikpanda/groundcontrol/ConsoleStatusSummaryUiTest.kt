package com.atomikpanda.groundcontrol

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.console.ConsoleScreen
import com.atomikpanda.groundcontrol.ui.console.ConsoleUiState
import com.atomikpanda.groundcontrol.ui.console.ConsoleViewModel
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.test.ExperimentalTestApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConsoleStatusSummaryUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>(effectContext = Dispatchers.Main)

    @Test fun in_flight_item_without_active_task_phase_renders_dispatched_step() {
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        val connection = WorkspaceConnection("one", "http://console.invalid", null, "Workspace")
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/items/item")) {
                respond(
                    """{"id":"item","kind":"feature","title":"Dispatched item","phase":"in_flight",
                       "task_slugs":["unavailable"],"thread_ids":[],"spec_id":null}""",
                    HttpStatusCode.OK,
                    headers,
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }) { mshipDefaults() }
        val vm = ConsoleViewModel(
            SpecApi(client),
            connection.id,
            "item",
            MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(connection))),
        )
        val store = ViewModelStore().apply { put("console", vm) }
        try {
            composeRule.setContent {
                GroundControlTheme {
                    ConsoleScreen(vm, "Console", onBack = {})
                }
            }
            composeRule.waitUntil(10_000) { vm.state.value is ConsoleUiState.Content }
            composeRule.onNodeWithContentDescription("Task phase").assert(
                SemanticsMatcher("Task phase is dispatched") { node ->
                    node.config[SemanticsProperties.StateDescription].startsWith("Dispatched,")
                },
            )
        } finally {
            composeRule.runOnIdle { store.clear() }
            client.close()
        }
    }
}
