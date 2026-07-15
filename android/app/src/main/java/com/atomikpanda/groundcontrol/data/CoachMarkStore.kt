package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A one-time "coach mark seen" flag. Persisted so the Queue's first-run swipe onboarding shows once
 * and never reappears (the header info affordance re-opens it on demand). [seen] is nullable so
 * callers can distinguish "not loaded yet" (null) from a definite false — a returning user whose
 * stored value is true never flashes the overlay while the store hydrates.
 */
interface CoachMarkStore {
    /** null = still loading from disk; false = never seen; true = dismissed at least once. */
    val seen: StateFlow<Boolean?>
    suspend fun markSeen()

    /** Fire-and-forget persist on the store's own (app-level) scope, so a caller dismissing the
     *  overlay doesn't have to hold a stable scope — the write can't be cancelled by the UI leaving
     *  composition mid-write (which would let the one-time overlay reappear). */
    fun markSeenAsync()
}

// Reuses the single app-wide settings DataStore (see SettingsRepository.settingsStore) — a second
// delegate for the same file crashes, so we only add a key here.
internal val QUEUE_COACH_MARK_SEEN = booleanPreferencesKey("queue_coach_mark_seen")

class DataStoreCoachMarkStore(
    private val context: Context,
    private val scope: CoroutineScope,
) : CoachMarkStore {
    override val seen: StateFlow<Boolean?> =
        context.settingsStore.data.map { it[QUEUE_COACH_MARK_SEEN] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun markSeen() {
        context.settingsStore.edit { it[QUEUE_COACH_MARK_SEEN] = true }
    }

    override fun markSeenAsync() { scope.launch { markSeen() } }
}

/** In-memory [CoachMarkStore] for tests/previews (no DataStore/Context). */
class InMemoryCoachMarkStore(seen: Boolean = false) : CoachMarkStore {
    private val _seen = MutableStateFlow<Boolean?>(seen)
    override val seen: StateFlow<Boolean?> = _seen.asStateFlow()
    override suspend fun markSeen() { _seen.value = true }
    override fun markSeenAsync() { _seen.value = true }
}
