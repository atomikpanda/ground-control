package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.ui.theme.JetBrainsMono
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * Bubble-tuned markdown rendering for agent messages (see `SpecBodyMarkdown`
 * for the full-document equivalent). Differs from that default in three ways:
 * all text/code/link colors inherit the bubble's foreground [color] instead
 * of the library's own `onBackground`-derived palette, block spacing is
 * collapsed to suit a compact chat bubble rather than a full-page document,
 * and code spans/blocks use [JetBrainsMono] to match the rest of the app's
 * monospace tokens.
 */
@Composable
fun MessageMarkdown(text: String, color: Color, modifier: Modifier = Modifier) {
    val bodyStyle = MaterialTheme.typography.bodyMedium
    Markdown(
        content = text,
        colors = markdownColor(
            text = color,
            codeText = color,
            // Distinct accent (not the bubble's foreground `color`) so links
            // are visually identifiable against surrounding prose/code in the
            // agent bubble, which sits on a `surfaceVariant` background.
            linkText = MaterialTheme.colorScheme.primary,
        ),
        typography = markdownTypography(
            text = bodyStyle,
            paragraph = bodyStyle,
            ordered = bodyStyle,
            bullet = bodyStyle,
            list = bodyStyle,
            code = bodyStyle.copy(fontFamily = JetBrainsMono),
        ),
        padding = markdownPadding(
            block = 0.dp,
            list = 4.dp,
            listItemBottom = 2.dp,
        ),
        modifier = modifier,
    )
}
