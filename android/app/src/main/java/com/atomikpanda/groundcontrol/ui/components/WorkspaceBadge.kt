package com.atomikpanda.groundcontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atomikpanda.groundcontrol.ui.theme.WorkspaceIdentity
import com.atomikpanda.groundcontrol.ui.theme.WorkspacePalette

/** The one reusable per-workspace identity badge: a solid rounded-square swatch with a white glyph.
 *  Rendered everywhere a workspace is referenced so identity stays consistent across the app (ac7). */
@Composable
fun WorkspaceBadge(
    identity: WorkspaceIdentity,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(percent = 28)).background(identity.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            identity.glyph,
            color = WorkspacePalette.onColor,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.55f).sp,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
