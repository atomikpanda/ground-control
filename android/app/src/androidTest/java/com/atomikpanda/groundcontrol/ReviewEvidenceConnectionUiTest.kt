package com.atomikpanda.groundcontrol

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.done.DoneScreen
import com.atomikpanda.groundcontrol.ui.done.DoneViewModel
import com.atomikpanda.groundcontrol.ui.review.ReviewScreen
import com.atomikpanda.groundcontrol.ui.review.ReviewViewModel
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailScreen
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailViewModel
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewEvidenceConnectionUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private enum class Surface { REVIEW, DONE, SPEC_DETAIL }

    @Test fun review_reloads_same_ref_after_connection_replacement() = verifyReload(Surface.REVIEW)
    @Test fun done_reloads_same_ref_after_connection_replacement() = verifyReload(Surface.DONE)
    @Test fun spec_detail_reloads_same_ref_after_connection_replacement() = verifyReload(Surface.SPEC_DETAIL)

    private fun verifyReload(surface: Surface) {
        val oldConnection = WorkspaceConnection("one", "http://evidence.invalid", "old", "Workspace")
        val connections = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(oldConnection)))
        val oldRequested = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val replacementRequested = CompletableDeferred<Unit>()
        val oldImage = png(android.graphics.Color.RED)
        val currentImage = png(android.graphics.Color.GREEN)
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/items/item") -> respond(
                    """{"id":"item","kind":"chore","title":"Evidence recovery","phase":"${if (surface == Surface.DONE) "done" else "review"}","spec_id":"spec"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                request.url.encodedPath.endsWith("/evidence/image.png/blob") -> {
                    val bytes = if (request.headers[HttpHeaders.Authorization] == "Bearer old") {
                        oldRequested.complete(Unit)
                        releaseOld.await()
                        oldImage
                    } else {
                        replacementRequested.complete(Unit)
                        currentImage
                    }
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }
                else -> respond(
                    """{"id":"spec","title":"Evidence recovery","status":"dispatched",
                       "acceptance_criteria":[{"id":"ac1","text":"Inspect the current screenshot","verdict":"approved",
                       "evidence":[{"kind":"artifact","ref":"image.png","note":"Current screenshot"}]}]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }) { mshipDefaults() }
        val api = SpecApi(client)
        val store = ViewModelStore()
        val vm = when (surface) {
            Surface.DONE -> DoneViewModel(api, "one", "item", connections)
            Surface.REVIEW -> ReviewViewModel(api, "one", "item", connections)
            Surface.SPEC_DETAIL -> SpecDetailViewModel(SpecDetailRepository(api), "one", "spec", connections)
        }
        store.put("screen", vm)
        try {
            composeRule.setContent {
                GroundControlTheme {
                    when (vm) {
                        is DoneViewModel -> DoneScreen(vm, "Done", {})
                        is ReviewViewModel -> ReviewScreen(vm, "Review", {})
                        is SpecDetailViewModel -> SpecDetailScreen(vm, "Spec", onBack = {})
                    }
                }
            }
            composeRule.waitUntil(10_000) { oldRequested.isCompleted }
            composeRule.runOnIdle {
                connections.value = ConnectionState.Ready(listOf(oldConnection.copy(token = "replacement")))
            }
            // The item, criterion, and image ref are identical. Only connection ownership changed.
            composeRule.waitUntil(10_000) { replacementRequested.isCompleted }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription("Current screenshot")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            releaseOld.complete(Unit)
            val image = composeRule.onNodeWithContentDescription("Current screenshot")
            image.assertIsDisplayed()
            val pixels = image.captureToImage().toPixelMap()
            assertEquals(android.graphics.Color.GREEN, pixels[pixels.width / 2, pixels.height / 2].toArgb())
        } finally {
            releaseOld.complete(Unit)
            composeRule.runOnIdle { store.clear() }
            client.close()
        }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
