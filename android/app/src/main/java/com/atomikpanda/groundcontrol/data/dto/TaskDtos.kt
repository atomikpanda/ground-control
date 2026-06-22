package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskSummary(
    val slug: String,
    val description: String = "",
    val phase: String,
    val branch: String,
    @SerialName("affected_repos") val affectedRepos: List<String> = emptyList(),
    @SerialName("pr_urls") val prUrls: Map<String, String> = emptyMap(),
    @SerialName("test_results") val testResults: Map<String, String> = emptyMap(),
    @SerialName("blocked_reason") val blockedReason: String? = null,
    @SerialName("depends_on") val dependsOn: List<String> = emptyList(),
    @SerialName("spec_count") val specCount: Int = 0,
    val orphan: Boolean = false,
    @SerialName("tests_failing") val testsFailing: Boolean = false,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class JournalEntry(
    val timestamp: String,
    val message: String,
    val repo: String? = null,
    val action: String? = null,
    @SerialName("test_state") val testState: String? = null,
    @SerialName("open_question") val openQuestion: String? = null,
    val category: String? = null,
    val iteration: Int? = null,
)
