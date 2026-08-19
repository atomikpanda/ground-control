// app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/EvidenceImage.kt
package com.atomikpanda.groundcontrol.ui.specdetail

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.atomikpanda.groundcontrol.data.EvidenceLockedException
import kotlinx.coroutines.CancellationException

/**
 * An image artifact backing an acceptance criterion. Bytes come from the screen's SpecApi path,
 * so host routing, bearer minting, safe-GET failover, and contact tracking stay centralized.
 */
@Composable
fun EvidenceImage(
    image: EvidenceImageRef,
    load: suspend (String) -> ByteArray,
    modifier: Modifier = Modifier,
) {
    var bytes by remember(image.ref) { mutableStateOf<ByteArray?>(null) }
    var loadError by remember(image.ref) { mutableStateOf<Throwable?>(null) }
    var zoomed by remember { mutableStateOf(false) }

    LaunchedEffect(image.ref) {
        try {
            bytes = load(image.ref)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            loadError = error
        }
    }
    val model = bytes

    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        when {
            loadError != null -> EvidenceLoadFailure(loadError, image.label)
            model == null -> {
                Box(Modifier.fillMaxWidth().height(96.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
            else -> SubcomposeAsyncImage(
                model = model,
                contentDescription = image.note?.takeIf { it.isNotBlank() }
                    ?: "evidence screenshot",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
                loading = {
                    Box(Modifier.fillMaxWidth().height(96.dp), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    }
                },
                success = {
                    SubcomposeAsyncImageContent(
                        Modifier.fillMaxWidth().clickable { zoomed = true },
                    )
                },
                error = { EvidenceLoadFailure(it.result.throwable, image.label) },
            )
        }
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

    if (zoomed && model != null) EvidenceZoomDialog(model, image) { zoomed = false }
}

/** Locked evidence reads as a state; every other failure falls back to the evidence label. */
fun evidenceLoadFailureText(throwable: Throwable?, label: String): String =
    if (throwable is EvidenceLockedException) {
        "🔒 locked — no key for this artifact on this workspace"
    } else {
        label
    }

@Composable
private fun EvidenceLoadFailure(throwable: Throwable?, label: String) {
    Text(
        evidenceLoadFailureText(throwable, label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Tap-to-zoom: the decoded bytes fill the screen; tap anywhere to dismiss. */
@Composable
private fun EvidenceZoomDialog(bytes: ByteArray, image: EvidenceImageRef, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = bytes,
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
