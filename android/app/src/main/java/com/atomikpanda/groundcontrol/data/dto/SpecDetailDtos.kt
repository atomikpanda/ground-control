package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReviewCriterion(
    val id: String,
    val text: String,
    val verdict: String,            // "unreviewed" | "approved" | "flagged"
    val comment: String? = null,    // MOS-217: flag-with-comment note
)

/** A prose section's verdict + optional flag note (MOS-172). Mirrors serve's
 *  `ProseVerdict` model as surfaced in `prose_verdicts` maps. */
@Serializable
data class ProseVerdictDto(
    val verdict: String,            // "unreviewed" | "approved" | "flagged"
    val comment: String? = null,
)

@Serializable
data class ReviewQuestion(
    val id: String,
    val text: String,
    val answer: String? = null,
)

@Serializable
data class SpecRecord(
    val id: String,
    val title: String,
    val status: String,
    @SerialName("affected_repos") val affectedRepos: List<String> = emptyList(),
    @SerialName("work_item_kind") val workItemKind: String? = null,
    @SerialName("acceptance_criteria") val acceptanceCriteria: List<ReviewCriterion> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<ReviewQuestion> = emptyList(),
    @SerialName("non_goals") val nonGoals: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    @SerialName("task_slug") val taskSlug: String? = null,
    val body: String = "",
    @SerialName("prose_verdicts") val proseVerdicts: Map<String, ProseVerdictDto> = emptyMap(),
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ReviewSummary(
    @SerialName("criteria_total") val criteriaTotal: Int = 0,
    val approved: Int = 0,
    val flagged: Int = 0,
    val unreviewed: Int = 0,
    @SerialName("open_questions_unanswered") val openQuestionsUnanswered: Int = 0,
)

/** Returned by every write endpoint; we patch state from it. `context` is intentionally
 *  unmodeled (display uses SpecRecord.body) and dropped by ignoreUnknownKeys. */
@Serializable
data class SpecReview(
    val id: String,
    val status: String,
    @SerialName("acceptance_criteria") val acceptanceCriteria: List<ReviewCriterion> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<ReviewQuestion> = emptyList(),
    @SerialName("prose_verdicts") val proseVerdicts: Map<String, ProseVerdictDto> = emptyMap(),
    val summary: ReviewSummary = ReviewSummary(),
)

@Serializable
data class DispatchResult(
    val spec: SpecRecord,
    @SerialName("task_slug") val taskSlug: String,
    val spawned: Boolean = false,
    val handoff: String = "",
)

@Serializable data class VerdictBody(@SerialName("criterion_id") val criterionId: String, val verdict: String, val comment: String? = null)
@Serializable data class ProseVerdictBody(@SerialName("section_id") val sectionId: String, val verdict: String, val comment: String? = null)
@Serializable data class AnswerBody(val answer: String)
@Serializable data class QuestionBody(val text: String)
@Serializable data class ApproveBody(@SerialName("bypass_gate") val bypassGate: Boolean = false)
@Serializable data class ReasonBody(val reason: String)

@Serializable
data class NewSpecBody(
    val title: String,
    val id: String? = null,
    @SerialName("affected_repos") val affectedRepos: List<String> = emptyList(),
    @SerialName("task_slug") val taskSlug: String? = null,
)
