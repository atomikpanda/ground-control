package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkItemSummary(
    val id: String,
    val kind: String,
    val title: String,
    val phase: String,
    @SerialName("spec_id") val specId: String? = null,
    @SerialName("task_slugs") val taskSlugs: List<String> = emptyList(),
    @SerialName("thread_ids") val threadIds: List<String> = emptyList(),
    val attention: Attention = Attention(),
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("external_links") val externalLinks: List<ExternalLink> = emptyList(),
)

/** A read-only pointer to an external tracker/resource (GitHub, Linear, …). MOS-201. */
@Serializable
data class ExternalLink(
    val provider: String,
    val url: String,
    val title: String = "",
)

@Serializable
data class Attention(
    @SerialName("needs_approval") val needsApproval: Boolean = false,
    @SerialName("needs_decision") val needsDecision: Boolean = false,
    val blocked: Boolean = false,
    @SerialName("needs_review") val needsReview: Boolean = false,
    @SerialName("blocked_tasks") val blockedTasks: Int = 0,
    @SerialName("total_tasks") val totalTasks: Int = 0,
)
