// app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/EvidenceImage.kt
package com.atomikpanda.groundcontrol.ui.specdetail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.network.HttpException
import coil.request.ImageRequest

/** serve's blob route answers 409 when this host holds no key for an encrypted artifact. */
private const val HTTP_LOCKED = 409

/**
 * The one place that knows how an evidence-blob request is authenticated. Coil fetches through
 * OkHttp, not the app's Ktor client, so MshipClient's private `auth()` can't be reused — this
 * mirrors it exactly: a blank or absent token attaches no header, so a token-less workspace still
 * loads. Plain http works too (the app sets `usesCleartextTraffic` for tailnet/LAN workspaces).
 */
private fun evidenceImageRequest(context: Context, url: String, token: String?): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .apply { token?.takeIf { it.isNotBlank() }?.let { addHeader("Authorization", "Bearer $it") } }
        .build()

/**
 * An image artifact backing an acceptance criterion: full-width picture, tap to zoom, provenance
 * note beneath. Any load failure degrades to [EvidenceImageRef.label] — the text line this evidence
 * would have shown — never a broken-image placeholder; a 409 reads as "locked" instead.
 */
@Composable
fun EvidenceImage(image: EvidenceImageRef, token: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(image.url, token) { evidenceImageRequest(context, image.url, token) }
    var zoomed by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = image.note?.takeIf { it.isNotBlank() } ?: "evidence screenshot",
            // Compose's name for fit-to-width (scale to the row width, keep aspect ratio).
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
            loading = {
                Box(Modifier.fillMaxWidth().height(96.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                }
            },
            // Zoom hangs off the loaded picture only, so a failed load can't open an empty viewer.
            success = { SubcomposeAsyncImageContent(Modifier.fillMaxWidth().clickable { zoomed = true }) },
            error = { EvidenceLoadFailure(it, image.label) },
        )
        // The note carries capture kind, platform and source revision (including the marker when
        // that revision isn't a real commit) — that marker is the point, so it isn't clipped.
        image.note?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    if (zoomed) EvidenceZoomDialog(request, image) { zoomed = false }
}

/** Locked (409: no key on this host) reads as a state, not a fault; anything else falls back to the
 *  plain evidence label, so a flaky fetch looks like the pre-image UI rather than a rendering bug. */
@Composable
private fun EvidenceLoadFailure(state: AsyncImagePainter.State.Error, label: String) {
    val locked = (state.result.throwable as? HttpException)?.response?.code == HTTP_LOCKED
    Text(
        if (locked) "🔒 locked — no key for this artifact on this workspace" else label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Tap-to-zoom: the same authenticated request (so it's a cache hit) filling the screen; tap
 *  anywhere to dismiss. */
@Composable
private fun EvidenceZoomDialog(request: ImageRequest, image: EvidenceImageRef, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = request,
                contentDescription = image.note?.takeIf { it.isNotBlank() } ?: "evidence screenshot",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
            image.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }
}
