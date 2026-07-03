package com.atomikpanda.groundcontrol.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.data.dto.ExternalLink

/**
 * Read-only display of a work item's external links (MOS-201 v1). Provider-labeled,
 * tap-to-open; no add/remove (deferred to co-design with MOS-210). Renders nothing
 * when there are no links, mirroring how `pr_urls` rows are gated — stay honest and
 * don't show an empty "LINKS" header.
 */
@Composable
fun ExternalLinksSection(links: List<ExternalLink>, modifier: Modifier = Modifier) {
    if (links.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    Column(modifier.fillMaxWidth()) {
        Text(
            "LINKS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
        )
        links.forEach { link ->
            // Only open web links, and never let a bad URL crash the app: a
            // non-http(s) scheme (tel:/intent:/file:/…) or a URL with no handler
            // would otherwise throw from openUri or launch an unintended target.
            // Non-web links still display (read-only) but aren't tappable.
            val openable = link.url.startsWith("http://", true) ||
                link.url.startsWith("https://", true)
            val label = "${link.provider} · ${link.title.ifBlank { link.url }}" +
                if (openable) " ↗" else ""
            Text(
                label,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (openable) {
                            Modifier.clickable { runCatching { uriHandler.openUri(link.url) } }
                        } else {
                            Modifier
                        },
                    )
                    .padding(16.dp, 4.dp),
                color = if (openable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
