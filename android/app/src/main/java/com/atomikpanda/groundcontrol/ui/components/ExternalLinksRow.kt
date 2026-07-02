package com.atomikpanda.groundcontrol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { link ->
            AssistChip(
                onClick = { uriHandler.openUri(link.url) },
                label = { Text("${link.title.ifBlank { link.provider }} ↗") },
            )
        }
    }
}
