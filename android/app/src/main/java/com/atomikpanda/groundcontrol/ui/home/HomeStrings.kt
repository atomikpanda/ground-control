package com.atomikpanda.groundcontrol.ui.home

/**
 * Header label for Home's leading "Needs you" section. A positive count reads "Needs you · N";
 * zero is a distinct caught-up state (never "Needs you · 0", which would look like an action).
 */
fun needsYouHeader(count: Int): String =
    if (count > 0) "Needs you · $count" else "You're all caught up"

/**
 * CTA that funnels into the Queue tab. Null when there is nothing to review, so the caught-up
 * state shows no action. N is the needs-you count (matches the header).
 */
fun reviewInQueueCta(count: Int): String? =
    if (count > 0) "Review $count in Queue" else null
