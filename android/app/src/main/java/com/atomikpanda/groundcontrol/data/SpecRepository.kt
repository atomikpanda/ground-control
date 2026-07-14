package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Per-workspace fetch result; a failure in one never sinks the others. */
data class WorkspaceSpecs(
    val connection: WorkspaceConnection,
    val specs: Result<List<SpecSummary>>,
)

class SpecRepository(private val api: SpecApi) {

    suspend fun listAllSpecs(connections: List<WorkspaceConnection>): List<WorkspaceSpecs> =
        coroutineScope {
            connections.map { conn ->
                async { WorkspaceSpecs(conn, runCatching { api.listSpecs(conn) }) }
            }.awaitAll()
        }

    /** Archive a spec (swipe-to-archive). Thin passthrough so the ViewModel only ever talks
     *  to the repository, matching [listAllSpecs]'s layering. */
    suspend fun archiveSpec(conn: WorkspaceConnection, id: String) = api.archiveSpec(conn, id)

    /** The full spec record (body + prose_verdicts + criteria/questions) for one spec.
     *  The Queue v2 chunk sourcing fetches this per `needs_review` spec to build its cards. */
    suspend fun specDetail(conn: WorkspaceConnection, id: String) = api.getSpec(conn, id)
}
