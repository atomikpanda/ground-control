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
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
}
