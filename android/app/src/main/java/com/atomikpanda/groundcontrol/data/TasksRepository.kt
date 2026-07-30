package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.PlanAssumptionsEnvelope
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class WorkspaceTasks(
    val connection: WorkspaceConnection,
    val tasks: Result<List<TaskSummary>>,
)

class TasksRepository(internal val api: SpecApi) {
    suspend fun listAllTasks(connections: List<WorkspaceConnection>): List<WorkspaceTasks> =
        coroutineScope {
            connections.map { conn ->
                async { WorkspaceTasks(conn, runCatching { api.listTasks(conn) }) }
            }.awaitAll()
        }

    suspend fun getTask(conn: WorkspaceConnection, slug: String): TaskSummary =
        api.getTask(conn, slug)

    suspend fun getJournal(conn: WorkspaceConnection, slug: String): List<JournalEntry> =
        api.getJournal(conn, slug)

    suspend fun getPlanAssumptions(conn: WorkspaceConnection, slug: String): PlanAssumptionsEnvelope =
        api.getPlanAssumptions(conn, slug)

    suspend fun approvePlanFlag(conn: WorkspaceConnection, slug: String, axis: String, reason: String?): PlanAssumptionsEnvelope =
        api.approvePlanFlag(conn, slug, axis, reason)
}
