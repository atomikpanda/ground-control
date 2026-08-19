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

/** The host's runner passthrough (#471 AC6; #473 fills `state` with idle|active|degraded). */
@Serializable
data class RunnerBlock(
    val enabled: Boolean = false,
    val state: String? = null,
)

/** One workspace discovered by a host daemon's registry (#472, GET /workspaces). */
@Serializable
data class WorkspaceInfo(
    val id: String,
    val name: String,
    val state: String,
    val path: String = "",
    val detail: String = "",
    val runner: RunnerBlock? = null,
)

@Serializable
data class WorkspacesResponse(
    val workspaces: List<WorkspaceInfo> = emptyList(),
)

/**
 * One entry of the relay host directory (#471, GET enroll.<relay>/hosts).
 *
 * `host_id` is null on a `pending-approval` row — a VM that has posted its key
 * but that nobody has approved yet is visible on the phone before it has an
 * identity (AC1). `refresh` is published on this route and nowhere else.
 */
@Serializable
data class HostInfo(
    @SerialName("host_id") val hostId: String? = null,
    val state: String = "",
    val label: String = "",
    @SerialName("instance_id") val instanceId: String = "",
    val subdomain: String = "",
    @SerialName("public_url") val publicUrl: String = "",
    val refresh: String? = null,
    @SerialName("last_seen") val lastSeen: Double? = null,
    val runner: RunnerBlock? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class HostsResponse(val hosts: List<HostInfo> = emptyList())

/** The host app's unauthenticated `GET /health`: ids and counts only. */
@Serializable
data class HostHealth(
    val status: String = "",
    @SerialName("host_id") val hostId: String? = null,
    @SerialName("instance_id") val instanceId: String = "",
    val workspaces: Int = 0,
    val degraded: Int = 0,
    val runner: RunnerBlock? = null,
)

/** `POST {host}/host/token` — the refresh credential in, a short-lived bearer out. */
@Serializable
data class HostTokenBody(val refresh: String)

@Serializable
data class HostTokenResponse(
    val token: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
)
