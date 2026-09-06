package com.atomikpanda.groundcontrol

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.InMemoryCoachMarkStore
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.queue.QueueScreen
import com.atomikpanda.groundcontrol.ui.queue.QueueUiState
import com.atomikpanda.groundcontrol.ui.queue.QueueViewModel
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueQuestionSubmissionUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun pending_answer_disables_sibling_controls_without_claiming_they_are_sending() {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/answer") -> {
                    requested.complete(Unit)
                    release.await()
                    respond("{}", HttpStatusCode.InternalServerError, headers)
                }
                request.url.encodedPath.endsWith("/specs") -> respond(
                    """[{"id":"spec","title":"Two questions","status":"needs_review"}]""",
                    HttpStatusCode.OK, headers,
                )
                request.url.encodedPath.endsWith("/specs/spec") -> respond(
                    """{"id":"spec","title":"Two questions","status":"needs_review","body":"",
                       "open_questions":[{"id":"first","text":"First question?","answer":null},
                       {"id":"second","text":"Second question?","answer":null}]}""",
                    HttpStatusCode.OK, headers,
                )
                else -> respond("[]", HttpStatusCode.OK, headers)
            }
        }) { mshipDefaults() }
        val connections = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(
            WorkspaceConnection("one", "http://queue.invalid", null, "Workspace"),
        )))
        val vm = QueueViewModel(QueueRepository(SpecApi(client)), connections)
        val store = ViewModelStore().apply { put("queue", vm) }
        val coach = InMemoryCoachMarkStore(seen = true)
        try {
            composeRule.setContent {
                GroundControlTheme {
                    QueueScreen(vm, coach, { _, _ -> }, {}, { _, _ -> }, {}, {})
                }
            }
            composeRule.waitUntil(10_000) { vm.state.value is QueueUiState.Content }
            composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("First line\nSecond line")
            composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("Unsent draft")
            composeRule.onAllNodesWithContentDescription("Send answer")[0].performClick()
            composeRule.waitUntil(10_000) { requested.isCompleted }
            composeRule.onAllNodesWithContentDescription("Send answer").assertCountEquals(2)
            composeRule.onAllNodesWithContentDescription("Send answer")[0].assertIsNotEnabled()
            composeRule.onAllNodesWithContentDescription("Send answer")[1].assertIsNotEnabled()
            release.complete(Unit)
            composeRule.waitUntil(10_000) { (vm.state.value as? QueueUiState.Content)?.inFlight == false }
            composeRule.onAllNodes(hasSetTextAction())[0].assertTextEquals("First line\nSecond line")
            composeRule.onAllNodes(hasSetTextAction())[1].assertTextEquals("Unsent draft")
            composeRule.onAllNodesWithContentDescription("Send answer")[0].assertIsEnabled()
            composeRule.onAllNodesWithContentDescription("Send answer")[1].assertIsEnabled()
        } finally {
            release.complete(Unit)
            composeRule.runOnIdle { store.clear() }
            client.close()
        }
    }
}
