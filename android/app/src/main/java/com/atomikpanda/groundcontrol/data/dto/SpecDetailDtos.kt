package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReviewCriterion(
    val id: String,
    val text: String,
    val verdict: String,            // "unreviewed" | "approved" | "flagged"
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
    @SerialName("acceptance_criteria") val acceptanceCriteria: List<ReviewCriterion> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<ReviewQuestion> = emptyList(),
    @SerialName("non_goals") val nonGoals: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    @SerialName("task_slug") val taskSlug: String? = null,
    val body: String = "",
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
    val summary: ReviewSummary = ReviewSummary(),
)

@Serializable
data class DispatchResult(
    val spec: SpecRecord,
    @SerialName("task_slug") val taskSlug: String,
    val spawned: Boolean = false,
    val handoff: String = "",
)

@Serializable data class VerdictBody(@SerialName("criterion_id") val criterionId: String, val verdict: String)
@Serializable data class AnswerBody(val answer: String)
@Serializable data class QuestionBody(val text: String)
@Serializable data class ApproveBody(@SerialName("bypass_gate") val bypassGate: Boolean = false)
@Serializable data class ReasonBody(val reason: String)
