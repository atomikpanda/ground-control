package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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

/** Readiness summary as a row of colored counter chips (approved/flagged/unanswered). */
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
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(chip.label) },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = tint,
                    disabledLeadingIconContentColor = tint,
                ),
            )
        }
    }
}
