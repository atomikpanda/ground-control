package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpecSummary(
    val id: String,
    val title: String,
    val status: String,
    @SerialName("task_slug") val taskSlug: String? = null,
    @SerialName("affected_repos") val affectedRepos: List<String> = emptyList(),
)

@Serializable
data class HealthResponse(
    val status: String,
    val workspace: String,
)
