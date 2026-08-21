package com.atomikpanda.groundcontrol.notify

/** Clears pre-capability needs-you actions exactly once, but only after cancellation completes. */
internal class ReplyMigrationResetter(
    private val database: NotifiedDatabase,
    private val cancelLegacyNeedsYou: () -> Unit,
) {
    suspend fun resetIfRequired() {
        val state = database.replyMigrationStateDao().get() ?: return
        if (!state.legacyNotificationResetRequired) return
        cancelLegacyNeedsYou()
        database.replyMigrationStateDao().insert(state.copy(legacyNotificationResetRequired = false))
    }
}
