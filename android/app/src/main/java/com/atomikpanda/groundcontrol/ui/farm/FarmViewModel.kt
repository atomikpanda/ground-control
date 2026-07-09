package com.atomikpanda.groundcontrol.ui.farm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

sealed interface FarmUiState {
    data object Loading : FarmUiState
    data class Content(val groups: List<PhaseGroup>, val errored: Boolean) : FarmUiState
}

/** Single-workspace farm: loads GET /items for one connection and bins by phase.
 *  Shape mirrors WorkspaceViewModel (concrete conn + optional testScope). */
class FarmViewModel(
    private val api: SpecApi,
    private val conn: WorkspaceConnection,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<FarmUiState>(FarmUiState.Loading)
    val state: StateFlow<FarmUiState> = _state.asStateFlow()

    fun refresh(): Job = (testScope ?: viewModelScope).launch {
        _state.value = FarmUiState.Loading
        _state.value = runCatching { api.listItems(conn) }.fold(
            onSuccess = { FarmUiState.Content(groupByPhase(it), errored = false) },
            onFailure = {
                if (it is CancellationException) throw it
                FarmUiState.Content(emptyList(), errored = true)
            },
        )
    }

    /** Toggle a work item's unattended flag. Updates local state immediately (the server's
     *  own list/get responses don't echo `unattended` back into the summary yet, so a
     *  post-then-refetch would silently drop the change) and rolls back to the pre-toggle
     *  value on failure — mirrors [refresh]'s runCatching style, including re-throwing
     *  CancellationException so scope cancellation isn't swallowed as an ordinary failure. */
    fun setUnattended(item: WorkItemSummary, on: Boolean): Job = (testScope ?: viewModelScope).launch {
        val original = item.unattended
        applyToItem(item.id) { it.copy(unattended = on) }
        runCatching { api.setUnattended(conn, item.id, on) }.onFailure {
            if (it is CancellationException) throw it
            applyToItem(item.id) { summary -> summary.copy(unattended = original) }
        }
    }

    /** Quick actions on the WorkItem card: "Mark done" (`phase = "done"`) and "Reopen"
     *  (`phase = null`, clearing the override so the item returns to its derived phase).
     *  Sets `phaseOverride` immediately (the server's list/get responses don't echo the field
     *  back into a full summary either) — [applyToItem] regroups afterwards so the card visibly
     *  moves to the new phase's section rather than sitting in its old group until the next
     *  refresh — and rolls back to the pre-call value on failure — same shape as [setUnattended],
     *  including re-throwing CancellationException so scope cancellation isn't swallowed as an
     *  ordinary failure.
     *
     *  Per-item mutex: a rapid second tap on the same card (e.g. Mark done immediately followed
     *  by Reopen, before the first request resolves) would otherwise let the second call capture
     *  the first call's not-yet-confirmed optimistic write as *its* rollback target — so if both
     *  fail, the card can end up restored to a value the server never confirmed. Serializing per
     *  item means a call only ever reads `original` once any earlier call on that item has fully
     *  settled (confirmed or rolled back), so it's always the last confirmed value. */
    fun setItemPhase(item: WorkItemSummary, phase: String?): Job = (testScope ?: viewModelScope).launch {
        phaseLockFor(item.id).withLock {
            // NB: `?:` would be wrong here -- a confirmed `phaseOverride` of `null` is a valid
            // value, not "not found", and must not fall through to the (possibly stale) `item`
            // argument.
            val existing = findItem(item.id)
            val original = if (existing != null) existing.phaseOverride else item.phaseOverride
            applyToItem(item.id) { it.copy(phaseOverride = phase) }
            runCatching { api.setItemPhase(conn, item.id, phase) }.onFailure {
                if (it is CancellationException) throw it
                applyToItem(item.id) { summary -> summary.copy(phaseOverride = original) }
            }
        }
    }

    private val phaseLocks = mutableMapOf<String, Mutex>()
    private fun phaseLockFor(id: String): Mutex = phaseLocks.getOrPut(id) { Mutex() }

    private fun findItem(id: String): WorkItemSummary? =
        (_state.value as? FarmUiState.Content)?.groups
            ?.firstNotNullOfOrNull { g -> g.items.firstOrNull { it.id == id } }

    /** Applies `transform` to the item and regroups the full item set by (effective) phase, so a
     *  `phaseOverride` change (or a rollback of one) that crosses a phase boundary actually moves
     *  the card between sections instead of leaving it in its previous group. */
    private fun applyToItem(id: String, transform: (WorkItemSummary) -> WorkItemSummary) {
        val current = _state.value as? FarmUiState.Content ?: return
        val updated = current.groups.flatMap { it.items }.map { if (it.id == id) transform(it) else it }
        _state.value = current.copy(groups = groupByPhase(updated))
    }
}
