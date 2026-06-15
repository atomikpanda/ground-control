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
}
