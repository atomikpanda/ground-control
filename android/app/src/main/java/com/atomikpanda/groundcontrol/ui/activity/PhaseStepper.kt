package com.atomikpanda.groundcontrol.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** The five shared progress stages an operator sees after dispatch. */
enum class PhaseStep(val label: String) {
    DISPATCHED("Dispatched"),
    PLANNING("Planning"),
    BUILDING("Building"),
    REVIEW("Review"),
    DONE("Done"),
}

/**
 * Map reported task progress without treating missing data as evidence of dispatch.
 * Completion wins; a spec's explicit dispatched status can identify a task whose
 * first phase has not arrived yet.
 */
fun phaseStepFor(taskPhase: String?, done: Boolean, dispatched: Boolean = false): PhaseStep? = when {
    done -> PhaseStep.DONE
    taskPhase == "plan" -> PhaseStep.PLANNING
    taskPhase == "dev" -> PhaseStep.BUILDING
    taskPhase == "review" -> PhaseStep.REVIEW
    taskPhase == "run" -> PhaseStep.DONE
    taskPhase == null && dispatched -> PhaseStep.DISPATCHED
    else -> null
}

/**
 * One readable phase summary at every width. The stage count provides a non-color
 * progress cue; the decorative segments never pulse or imply live agent activity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhaseStepper(current: PhaseStep?, modifier: Modifier = Modifier) {
    val phaseLabel = current?.label ?: "Unavailable"
    val position = current?.let { "Step ${it.ordinal + 1} of ${PhaseStep.entries.size}" }
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "Task phase"
            stateDescription = if (position == null) phaseLabel else "$phaseLabel, $position"
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Task phase: $phaseLabel",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (position != null) {
                Text(
                    position,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (current != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PhaseStep.entries.forEach { step ->
                    Box(
                        Modifier.weight(1f).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (step.ordinal <= current.ordinal) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                }
            }
        }
    }
}
