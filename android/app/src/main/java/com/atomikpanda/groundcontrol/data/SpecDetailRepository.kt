package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.SpecReview
import com.atomikpanda.groundcontrol.data.dto.TaskSummary

/** Detail-screen seam over SpecApi, scoped to one workspace connection + spec id. */
class SpecDetailRepository(private val api: SpecApi) {
    suspend fun load(conn: WorkspaceConnection, id: String): SpecRecord = api.getSpec(conn, id)
    suspend fun loadTask(conn: WorkspaceConnection, slug: String): TaskSummary = api.getTask(conn, slug)
    suspend fun setVerdict(conn: WorkspaceConnection, id: String, criterionId: String, verdict: String): SpecReview =
        api.setVerdict(conn, id, criterionId, verdict)
    suspend fun answer(conn: WorkspaceConnection, id: String, qid: String, answer: String): SpecReview =
        api.answerQuestion(conn, id, qid, answer)
    suspend fun ask(conn: WorkspaceConnection, id: String, text: String): SpecReview =
        api.addQuestion(conn, id, text)
    suspend fun approve(conn: WorkspaceConnection, id: String, bypassGate: Boolean): SpecReview =
        api.approve(conn, id, bypassGate)
    suspend fun requestChanges(conn: WorkspaceConnection, id: String, reason: String): SpecReview =
        api.requestChanges(conn, id, reason)
    suspend fun dispatch(conn: WorkspaceConnection, id: String): DispatchResult = api.dispatch(conn, id)
}
