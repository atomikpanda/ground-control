package com.atomikpanda.groundcontrol.ui.home

import androidx.compose.ui.graphics.Color
import com.atomikpanda.groundcontrol.ui.theme.SemanticColors

/**
 * Pure: a "Needs you" item's urgency tier → its semantic accent.
 *
 * Lives in `ui.home` (not `ui.theme`) so the theme package stays a leaf —
 * `ui.home` depends on `ui.theme`, never the reverse.
 */
fun accentFor(tier: UrgencyTier, colors: SemanticColors): Color = when (tier) {
    UrgencyTier.BLOCKER -> colors.blocker
    UrgencyTier.QUESTION -> colors.question
    UrgencyTier.APPROVAL -> colors.approval
}
