package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion

/** Actions Ground Control offers for a spec, gated by status. */
enum class SpecAction { APPROVE, APPROVE_ANYWAY, REQUEST_CHANGES, DISPATCH }

/** Criteria/questions are editable only while a spec is in review. */
fun isReviewInteractive(status: String): Boolean = status == "needs_review"

/** Status-aware action set. Covers all 8 mship spec statuses; unknown → none. */
fun availableActions(status: String): Set<SpecAction> = when (status) {
    "needs_review" -> setOf(SpecAction.REQUEST_CHANGES, SpecAction.APPROVE, SpecAction.APPROVE_ANYWAY)
    "approved" -> setOf(SpecAction.REQUEST_CHANGES, SpecAction.DISPATCH)
    else -> emptySet()   // captured, drafting, needs_clarification, dispatched, implemented, archived
}

/** Read-only banner for non-actionable statuses; null when the action bar is shown. */
fun statusBanner(status: String, taskSlug: String?): String? = when (status) {
    "needs_review", "approved" -> null
    "dispatched" -> "dispatched" + (taskSlug?.let { " · task $it" } ?: "")
    "needs_clarification" -> "needs clarification — re-draft to reopen review"
    "drafting" -> "drafting"
    "captured" -> "captured"
    "implemented" -> "implemented"
    "archived" -> "archived"
    else -> status
}

data class Summary(
    val criteriaTotal: Int,
    val approved: Int,
    val flagged: Int,
    val unreviewed: Int,
    val unansweredQuestions: Int,
)

fun summaryOf(criteria: List<ReviewCriterion>, questions: List<ReviewQuestion>): Summary = Summary(
    criteriaTotal = criteria.size,
    approved = criteria.count { it.verdict == "approved" },
    flagged = criteria.count { it.verdict == "flagged" },
    unreviewed = criteria.count { it.verdict == "unreviewed" },
    unansweredQuestions = questions.count { it.answer == null },
)

/** Split FastAPI's `cannot approve: a; b; c` 409 detail into individual blockers. */
fun parseApproveBlockers(detail: String): List<String> {
    val tail = detail.substringAfter("cannot approve:", detail)
    return tail.split(";").map { it.trim() }.filter { it.isNotEmpty() }
}
