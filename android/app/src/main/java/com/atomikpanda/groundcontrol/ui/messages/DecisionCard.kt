package com.atomikpanda.groundcontrol.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.SemanticColors
import kotlinx.coroutines.launch

/** An agent decision rendered as the question text plus one tappable button per
 *  option — the recommended option (if any) is accented with the theme's
 *  "question" semantic color. Tapping an option reuses the same send path as
 *  the free-text compose bar ([onOption] is wired to `vm.send` by the caller).
 *
 *  [resolved] marks a decision that a human has already answered (a human
 *  message exists later in the thread) — its options are disabled and it's
 *  visually muted so it reads as history rather than a live prompt.
 *
 *  Two extra affordances layer on top of the base tap-to-send behavior:
 *  - Every option carries a small "add comment" icon (chosen over a
 *    long-press: `Button`/`OutlinedButton`/`FilterChip` don't expose a long-
 *    press hook without dropping to a raw `combinedClickable`, and a visible
 *    icon is more discoverable than a hidden gesture anyway) that opens a
 *    [ModalBottomSheet] prefilled with the option text plus a caveat field.
 *    Sending it posts one combined message via [onOption] — the same path
 *    the plain option tap uses — so it works even when the decision is
 *    gated (`allow_free_text = false`): that gate only locks the compose
 *    bar's free-text field, not option-anchored sends.
 *  - When [Decision.multi] is set, options render as toggle chips that
 *    accumulate a local selection instead of sending immediately; a trailing
 *    "Send" button combines the selection into one message. Non-multi
 *    decisions keep the original immediate-send-on-tap behavior unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DecisionCard(
    text: String,
    decision: Decision,
    enabled: Boolean,
    resolved: Boolean = false,
    onOption: (String) -> Unit,
) {
    val colors = LocalSemanticColors.current
    // Which option (if any) the comment sheet is currently open for -- null
    // means closed. Anchored to an index rather than the option text so it
    // stays correct even if two options happen to have identical text.
    var commentOptionIndex by remember { mutableStateOf<Int?>(null) }
    // Local multiselect accumulator: indices of currently toggled options.
    // Only read/written when decision.multi -- irrelevant (and harmless) for
    // single-select decisions. `remember` keys this to the DecisionCard call
    // site, which itemsIndexed keys by message.id, so each decision message
    // gets its own independent selection.
    var selected by remember { mutableStateOf(setOf<Int>()) }

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
                    DecisionOptionRow(
                        index = index,
                        option = option,
                        isRecommended = index == decision.recommended,
                        multi = decision.multi,
                        isSelected = index in selected,
                        enabled = enabled,
                        colors = colors,
                        onTap = {
                            if (decision.multi) {
                                selected = if (index in selected) selected - index else selected + index
                            } else {
                                onOption(option)
                            }
                        },
                        onComment = { commentOptionIndex = index },
                    )
                }
                if (decision.multi) {
                    Button(
                        onClick = {
                            onOption(formatMultiSelectMessage(decision.options, selected))
                            selected = emptySet()
                        },
                        enabled = enabled && selected.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.question),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Send") }
                }
            }
        }
    }

    val sheetIndex = commentOptionIndex
    if (sheetIndex != null) {
        CommentSheet(
            optionText = decision.options[sheetIndex],
            onDismiss = { commentOptionIndex = null },
            onSend = { caveat -> onOption(formatCommentMessage(sheetIndex, decision.options[sheetIndex], caveat)) },
        )
    }
}

/** One option's row: the tappable option (a filled/outlined button for
 *  single-select, a toggle chip for multi-select) plus its "add comment"
 *  icon. Numbers are display-only: the label shows "N. option" but the raw
 *  option string is still what's sent. */
@Composable
private fun DecisionOptionRow(
    index: Int,
    option: String,
    isRecommended: Boolean,
    multi: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    colors: SemanticColors,
    onTap: () -> Unit,
    onComment: () -> Unit,
) {
    val label = "${index + 1}. $option"
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            multi -> {
                FilterChip(
                    selected = isSelected,
                    onClick = onTap,
                    enabled = enabled,
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
            isRecommended -> {
                Button(
                    onClick = onTap,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.question),
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
            }
            else -> {
                // OutlinedButton + explicit onSurface content color rather
                // than the default FilledTonalButton, whose
                // secondaryContainer/onSecondaryContainer pair falls back to
                // the M3 baseline palette (this theme only overrides
                // primary/surface/error roles) and reads as low-contrast
                // against this card's surfaceVariant background.
                OutlinedButton(
                    onClick = onTap,
                    enabled = enabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
            }
        }
        IconButton(onClick = onComment, enabled = enabled) {
            Icon(Icons.Filled.AddComment, contentDescription = "Comment on option ${index + 1}")
        }
    }
}

/** Compose-style bottom sheet for annotating a single option with a caveat.
 *  Prefills the option text (read-only context, not editable here) plus a
 *  multiline comment field; Send posts one combined message via [onSend]. A
 *  full [ModalBottomSheet] (rather than an AlertDialog) so there's room for
 *  a caveat that runs long. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentSheet(
    optionText: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var caveat by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(optionText, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = caveat,
                onValueChange = { caveat = it },
                label = { Text("Comment") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val toSend = caveat
                    caveat = ""                       // clear first so the button disables next frame (no double-submit)
                    onSend(toSend)
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                },
                enabled = caveat.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send") }
        }
    }
}

/** Formats the combined human message posted by the per-option comment
 *  affordance: 1-based option number, the option text verbatim, an em dash,
 *  then the caveat -- e.g. `"1. Ship it — but gate behind a flag"`. */
internal fun formatCommentMessage(index: Int, optionText: String, caveat: String): String =
    "${index + 1}. $optionText — $caveat"

/** Formats the combined human message posted by a multiselect decision's
 *  "Send" button: each selected option as its 1-based number + option text,
 *  joined by "; ", e.g. `"Selected: 1. Add index; 3. Backfill nulls"`.
 *  Selected indices are sorted so the message reads in option order
 *  regardless of tap order. */
internal fun formatMultiSelectMessage(options: List<String>, selectedIndices: Collection<Int>): String {
    val parts = selectedIndices.sorted().joinToString("; ") { i -> "${i + 1}. ${options[i]}" }
    return "Selected: $parts"
}
