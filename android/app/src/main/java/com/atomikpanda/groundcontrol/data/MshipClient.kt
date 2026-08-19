package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.AnswerBody
import com.atomikpanda.groundcontrol.data.dto.ApproveBody
import com.atomikpanda.groundcontrol.data.dto.CaptureBody
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import com.atomikpanda.groundcontrol.data.dto.WorkspacesResponse
import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.HealthResponse
import com.atomikpanda.groundcontrol.data.dto.HostHealth
import com.atomikpanda.groundcontrol.data.dto.HostInfo
import com.atomikpanda.groundcontrol.data.dto.HostTokenBody
import com.atomikpanda.groundcontrol.data.dto.HostTokenResponse
import com.atomikpanda.groundcontrol.data.dto.HostsResponse
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.NewMessageBody
import com.atomikpanda.groundcontrol.data.dto.SeenBody
import com.atomikpanda.groundcontrol.data.dto.NewSpecBody
import com.atomikpanda.groundcontrol.data.dto.NewThreadBody
import com.atomikpanda.groundcontrol.data.dto.PhaseBody
import com.atomikpanda.groundcontrol.data.dto.PlanAssumptionSummary
import com.atomikpanda.groundcontrol.data.dto.PlanAssumptionsEnvelope
import com.atomikpanda.groundcontrol.data.dto.PlanFlagApproveBody
import com.atomikpanda.groundcontrol.data.dto.ProseVerdictBody
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
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
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
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// coerceInputValues: an explicit `null` for a field that has a default (e.g. a
// server sending `"external_links": null` / `"task_slugs": null`) is coerced to
// that default instead of failing the whole decode — one bad field shouldn't
// break a cockpit's entire load.
fun buildJson(): Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

open class AuthException(message: String) : Exception(message)
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

/** Default production client (OkHttp engine), for callers with no host state
 *  to read. App code goes through `appHttpClient(context)`, which adds the
 *  host-scoped bearer refresh. Tests inject a MockEngine-backed client. */
fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) { mshipDefaults() }

// ---- the relay directory + the host-scoped refresh exchange (#471) ---------

/** The per-device fleet credential's header, mirroring the relay's
 *  `host_contract.FLEET_TOKEN_HEADER`. */
const val FLEET_TOKEN_HEADER = "Mship-Fleet-Token"

/** Every host route sits under this one prefix (AC13's single Caddy matcher). */
const val HOSTS_PATH = "/hosts"

/** The host's own bootstrap exchange — never proxied through the relay. */
const val HOST_TOKEN_PATH = "/host/token"
/** Discovery probes own their direct → public loop and suppress the client's
 * nested fallback so the base they return is the base that actually answered. */
private val EXPLICIT_HOST_BASE = AttributeKey<Unit>("ExplicitHostBase")
private val SUPPRESS_HOST_CONTACT = AttributeKey<Unit>("SuppressHostContact")
private data class WorkspaceRoute(
    val hostId: String,
    val workspaceId: String,
    val baseUrl: String,
)
private val WORKSPACE_ROUTE = AttributeKey<WorkspaceRoute>("WorkspaceRoute")
private val HOST_ROUTE_ID = AttributeKey<String>("HostRouteId")


/** `https://enroll.<relay domain>`: the one enroll server `mship relay enroll`,
 *  the daemon and the phone all address (`host_contract.enroll_base_url`). */
fun enrollBaseUrl(relayDomain: String): String {
    val domain = relayDomain.trim()
        .removePrefix("https://").removePrefix("http://")
        .trim('/')
        .removePrefix("enroll.")
    return "https://enroll.$domain"
}

/** The persisted refresh credential was itself refused: no retry can fix this,
 *  the operator has to re-pair the device. */
class RePairNeededException(val hostBase: String) :
    AuthException("re-pair needed: $hostBase refused the stored refresh credential")

/** Client + the token cache its interceptor mints through. */
class HostClient(val client: HttpClient, val tokens: HostTokens)

/**
 * Short-lived host bearers (AC9), keyed by host identity and candidate base.
 *
 * The exchange is serialized per route: two requests that discover the same
 * expired bearer at the same moment must produce ONE exchange, not two — the
 * loser reads the winner's token. Identity disambiguates contended hosts that
 * publish the same base; the base prevents a failed direct route from lending
 * its bearer to a different transport.
 */
class HostTokens(
    private val exchange: suspend (hostBase: String, credential: String) -> String,
) {
    private data class RouteKey(val hostId: String, val hostBase: String)
    private data class CachedBearer(val credential: String, val token: String)


    private val cache = ConcurrentHashMap<RouteKey, CachedBearer>()
    private val guard = Mutex()
    private val locks = mutableMapOf<RouteKey, Mutex>()

    fun cached(hostId: String, hostBase: String, credential: String?): String? =
        cache[RouteKey(hostId, hostBase)]
            ?.takeIf { it.credential == credential }
            ?.token

    /**
     * The current bearer for this host route, minting one from the refresh
     * credential captured in the same host snapshot that selected the route.
     * Null when that snapshot has no credential.
     */
    suspend fun bearer(
        hostId: String,
        hostBase: String,
        credential: String?,
        stale: String? = null,
    ): String? {
        val key = RouteKey(hostId, hostBase)
        val lock = guard.withLock { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val current = cache[key]
            // Someone else already refreshed this credential after our request.
            if (
                current != null &&
                current.credential == credential &&
                current.token != stale
            ) {
                return@withLock current.token
            }
            val capturedCredential = credential ?: return@withLock null
            val token = exchange(hostBase, capturedCredential)
            cache[key] = CachedBearer(capturedCredential, token)
            token
        }
    }
}


/** Which host base [url] belongs to, longest prefix first (a URL under two
 *  known bases belongs to the more specific one). The exchange route itself is
 *  excluded: it carries the credential, never a bearer, and its 401 is final. */
fun hostBaseFor(url: String, hosts: List<HostConnection>): String? {
    if (url.substringBefore('?').trimEnd('/').endsWith(HOST_TOKEN_PATH)) return null
    return hosts.flatMap { it.hostBases() }
        .filter { url.startsWith("$it/") }
        .maxByOrNull { it.length }
}

private suspend fun notifyHostContact(
    callback: suspend (hostId: String, hostBase: String) -> Unit,
    hostId: String,
    hostBase: String,
) {
    try {
        callback(hostId, hostBase)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Contact persistence is secondary evidence; it must not turn a
        // successful host response into a failed operator action.
    }
}

/**
 * A client that mints and refreshes host bearers by itself (#471 AC9).
 *
 * The interceptor is host-scoped: it attaches a bearer only to requests that
 * fall under a known host base, and on a 401 it exchanges the PERSISTED refresh
 * credential at that host's own `POST /host/token` — never at the relay — and
 * retries once. It never touches a request that already carries an
 * Authorization header, so manually paired connections keep their standing
 * token untouched.
 */

fun hostAwareClient(
    engine: HttpClientEngine,
    onHostContact: suspend (hostId: String, hostBase: String) -> Unit = { _, _ -> },
    hosts: suspend () -> List<HostConnection>,
): HostClient {
    var tokens: HostTokens? = null
    val refreshAuth = createClientPlugin("HostRefreshAuth") {
        on(Send) { request ->
            val cache = tokens
            val knownHosts = hosts()
            val originalUrl = request.url.buildString()
            val workspaceRoute = request.attributes.getOrNull(WORKSPACE_ROUTE)
            val routedHostId = workspaceRoute?.hostId
                ?: request.attributes.getOrNull(HOST_ROUTE_ID)
            val reportContact =
                request.attributes.getOrNull(SUPPRESS_HOST_CONTACT) == null
            val base = cache?.let { hostBaseFor(originalUrl, knownHosts) }
            if (cache == null) return@on proceed(request)
            val host = when {
                routedHostId != null ->
                    knownHosts.firstOrNull { it.hostId == routedHostId }
                        ?: base?.let { candidateBase ->
                            knownHosts.filter { candidateBase in it.hostBases() }.singleOrNull()
                        }
                base != null -> knownHosts.filter { base in it.hostBases() }.singleOrNull()
                else -> null
            } ?: return@on proceed(request)
            val originalBase = base ?: workspaceRoute?.baseUrl?.trimEnd('/')
                ?.takeIf { originalUrl.startsWith("$it/") }
                ?: return@on proceed(request)
            val explicitHostBase =
                request.attributes.getOrNull(EXPLICIT_HOST_BASE) != null
            val retryRequest = request.method == HttpMethod.Get ||
                request.method == HttpMethod.Head
            var lastTransportFailure: IOException? = null
            var routedHost = host
            var snapshotRefreshed = false

            while (true) {
                val preferredBase = base?.takeIf { it in routedHost.hostBases() }
                val candidateBases = when {
                    explicitHostBase -> listOfNotNull(preferredBase)
                    preferredBase != null ->
                        listOf(preferredBase) + routedHost.hostBases().filterNot { it == preferredBase }
                    else -> routedHost.hostBases()
                }
                val candidateRequests = candidateBases.map { candidateBase ->
                    val candidateUrl = if (base != null) {
                        candidateBase + originalUrl.removePrefix(originalBase)
                    } else {
                        workspaceBaseUrl(candidateBase, workspaceRoute!!.workspaceId) +
                            originalUrl.removePrefix(originalBase)
                    }
                    candidateBase to candidateUrl
                }
                var snapshotRestart: HostConnection? = null

                suspend fun routeBearer(candidateBase: String, stale: String? = null): String? =
                    try {
                        cache.bearer(
                            routedHost.hostId,
                            candidateBase,
                            routedHost.refresh,
                            stale,
                        )
                    } catch (error: RePairNeededException) {
                        if (snapshotRefreshed || explicitHostBase) throw error
                        val currentHost = hosts().firstOrNull {
                            it.hostId == routedHost.hostId
                        } ?: throw error
                        val routeChanged = currentHost.hostBases() != routedHost.hostBases()
                        if (
                            currentHost.refresh == null ||
                            (currentHost.refresh == routedHost.refresh && !routeChanged)
                        ) {
                            throw error
                        }
                        // Abandon every URL from the refused snapshot. A changed
                        // refresh credential may only be sent to a route from
                        // the same new snapshot.
                        snapshotRestart = currentHost
                        null
                    }

                for ((candidateBase, candidateUrl) in candidateRequests) {
                    val candidateRequest = HttpRequestBuilder().apply {
                        takeFrom(request)
                        url.takeFrom(candidateUrl)
                    }
                    if (candidateRequest.headers.contains(HttpHeaders.Authorization)) {
                        val call = try {
                            proceed(candidateRequest)
                        } catch (error: IOException) {
                            if (!retryRequest) throw error
                            lastTransportFailure = error
                            continue
                        }
                        if (call.response.status.isSuccess() && reportContact) {
                            notifyHostContact(onHostContact, routedHost.hostId, candidateBase)
                        }
                        return@on call
                    }
                    val token = try {
                        cache.cached(
                            routedHost.hostId,
                            candidateBase,
                            routedHost.refresh,
                        ) ?: routeBearer(candidateBase)
                    } catch (error: IOException) {
                        lastTransportFailure = error
                        continue
                    }
                    if (snapshotRestart != null) break
                    token?.let {
                        candidateRequest.headers[HttpHeaders.Authorization] = "Bearer $it"
                    }
                    // The response validator turns a 401 into an AuthException,
                    // and it may fire here or when the caller reads the body.
                    val call = try {
                        proceed(candidateRequest)
                            .takeIf { it.response.status != HttpStatusCode.Unauthorized }
                    } catch (_: AuthException) {
                        null
                    } catch (error: IOException) {
                        if (!retryRequest) throw error
                        lastTransportFailure = error
                        continue
                    }
                    if (call != null) {
                        if (call.response.status.isSuccess() && reportContact) {
                            notifyHostContact(onHostContact, routedHost.hostId, candidateBase)
                        }
                        return@on call
                    }
                    if (!retryRequest) {
                        throw AuthException("request unauthorized")
                    }
                    val refreshed = try {
                        routeBearer(candidateBase, stale = token)
                    } catch (error: IOException) {
                        lastTransportFailure = error
                        continue
                    }
                    if (snapshotRestart != null) break
                    if (refreshed == null) {
                        throw AuthException("no credential for $candidateBase")
                    }
                    candidateRequest.headers[HttpHeaders.Authorization] = "Bearer $refreshed"
                    val retried = try {
                        proceed(candidateRequest)
                    } catch (error: IOException) {
                        if (!retryRequest) throw error
                        lastTransportFailure = error
                        continue
                    }
                    if (retried.response.status.isSuccess() && reportContact) {
                        notifyHostContact(onHostContact, routedHost.hostId, candidateBase)
                    }
                    return@on retried
                }
                val currentHost = snapshotRestart
                if (currentHost != null) {
                    routedHost = currentHost
                    snapshotRefreshed = true
                    continue
                }
                throw lastTransportFailure ?: IOException("no reachable base for $originalBase")
            }
            error("host route loop exited")
        }
    }
    val client = HttpClient(engine) {
        mshipDefaults()
        install(refreshAuth)
    }
    tokens = HostTokens(
        exchange = { base, credential -> mintHostToken(client, base, credential) },
    )
    return HostClient(client, tokens)
}

/** The bootstrap exchange itself: no Authorization header, the credential in the
 *  body, and every failure is a uniform 401 — which can only mean re-pair. */
private suspend fun mintHostToken(client: HttpClient, hostBase: String, credential: String): String =
    try {
        client.post("${hostBase.trimEnd('/')}$HOST_TOKEN_PATH") {
            contentType(ContentType.Application.Json)
            setBody(HostTokenBody(credential))
        }.body<HostTokenResponse>().token
    } catch (_: AuthException) {
        throw RePairNeededException(hostBase)
    }

/** Thin wrapper over mship serve endpoints. One client; per-call base URL + bearer. */
class SpecApi(private val client: HttpClient) {

    suspend fun health(conn: WorkspaceConnection): HealthResponse =
        client.get("${conn.baseUrl}/health") { auth(conn) }.body()

    suspend fun listSpecs(conn: WorkspaceConnection): List<SpecSummary> =
        client.get("${conn.baseUrl}/specs") { auth(conn) }.body()

    suspend fun getSpec(conn: WorkspaceConnection, id: String): SpecRecord =
        client.get("${conn.baseUrl}/specs/$id") { auth(conn) }.body()

    suspend fun getEvidenceBlob(
        conn: WorkspaceConnection,
        specId: String,
        ref: String,
    ): ByteArray =
        client.get("${conn.baseUrl}/specs/$specId/evidence/$ref/blob") {
            auth(conn)
        }.body()

    suspend fun getReview(conn: WorkspaceConnection, id: String): SpecReview =
        client.get("${conn.baseUrl}/specs/$id/review") { auth(conn) }.body()

    suspend fun setVerdict(conn: WorkspaceConnection, id: String, criterionId: String, verdict: String, comment: String? = null): SpecReview =
        client.post("${conn.baseUrl}/specs/$id/verdict") { auth(conn); jsonBody(VerdictBody(criterionId, verdict, comment)) }.body()

    suspend fun setProseVerdict(conn: WorkspaceConnection, id: String, sectionId: String, verdict: String, comment: String? = null): SpecReview =
        client.post("${conn.baseUrl}/specs/$id/prose-verdict") { auth(conn); jsonBody(ProseVerdictBody(sectionId, verdict, comment)) }.body()

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

    suspend fun listPlanAssumptions(conn: WorkspaceConnection): List<PlanAssumptionSummary> =
        client.get("${conn.baseUrl}/plan-assumptions") { auth(conn) }.body()

    suspend fun getPlanAssumptions(conn: WorkspaceConnection, slug: String): PlanAssumptionsEnvelope =
        client.get("${conn.baseUrl}/plan-assumptions/$slug") { auth(conn) }.body()

    suspend fun approvePlanFlag(conn: WorkspaceConnection, slug: String, axis: String, reason: String?): PlanAssumptionsEnvelope =
        client.post("${conn.baseUrl}/plan-assumptions/$slug/approve") {
            auth(conn); jsonBody(PlanFlagApproveBody(axis, reason))
        }.body()

    /** The fleet (#471): GET enroll.<relay>/hosts with the per-device fleet token.
     *  The phone holds a relay ACCOUNT — this is the only route that turns it
     *  into addresses, and the only one that publishes refresh credentials. */
    suspend fun listHosts(relayDomain: String, fleetToken: String): List<HostInfo> =
        client.get("${enrollBaseUrl(relayDomain)}$HOSTS_PATH") {
            header(FLEET_TOKEN_HEADER, fleetToken)
        }.body<HostsResponse>().hosts

    /** A host's unauthenticated GET /health: ids and counts, the reachability
     * probe and the `host_id` half of an adoption's identity tuple. */
    suspend fun hostHealth(
        hostBase: String,
        recordContact: Boolean = true,
    ): HostHealth =
        client.get("${hostBase.trimEnd('/')}/health") {
            if (!recordContact) attributes.put(SUPPRESS_HOST_CONTACT, Unit)
        }.body()

    /** Host-level list (#472): GET {hostBase}/workspaces with the host token.
     *  Degraded entries are carried with their state, never dropped — the
     *  caller decides how to render them. */
    suspend fun listWorkspaces(
        hostBase: String,
        token: String?,
        allowHostFallback: Boolean = true,
        recordContact: Boolean = true,
        hostId: String? = null,
    ): List<WorkspaceInfo> =
        client.get("${hostBase.trimEnd('/')}/workspaces") {
            token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (!allowHostFallback) attributes.put(EXPLICIT_HOST_BASE, Unit)
            if (!recordContact) attributes.put(SUPPRESS_HOST_CONTACT, Unit)
            hostId?.let { attributes.put(HOST_ROUTE_ID, it) }
        }.body<WorkspacesResponse>().workspaces

    private fun HttpRequestBuilder.auth(conn: WorkspaceConnection) {
        conn.token?.takeIf { it.isNotBlank() }?.let {
            header(HttpHeaders.Authorization, "Bearer $it")
        }
        val hostId = conn.hostId
        val workspaceId = conn.workspaceId
        if (!hostId.isNullOrBlank() && !workspaceId.isNullOrBlank()) {
            attributes.put(
                WORKSPACE_ROUTE,
                WorkspaceRoute(hostId, workspaceId, conn.baseUrl),
            )
        }
    }

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json); setBody(body)
    }
}
