package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.data.Summary
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors

/** Semantic role for a readiness chip; kept color-free so it is unit-testable. */
enum class ChipRole { APPROVED, FLAGGED, UNANSWERED }

data class ReadinessChip(val label: String, val role: ChipRole)

/** Derive the three readiness chips from a spec's [Summary]. Always three chips, even at zero. */
fun readinessChips(sum: Summary): List<ReadinessChip> = listOf(
    ReadinessChip("${sum.approved}/${sum.criteriaTotal} approved", ChipRole.APPROVED),
    ReadinessChip("${sum.flagged} flagged", ChipRole.FLAGGED),
    ReadinessChip("${sum.unansweredQuestions} unanswered", ChipRole.UNANSWERED),
)

/**
 * Readiness summary as a row of non-interactive, per-role colored pills (approved/flagged/unanswered).
 * Built as tinted [Surface]s rather than a disabled `AssistChip` — a disabled chip renders a flat gray
 * container for every role (only the label picks up color), which would make the three read identically.
 */
@Composable
fun ReadinessChipsRow(sum: Summary, modifier: Modifier = Modifier) {
    val colors = LocalSemanticColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        readinessChips(sum).forEach { chip ->
            val tint = when (chip.role) {
                ChipRole.APPROVED -> colors.approval
                ChipRole.FLAGGED -> colors.error
                ChipRole.UNANSWERED -> colors.muted
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = tint.copy(alpha = 0.12f),
                contentColor = tint,
                border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
            ) {
                Text(
                    chip.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
