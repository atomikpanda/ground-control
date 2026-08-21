package com.atomikpanda.groundcontrol.notify

/** Durable outcome for one notification action capability. */
enum class ReplyActionState {
    READY,
    IN_FLIGHT,
    SAFE_FAILURE_PENDING_RENDER,
    DELIVERED_PENDING_RENDER,
    UNCERTAIN_PENDING_RENDER,
    DELIVERED,
    UNCERTAIN,
}


/**
 * A response proves that the server rejected the request only for 4xx. A transport failure or
 * server error can occur after append, so retrying it would risk a duplicate message.
 */
internal fun replyFailureState(httpStatusCode: Int?): ReplyActionState =
    if (httpStatusCode in 400..499) ReplyActionState.READY else ReplyActionState.UNCERTAIN
