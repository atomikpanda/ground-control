package com.atomikpanda.groundcontrol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// A pill for the compose field. Local so it doesn't round every button/card via a theme override
// (AppShapes caps at 8dp for the instrument look).
private val ComposeFieldShape = RoundedCornerShape(24.dp)

/**
 * The app's single reusable free-text compose input (#282): an auto-expanding multiline field that
 * grows with content up to [maxLines] and then scrolls, with a trailing Send button (or a spinner
 * while [inFlight]). Return inserts a **newline** — there is no Return-to-send; the button sends
 * (mobile-native, per the 2026-07-09 decision). Used by the thread compose bar, the Queue
 * reject/flag-comment sheet, and any other place the app writes free text, so behavior stays
 * consistent. The caller owns the [value] state and clears it in [onSend].
 */
@Composable
fun MultilineComposeInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message…",
    enabled: Boolean = true,
    inFlight: Boolean = false,
    sendEnabled: Boolean = value.isNotBlank(),
    maxLines: Int = 6,
    sendIcon: ImageVector = Icons.AutoMirrored.Filled.Send,
    sendDescription: String = "Send",
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        // Bottom-aligned so the Send button sits beside the last line as the field grows.
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = ComposeFieldShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(placeholder) },
                enabled = enabled,
                // Not singleLine: multiline, so Return inserts a newline. Grows to maxLines, then scrolls.
                maxLines = maxLines,
                shape = ComposeFieldShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
        if (inFlight) {
            CircularProgressIndicator(Modifier.padding(8.dp))
        } else {
            FilledIconButton(onClick = onSend, enabled = sendEnabled) {
                Icon(sendIcon, contentDescription = sendDescription)
            }
        }
    }
}
