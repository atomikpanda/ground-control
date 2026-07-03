package com.atomikpanda.groundcontrol.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.data.dto.ExternalLink

/**
 * A wrapping row of tap-through chips for a work item's external links
 * (GitHub PR, Linear issue, Notion doc, etc). Renders nothing when empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExternalLinksRow(links: List<ExternalLink>, modifier: Modifier = Modifier) {
    if (links.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { link ->
            // Only open web links, and never let a bad URL crash the app: a non-http(s)
            // scheme (tel:/intent:/file:/…) or a URL with no handler would otherwise throw
            // from openUri or launch an unintended target. Non-web links show disabled.
            val openable = link.url.startsWith("http://", true) ||
                link.url.startsWith("https://", true)
            AssistChip(
                enabled = openable,
                onClick = {
                    // openUri can still throw for an http(s) URL (e.g. no browser
                    // installed); tell the user instead of a silent no-op.
                    runCatching { uriHandler.openUri(link.url) }.onFailure {
                        Toast.makeText(context, "Couldn't open link", Toast.LENGTH_SHORT).show()
                    }
                },
                label = {
                    Text("${link.title.ifBlank { link.provider }}" + if (openable) " ↗" else "")
                },
            )
        }
    }
}
