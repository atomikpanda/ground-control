package com.atomikpanda.groundcontrol.notify

/** Durable state of an accepted notification action. */
enum class ReplyOutboxState {
    READY,
    WAITING_FOR_CONNECTION,
    IN_FLIGHT,
    SAFE_FAILURE_PENDING_RENDER,
    SAFE_FAILURE,
    DELIVERED_PENDING_RENDER,
    DELIVERED,
    UNCERTAIN_PENDING_RENDER,
    UNCERTAIN,
    STALE,
}

enum class ReplyInputKind { FREE_TEXT, OPTION }

/** A failure can only be retried manually unless it is proven to precede transmission. */
internal sealed interface ReplyDeliveryOutcome {
    data object Delivered : ReplyDeliveryOutcome
    data object SafePreTransmissionFailure : ReplyDeliveryOutcome
    data object Uncertain : ReplyDeliveryOutcome
}
