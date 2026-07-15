// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueHints.kt
package com.atomikpanda.groundcontrol.ui.queue

/**
 * The discoverability copy for the Queue's swipe model, in one place so the resting hint, the drag
 * stamps, and the onboarding coach mark all speak the same words. Swipe is THE interaction: right =
 * approve, left = request changes — these strings TEACH that gesture, they are never tap targets.
 */
object QueueHints {
    const val APPROVE = "Approve"
    const val REQUEST_CHANGES = "Request changes"
    /** Always-visible resting hint for approve-capable cards: teaches both swipe directions at once. */
    const val SWIPE_RESTING = "Request changes  ←   →  Approve"
    /** What a QuestionsCard needs (swipe-right is not an approve here). */
    const val ANSWER = "Answer to continue"
    /** What a DecisionCard needs (swipe-right is not an approve here). */
    const val DECIDE = "Choose an option"
}

/** Swipe-right = approve-all is only a valid action for the spec-review chunk cards. */
fun canApprove(card: QueueV2Card): Boolean = card is ProseCard || card is CriteriaCard

/** Swipe-left = request changes is valid for any spec-review chunk (not a thread decision). */
fun canRequestChanges(card: QueueV2Card): Boolean = card !is DecisionCard

/**
 * The one-line resting/what's-needed hint for a card. Approve-capable cards (Prose/Criteria) teach
 * the two swipe directions; a QuestionsCard/DecisionCard instead states what the operator must do to
 * proceed, so swiping right never implies an approve where it isn't one.
 */
fun queueCardHint(card: QueueV2Card): String = when (card) {
    is ProseCard, is CriteriaCard -> QueueHints.SWIPE_RESTING
    is QuestionsCard -> QueueHints.ANSWER
    is DecisionCard -> QueueHints.DECIDE
}
