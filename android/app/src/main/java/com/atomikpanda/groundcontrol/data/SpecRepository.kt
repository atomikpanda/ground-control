package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.InboxAction
import com.atomikpanda.groundcontrol.data.dto.InboxFilter
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

    suspend fun listAllSpecs(
        connections: List<WorkspaceConnection>,
        filter: InboxFilter = InboxFilter.ALL,
        query: String? = null,
    ): List<WorkspaceSpecs> =
        coroutineScope {
            connections.map { conn ->
                async { WorkspaceSpecs(conn, runCatching { api.listSpecs(conn, filter, query) }) }
            }.awaitAll()
        }


    suspend fun mutateSpecInbox(
        conn: WorkspaceConnection,
        id: String,
        action: InboxAction,
        mutationId: String,
    ) = api.mutateSpecInbox(conn, id, action, mutationId)
}
