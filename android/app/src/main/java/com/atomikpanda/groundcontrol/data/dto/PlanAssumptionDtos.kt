package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One plan-assumption flag surfaced for a task. Mirrors serve's `PlanAssumptionFlag`. */
@Serializable
data class PlanAssumptionFlag(
    val axis: String,
    val source: String,
    val reason: String,
    @SerialName("axis_fingerprint") val axisFingerprint: String? = null,
    val approved: Boolean = false,
    @SerialName("approved_by") val approvedBy: String? = null,
    @SerialName("approved_reason") val approvedReason: String? = null,
)

/** `GET /plan-assumptions/{slug}` and `POST /plan-assumptions/{slug}/approve` response envelope. */
@Serializable
data class PlanAssumptionsEnvelope(
    val task: String,
    val fresh: Boolean,
    val pending: Int,
    val flags: List<PlanAssumptionFlag> = emptyList(),
)

@Serializable
data class PlanFlagApproveBody(val axis: String, val reason: String? = null)

/** One row of `GET /plan-assumptions` — the fleet-wide list of tasks with plan-assumption
 *  flags, without their per-flag detail (that's [PlanAssumptionsEnvelope], fetched per-task). */
@Serializable
data class PlanAssumptionSummary(val task: String, val fresh: Boolean = true, val pending: Int = 0)
