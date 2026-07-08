package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors

/** An agent decision rendered as the question text plus one tappable button per
 *  option — the recommended option (if any) is accented with the theme's
 *  "question" semantic color. Tapping an option reuses the same send path as
 *  the free-text compose bar ([onOption] is wired to `vm.send` by the caller).
 *
 *  [resolved] marks a decision that a human has already answered (a human
 *  message exists later in the thread) — its options are disabled and it's
 *  visually muted so it reads as history rather than a live prompt. */
@Composable
internal fun DecisionCard(
    text: String,
    decision: Decision,
    enabled: Boolean,
    resolved: Boolean = false,
    onOption: (String) -> Unit,
) {
    val colors = LocalSemanticColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(4.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    // Full contrast (matches the onSurface role used by agent
                    // prose bubbles in MessageRow) — the muted onSurfaceVariant
                    // role was too low-contrast for a question the operator
                    // needs to actually read before answering.
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (resolved) {
                    Text(
                        "Answered",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                decision.options.forEachIndexed { index, option ->
                    val isRecommended = index == decision.recommended
                    // Numbers are display-only: the label shows "N. option"
                    // but the raw option string is still what's sent below.
                    val label = "${index + 1}. $option"
                    if (isRecommended) {
                        Button(
                            onClick = { onOption(option) },
                            enabled = enabled,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.question),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    } else {
                        // OutlinedButton + explicit onSurface content color
                        // rather than the default FilledTonalButton, whose
                        // secondaryContainer/onSecondaryContainer pair falls
                        // back to the M3 baseline palette (this theme only
                        // overrides primary/surface/error roles) and reads as
                        // low-contrast against this card's surfaceVariant
                        // background.
                        OutlinedButton(
                            onClick = { onOption(option) },
                            enabled = enabled,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    }
                }
            }
        }
    }
}
