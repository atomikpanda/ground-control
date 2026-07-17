package com.atomikpanda.groundcontrol.ui.activity

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.notify.parseTimestampMillis
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle

/** Named activity-chip thresholds (ac5). */
object LiveThresholds {
    /** Last activity newer than this reads as actively "working". */
    const val WORKING_WINDOW_MS = 90_000L
    /** Last activity older than this reads as "quiet Nm". */
    const val QUIET_AFTER_MS = 300_000L
}

/** Classified live state for the activity chip. */
sealed interface LiveStatus {
    data object Working : LiveStatus
    /** Between the working window and the quiet threshold: recently active, neutral. */
    data object Idle : LiveStatus
    data class Quiet(val minutes: Long) : LiveStatus
    /** Task finished/merged. */
    data object Done : LiveStatus
    /** No activity timestamp yet — neutral, never a false "working" (ac8). */
    data object Unknown : LiveStatus
}

/**
 * Pure classifier. `merged` wins; a null timestamp is [LiveStatus.Unknown] (never "working").
 * Clock skew (negative age) is clamped to 0 so a slightly-future stamp still reads as working.
 */
fun liveStatus(lastActivityMillis: Long?, nowMillis: Long, merged: Boolean): LiveStatus = when {
    merged -> LiveStatus.Done
    lastActivityMillis == null -> LiveStatus.Unknown
    else -> {
        val age = (nowMillis - lastActivityMillis).coerceAtLeast(0)
        when {
            age < LiveThresholds.WORKING_WINDOW_MS -> LiveStatus.Working
            age >= LiveThresholds.QUIET_AFTER_MS -> LiveStatus.Quiet(age / 60_000L)
            else -> LiveStatus.Idle
        }
    }
}

/** ISO-string overload: parses via [parseTimestampMillis] then classifies. */
fun liveStatus(lastActivityIso: String?, nowMillis: Long, merged: Boolean): LiveStatus =
    liveStatus(parseTimestampMillis(lastActivityIso), nowMillis, merged)

/** Pill chip: a colored dot (pulsing while working) + a short label. */
@Composable
fun LiveChip(
    lastActivityIso: String?,
    merged: Boolean,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSemanticColors.current
    val status = liveStatus(lastActivityIso, nowMillis, merged)
    val (label, tint) = when (status) {
        is LiveStatus.Working -> "working" to colors.approval
        is LiveStatus.Idle -> "idle" to colors.muted
        is LiveStatus.Quiet -> "quiet ${status.minutes}m" to colors.muted
        is LiveStatus.Done -> "done" to MaterialTheme.colorScheme.primary
        is LiveStatus.Unknown -> "—" to colors.muted
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only the Working branch mounts the infinite transition, so an idle/quiet/done
        // chip doesn't keep the choreographer ticking for a discarded pulse value.
        if (status is LiveStatus.Working) PulsingDot(tint) else StatusDot(tint)
        Spacer(Modifier.size(6.dp))
        Text(label, style = MonoStyle, color = tint)
    }
}

@Composable
private fun StatusDot(tint: Color, alpha: Float = 1f) {
    Box(Modifier.size(8.dp).clip(CircleShape).alpha(alpha).background(tint))
}

@Composable
private fun PulsingDot(tint: Color) {
    val pulse by rememberInfiniteTransition(label = "chipPulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "chipAlpha",
    )
    StatusDot(tint, pulse)
}
