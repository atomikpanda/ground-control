package com.atomikpanda.groundcontrol.ui.activity

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle

/** The five shared progress stages an operator sees after dispatch. */
enum class PhaseStep(val label: String) {
    DISPATCHED("Dispatched"),
    PLANNING("Planning"),
    BUILDING("Building"),
    REVIEW("Review"),
    DONE("Done"),
}

/**
 * Map a mothership task phase (`plan`/`dev`/`review`/`run`) plus a `done` flag (task
 * finished/merged) onto the shared stepper. `done` wins. An unknown/absent phase degrades to
 * [PhaseStep.DISPATCHED] so a freshly dispatched task with no phase yet still renders sensibly.
 */
fun phaseStepFor(taskPhase: String?, done: Boolean): PhaseStep = when {
    done -> PhaseStep.DONE
    taskPhase == "plan" -> PhaseStep.PLANNING
    taskPhase == "dev" -> PhaseStep.BUILDING
    taskPhase == "review" -> PhaseStep.REVIEW
    taskPhase == "run" -> PhaseStep.DONE
    else -> PhaseStep.DISPATCHED
}

/**
 * Horizontal 5-dot stepper. Completed stages read in the approval hue, the current stage pulses in
 * the primary color, future stages are muted. `compact = true` drops the labels for tight rows
 * (e.g. the spec-detail header).
 */
@Composable
fun PhaseStepper(current: PhaseStep, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = LocalSemanticColors.current
    val pulse by rememberInfiniteTransition(label = "phasePulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "phaseAlpha",
    )
    val dot = if (compact) 8.dp else 12.dp
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhaseStep.entries.forEachIndexed { i, step ->
            val isDone = i < current.ordinal
            val isCurrent = i == current.ordinal
            val tint = when {
                isDone -> colors.approval
                isCurrent -> MaterialTheme.colorScheme.primary
                else -> colors.muted
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    Modifier
                        .size(dot)
                        .clip(CircleShape)
                        .alpha(if (isCurrent) pulse else 1f)
                        .background(tint),
                )
                if (!compact) {
                    Text(step.label, style = MonoStyle, color = tint)
                }
            }
        }
    }
}
