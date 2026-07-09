package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
 *
 * Link taps are intercepted via a custom [UriHandler]: `groundcontrol://` entity links
 * (item/spec/task — see [EntityLink]) are routed to [onOpenEntity] for in-app navigation,
 * while everything else (http/https, `groundcontrol://thread`, anything unrecognized) falls
 * through to the ambient [LocalUriHandler] (external browser / OS deep-link handling).
 */
@Composable
fun MessageMarkdown(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onOpenEntity: (kind: String, id: String) -> Unit = { _, _ -> },
) {
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val defaultHandler = LocalUriHandler.current
    val latestOnOpenEntity by rememberUpdatedState(onOpenEntity)
    val handler = remember(defaultHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val ref = EntityLink.parse(uri)
                if (ref != null) latestOnOpenEntity(ref.first, ref.second) else defaultHandler.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler) {
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
}
