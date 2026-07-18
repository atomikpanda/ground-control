package com.atomikpanda.groundcontrol.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide signal for "which thread (if any) is currently open AND foregrounded" (#378).
 *
 * Written by [com.atomikpanda.groundcontrol.ui.messages.ConversationScreen]'s RESUME/PAUSE
 * lifecycle (RESUMED ⇒ that thread is on screen and the app is foregrounded); read by
 * [NeedsYouReconciler] (running in WatchService / WatchBackstopWorker in the same process) to
 * suppress a notification for the thread the user is already looking at. Holds the [threadKey]
 * string so the reconciler compares against the same derivation the notifier posts under. Uses
 * ProcessLifecycleOwner-free foreground detection: the conversation screen's own RESUMED state is
 * exactly "open + foregrounded", so no extra dependency is needed.
 */
object OpenThreadRegistry {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    /** Mark (connId, threadId) as the open+foregrounded thread. */
    fun open(connId: String, threadId: String) {
        _current.value = threadKey(connId, threadId)
    }

    /**
     * Clear the open thread — but only if (connId, threadId) is still the one on record. Guards the
     * navigate-A→B race where B's RESUME (open) can land before A's PAUSE (close): a late close of A
     * must not wipe B's open.
     */
    fun close(connId: String, threadId: String) {
        _current.compareAndSet(threadKey(connId, threadId), null)
    }

    /** Snapshot for the reconciler's suppression check. */
    fun snapshot(): String? = _current.value
}
