package com.atomikpanda.groundcontrol.notify

import kotlinx.coroutines.CompletableDeferred

/** Closed synchronously from Application.onCreate before any IO reset can be scheduled. */
internal object ReplyStartupGate {
    @Volatile private var resetComplete = CompletableDeferred<Unit>().apply { complete(Unit) }

    fun beginReset() {
        resetComplete = CompletableDeferred()
    }

    fun finishReset() {
        resetComplete.complete(Unit)
    }

    fun failReset(error: Throwable) {
        resetComplete.completeExceptionally(error)
    }

    suspend fun awaitReset() {
        resetComplete.await()
    }
}

internal suspend fun <T> withReplyStartupGate(block: suspend () -> T): T {
    val result = block()
    ReplyStartupGate.finishReset()
    return result
}
