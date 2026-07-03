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
            Text(
                "${link.provider} · ${link.title.ifBlank { link.url }} ↗",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(link.url) }
                    .padding(16.dp, 4.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
