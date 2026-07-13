package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.AnswerBody
import com.atomikpanda.groundcontrol.data.dto.ApproveBody
import com.atomikpanda.groundcontrol.data.dto.CaptureBody
import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.HealthResponse
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.NewMessageBody
import com.atomikpanda.groundcontrol.data.dto.SeenBody
import com.atomikpanda.groundcontrol.data.dto.NewSpecBody
import com.atomikpanda.groundcontrol.data.dto.NewThreadBody
import com.atomikpanda.groundcontrol.data.dto.PhaseBody
import com.atomikpanda.groundcontrol.data.dto.QuestionBody
import com.atomikpanda.groundcontrol.data.dto.ReasonBody
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.SpecReview
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadsWaitResponse
import com.atomikpanda.groundcontrol.data.dto.UnattendedBody
import com.atomikpanda.groundcontrol.data.dto.VerdictBody
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// coerceInputValues: an explicit `null` for a field that has a default (e.g. a
// server sending `"external_links": null` / `"task_slugs": null`) is coerced to
// that default instead of failing the whole decode — one bad field shouldn't
// break a cockpit's entire load.
fun buildJson(): Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

class AuthException(message: String) : Exception(message)
class NotFoundException(message: String) : Exception(message)
/** 409 — carries the server's verbatim `detail` (approval blockers or invalid transition). */
class ApiConflictException(val detail: String) : Exception(detail)

/** Pull FastAPI's `{"detail": "..."}` out of an error body, falling back to the raw text. */
private fun errorDetail(body: String): String =
    runCatching { buildJson().parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content }
        .getOrNull() ?: body

/** Shared client config: JSON negotiation + typed error mapping. Used by prod and tests. */
fun HttpClientConfig<*>.mshipDefaults() {
    install(ContentNegotiation) { json(buildJson()) }
    // Enables the per-request `timeout { }` block used by long-poll calls.
    // OkHttp's ~10s default read timeout would otherwise abort a 25s wait.
    install(HttpTimeout)
    HttpResponseValidator {
        validateResponse { resp: HttpResponse ->
            if (resp.status.isSuccess()) return@validateResponse
            val detail = runCatching { errorDetail(resp.bodyAsText()) }.getOrDefault("HTTP ${resp.status.value}")
            when (resp.status) {
                HttpStatusCode.Unauthorized -> throw AuthException(detail)
                HttpStatusCode.NotFound -> throw NotFoundException(detail)
                HttpStatusCode.Conflict -> throw ApiConflictException(detail)
                else -> throw Exception("HTTP ${resp.status.value}: $detail")
            }
        }
    }
}

/** Default production client (OkHttp engine). Tests inject a MockEngine-backed client. */
fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) { mshipDefaults() }

/** Thin wrapper over mship serve endpoints. One client; per-call base URL + bearer. */
class SpecApi(private val client: HttpClient) {

    suspend fun health(conn: WorkspaceConnection): HealthResponse =
        client.get("${conn.baseUrl}/health") { auth(conn) }.body()

    suspend fun listSpecs(conn: WorkspaceConnection): List<SpecSummary> =
        client.get("${conn.baseUrl}/specs") { auth(conn) }.body()

    suspend fun getSpec(conn: WorkspaceConnection, id: String): SpecRecord =
        client.get("${conn.baseUrl}/specs/$id") { auth(conn) }.body()

    suspend fun getReview(conn: WorkspaceConnection, id: String): SpecReview =
        client.get("${conn.baseUrl}/specs/$id/review") { auth(conn) }.body()

    suspend fun setVerdict(conn: WorkspaceConnection, id: String, criterionId: String, verdict: String): SpecReview =
        client.post("${conn.baseUrl}/specs/$id/verdict") { auth(conn); jsonBody(VerdictBody(criterionId, verdict)) }.body()

    suspend fun answerQuestion(conn: WorkspaceConnection, id: String, qid: String, answer: String): SpecReview =
        client.post("${conn.baseUrl}/specs/$id/questions/$qid/answer") { auth(conn); jsonBody(AnswerBody(answer)) }.body()

    suspend fun addQuestion(conn: WorkspaceConnection, id: String, text: String): SpecReview =
        client.post("${conn.baseUrl}/specs/$id/questions") { auth(conn); jsonBody(QuestionBody(text)) }.body()

    suspend fun approve(conn: WorkspaceConnection, id: String, bypassGate: Boolean): SpecReview =
        client.post("${conn.baseUrl}/specs/$id/approve") { auth(conn); jsonBody(ApproveBody(bypassGate)) }.body()

    suspend fun requestChanges(conn: WorkspaceConnection, id: String, reason: String): SpecReview =
        client.post("${conn.baseUrl}/specs/$id/request-changes") { auth(conn); jsonBody(ReasonBody(reason)) }.body()

    suspend fun createSpec(conn: WorkspaceConnection, title: String, affectedRepos: List<String>): SpecRecord =
        client.post("${conn.baseUrl}/specs") { auth(conn); jsonBody(NewSpecBody(title = title, affectedRepos = affectedRepos)) }.body()

    suspend fun dispatch(conn: WorkspaceConnection, id: String): DispatchResult =
        client.post("${conn.baseUrl}/specs/$id/dispatch") { auth(conn) }.body()

    /** Archive a spec (swipe-to-archive in the inbox). The response is just `{id, status}` —
     *  like [setUnattended], callers apply the optimistic removal themselves. */
    suspend fun archiveSpec(conn: WorkspaceConnection, id: String) {
        client.post("${conn.baseUrl}/specs/$id/archive") { auth(conn) }
    }

    suspend fun listTasks(conn: WorkspaceConnection): List<TaskSummary> =
        client.get("${conn.baseUrl}/tasks") { auth(conn) }.body()

    suspend fun listItems(conn: WorkspaceConnection): List<WorkItemSummary> =
        client.get("${conn.baseUrl}/items") { auth(conn) }.body()

    suspend fun getItem(conn: WorkspaceConnection, id: String): WorkItemSummary =
        client.get("${conn.baseUrl}/items/$id") { auth(conn) }.body()

    /** Toggle a work item's eligibility for unattended (cloud-runner) execution.
     *  The server response is just `{id, unattended}` (not a full WorkItemSummary), so — like
     *  [markThreadSeen] — this ignores the body; callers apply the optimistic update themselves. */
    suspend fun setUnattended(conn: WorkspaceConnection, id: String, on: Boolean) {
        client.post("${conn.baseUrl}/items/$id/unattended") { auth(conn); jsonBody(UnattendedBody(on)) }
    }

    /** Set or clear a work item's phase override ("Mark done" → `phase = "done"`, "Reopen" →
     *  `phase = null`). The response is just `{id, phase_override}` (not a full
     *  WorkItemSummary) — like [setUnattended], callers apply the optimistic update themselves. */
    suspend fun setItemPhase(conn: WorkspaceConnection, id: String, phase: String?) {
        client.post("${conn.baseUrl}/items/$id/phase") { auth(conn); jsonBody(PhaseBody(phase)) }
    }

    /** Steer a work item: appends a human message to its thread, lazily creating+linking one
     *  server-side when the item has none. Item-scoped (not thread-scoped) so it can never
     *  no-op on an in-flight item that hasn't got a thread yet. Returns the (new-or-existing) thread. */
    suspend fun postItemMessage(conn: WorkspaceConnection, id: String, text: String): Thread =
        client.post("${conn.baseUrl}/items/$id/messages") { auth(conn); jsonBody(NewMessageBody(text)) }.body()

    suspend fun getTask(conn: WorkspaceConnection, slug: String): TaskSummary =
        client.get("${conn.baseUrl}/tasks/$slug") { auth(conn) }.body()

    suspend fun getJournal(conn: WorkspaceConnection, slug: String): List<JournalEntry> =
        client.get("${conn.baseUrl}/journal/$slug") { auth(conn) }.body()

    suspend fun listThreads(conn: WorkspaceConnection): List<ThreadSummary> =
        client.get("${conn.baseUrl}/threads") { auth(conn) }.body()

    suspend fun listThreadsWait(conn: WorkspaceConnection, since: String, timeoutSeconds: Int): ThreadsWaitResponse =
        client.get("${conn.baseUrl}/threads") {
            auth(conn)
            parameter("wait", "1")
            parameter("since", since)
            parameter("timeout", timeoutSeconds)
            timeout {
                // Exceed the server wait so Ktor/OkHttp don't abort mid-poll.
                requestTimeoutMillis = (timeoutSeconds + 10) * 1000L
                socketTimeoutMillis = (timeoutSeconds + 10) * 1000L
            }
        }.body()

    suspend fun getThread(conn: WorkspaceConnection, id: String): Thread =
        client.get("${conn.baseUrl}/threads/$id") { auth(conn) }.body()

    suspend fun createThread(conn: WorkspaceConnection, text: String, subject: String?): Thread =
        client.post("${conn.baseUrl}/threads") { auth(conn); jsonBody(NewThreadBody(text, subject)) }.body()

    suspend fun captureBrainstorm(conn: WorkspaceConnection, idea: String, title: String? = null, idempotencyKey: String? = null): Thread =
        client.post("${conn.baseUrl}/capture") { auth(conn); jsonBody(CaptureBody(idea, title, idempotencyKey)) }.body()

    suspend fun postMessage(conn: WorkspaceConnection, id: String, text: String): Thread =
        client.post("${conn.baseUrl}/threads/$id/messages") { auth(conn); jsonBody(NewMessageBody(text)) }.body()

    suspend fun markThreadSeen(conn: WorkspaceConnection, id: String, seenAt: String?) {
        client.post("${conn.baseUrl}/threads/$id/seen") { auth(conn); jsonBody(SeenBody(seenAt)) }
    }

    private fun HttpRequestBuilder.auth(conn: WorkspaceConnection) {
        conn.token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json); setBody(body)
    }
}
