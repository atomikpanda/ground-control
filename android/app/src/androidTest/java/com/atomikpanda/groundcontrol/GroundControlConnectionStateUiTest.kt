package com.atomikpanda.groundcontrol

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ConnectionStateSource
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroundControlConnectionStateUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeSource(initial: ConnectionState) : ConnectionStateSource {
        private val mutable = MutableStateFlow(initial)
        override val state: StateFlow<ConnectionState> = mutable
        override fun retry() = Unit
        fun ready(connections: List<WorkspaceConnection> = emptyList()) {
            mutable.value = ConnectionState.Ready(connections)
        }
    }

    @Test fun error_open_settings_back_then_ready_reuses_the_actual_nav_host_and_activity() {
        val source = FakeSource(ConnectionState.Error(IllegalStateException("disk")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activity = composeRule.activity
        composeRule.setContent {
            GroundControlContent(context, GroundControlDependencies.production(context, source))
        }

        composeRule.onNodeWithText("Open Settings").performClick()
        composeRule.onNodeWithText("Relay account").assertIsDisplayed()
        source.ready(
            listOf(
                WorkspaceConnection(
                    id = "recovered",
                    baseUrl = "http://recovered.invalid:47100",
                    workspaceName = "Recovered workspace",
                ),
            ),
        )
        composeRule.waitUntil {
            composeRule.onAllNodesWithText("Recovered workspace")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithText("Recovered workspace").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Relay account").fetchSemanticsNodes().isEmpty())
        assertSame(activity, composeRule.activity)
    }

    @Test fun home_selection_and_state_filter_transfer_to_threads_without_changing_home_owner_metadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = FakeSource(
            ConnectionState.Ready(
                listOf(
                    WorkspaceConnection("a", "http://a:47100", workspaceName = "ws-a"),
                    WorkspaceConnection("b", "http://b:47100", workspaceName = "ws-b"),
                ),
            ),
        )
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val api = SpecApi(HttpClient(MockEngine { request ->
            when {
                request.url.parameters["wait"] == "1" -> kotlinx.coroutines.awaitCancellation()
                request.url.encodedPath.endsWith("/threads") -> respond(
                    if (request.url.host == "a") {
                        """[{"id":"a-unread","subject":"Only A","unseen":true}]"""
                    } else {
                        """[{"id":"b-unread","subject":"Only B","unseen":true}]"""
                    },
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        }) { mshipDefaults() })
        val dependencies = GroundControlDependencies(
            connections = ConnectionsRepository(context),
            hosts = HostsRepository(context),
            connectionStateSource = source,
            api = api,
            home = HomeFeedRepository(api),
            queue = QueueRepository(api),
            detail = SpecDetailRepository(api),
            specs = SpecRepository(api),
            tasks = TasksRepository(api),
            threads = ThreadsRepository(api),
        )

        composeRule.setContent { GroundControlContent(context, dependencies) }
        composeRule.waitUntil {
            composeRule.onAllNodesWithText("ws-a").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("ws-a").performClick()
        composeRule.onNodeWithText("Unread").performClick()
        composeRule.onNodeWithText("Threads").performClick()
        composeRule.waitUntil {
            composeRule.onAllNodesWithText("Only A").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Only A").assertIsDisplayed()
        assertFalse(composeRule.onAllNodesWithText("Only B").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun queue_browse_specs_opens_the_active_and_archived_spec_inbox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = FakeSource(
            ConnectionState.Ready(
                listOf(WorkspaceConnection("a", "http://a:47100", workspaceName = "ws-a")),
            ),
        )
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val api = SpecApi(HttpClient(MockEngine {
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }) { mshipDefaults() })
        val dependencies = GroundControlDependencies(
            connections = ConnectionsRepository(context),
            hosts = HostsRepository(context),
            connectionStateSource = source,
            api = api,
            home = HomeFeedRepository(api),
            queue = QueueRepository(api),
            detail = SpecDetailRepository(api),
            tasks = TasksRepository(api),
            specs = SpecRepository(api),
            threads = ThreadsRepository(api),
        )

        composeRule.setContent { GroundControlContent(context, dependencies) }
        composeRule.onNodeWithText("Queue").performClick()
        composeRule.onNodeWithText("Browse specs").performClick()
        composeRule.onNodeWithText("Search active specs").assertIsDisplayed()
        composeRule.onNodeWithText("Archived").assertIsDisplayed()
    }
}
