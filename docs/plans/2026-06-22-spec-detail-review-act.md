# Ground Control Spec Detail + Review/Act Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `ground-control-c4c6` (approved) — `specs/2026-06-22-ground-control-c4c6.md` in the workspace.

**Goal:** Add a Spec Detail screen to the Ground Control Android app so a user can open a spec from the inbox, read the complete spec, and act on it (per-criterion verdicts, answer/ask questions, approve gated + bypass, request-changes, dispatch) — all against the existing `mship serve` HTTP API.

**Architecture:** MVVM + StateFlow, matching the existing `ui/specs` inbox. A single `GET /specs/{id}` loads the full record (the **complete body markdown is rendered verbatim**, not the lossy `/review` context); every write endpoint returns a fresh review payload that the ViewModel treats as the source of truth (non-optimistic, so phone+terminal converge). The action bar and interactivity are driven by a pure `availableActions(status)` function. Typed exceptions map HTTP 401/404/409 so the UI can react (auth, gone, approval-blockers, stale-state).

**Tech Stack:** Kotlin, Jetpack Compose (Material3 1.2.1), Navigation-Compose 2.7.7, Ktor client 2.3.12 + kotlinx.serialization, JUnit4 + Ktor MockEngine. One new dependency: a Compose-native markdown renderer. JVM unit tests only (no emulator) — Compose/markdown code is build-verified.

**Conventions (match existing code):**
- Run all gradle/test from the `android/` dir; source the toolchain first. `mship test` does this for you.
- Tests live in `android/app/src/test/java/com/atomikpanda/groundcontrol/`.
- Commit from the worktree; pair each commit with `mship journal`.
- Single test class run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.<Class>"` (after `source ~/toolchains/android-env.sh`).

---

<!-- mship:task id=1 -->
### Task 1: Detail/review DTOs

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/dto/SpecDetailDtos.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecDetailDtosTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.SpecReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecDetailDtosTest {
    private val json = buildJson()

    @Test fun parses_full_spec_record_including_body_and_nested() {
        val raw = """
        {"id":"s1","title":"T","status":"needs_review","created_at":"x","updated_at":"y",
         "affected_repos":["r"],
         "acceptance_criteria":[{"id":"ac1","text":"AC one","verdict":"approved"},
                                {"id":"ac2","text":"AC two","verdict":"unreviewed"}],
         "open_questions":[{"id":"q1","text":"Q one?","answer":"yes"},
                           {"id":"q2","text":"Q two?","answer":null}],
         "non_goals":["ng"],"risks":["rk"],"task_slug":null,
         "body":"## Problem\n\nhi\n\n## Architecture\n\ncustom section"}
        """.trimIndent()
        val rec = json.decodeFromString(SpecRecord.serializer(), raw)
        assertEquals("s1", rec.id)
        assertEquals("needs_review", rec.status)
        assertTrue(rec.body.contains("## Architecture"))
        assertEquals(2, rec.acceptanceCriteria.size)
        assertEquals("approved", rec.acceptanceCriteria[0].verdict)
        assertNull(rec.openQuestions[1].answer)
        assertEquals(listOf("ng"), rec.nonGoals)
        assertNull(rec.taskSlug)
    }

    @Test fun parses_review_payload_and_ignores_unknown_context() {
        val raw = """
        {"id":"s1","status":"approved",
         "acceptance_criteria":[{"id":"ac1","text":"AC","verdict":"approved"}],
         "open_questions":[],
         "context":{"problem":"p","user_story":"u","approach":"a","non_goals":[],"risks":[],"affected_repos":[]},
         "summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}
        """.trimIndent()
        val rev = json.decodeFromString(SpecReview.serializer(), raw)
        assertEquals("approved", rev.status)
        assertEquals(1, rev.summary.criteriaTotal)
        assertEquals(1, rev.acceptanceCriteria.size)
    }

    @Test fun parses_dispatch_result() {
        val raw = """
        {"spec":{"id":"s1","title":"T","status":"dispatched"},
         "task_slug":"s1","spawned":true,"handoff":"do the thing"}
        """.trimIndent()
        val dr = json.decodeFromString(DispatchResult.serializer(), raw)
        assertEquals("s1", dr.taskSlug)
        assertTrue(dr.spawned)
        assertEquals("dispatched", dr.spec.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecDetailDtosTest"`
Expected: FAIL — `SpecRecord` / `SpecReview` / `DispatchResult` unresolved.

- [ ] **Step 3: Write the DTOs**

Create `data/dto/SpecDetailDtos.kt`:

```kotlin
package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReviewCriterion(
    val id: String,
    val text: String,
    val verdict: String,            // "unreviewed" | "approved" | "flagged"
)

@Serializable
data class ReviewQuestion(
    val id: String,
    val text: String,
    val answer: String? = null,
)

@Serializable
data class SpecRecord(
    val id: String,
    val title: String,
    val status: String,
    @SerialName("affected_repos") val affectedRepos: List<String> = emptyList(),
    @SerialName("acceptance_criteria") val acceptanceCriteria: List<ReviewCriterion> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<ReviewQuestion> = emptyList(),
    @SerialName("non_goals") val nonGoals: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    @SerialName("task_slug") val taskSlug: String? = null,
    val body: String = "",
)

@Serializable
data class ReviewSummary(
    @SerialName("criteria_total") val criteriaTotal: Int = 0,
    val approved: Int = 0,
    val flagged: Int = 0,
    val unreviewed: Int = 0,
    @SerialName("open_questions_unanswered") val openQuestionsUnanswered: Int = 0,
)

/** Returned by every write endpoint; we patch state from it. `context` is intentionally
 *  unmodeled (display uses SpecRecord.body) and dropped by ignoreUnknownKeys. */
@Serializable
data class SpecReview(
    val id: String,
    val status: String,
    @SerialName("acceptance_criteria") val acceptanceCriteria: List<ReviewCriterion> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<ReviewQuestion> = emptyList(),
    val summary: ReviewSummary = ReviewSummary(),
)

@Serializable
data class DispatchResult(
    val spec: SpecRecord,
    @SerialName("task_slug") val taskSlug: String,
    val spawned: Boolean = false,
    val handoff: String = "",
)

@Serializable data class VerdictBody(@SerialName("criterion_id") val criterionId: String, val verdict: String)
@Serializable data class AnswerBody(val answer: String)
@Serializable data class QuestionBody(val text: String)
@Serializable data class ApproveBody(@SerialName("bypass_gate") val bypassGate: Boolean = false)
@Serializable data class ReasonBody(val reason: String)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecDetailDtosTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/data/dto/SpecDetailDtos.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/SpecDetailDtosTest.kt
git commit -m "feat(gc): spec detail/review/dispatch DTOs"
mship journal "added SpecRecord/SpecReview/DispatchResult + request DTOs; parsing tests green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=2 -->
### Task 2: Pure logic — actions, summary, blocker parser

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/SpecActions.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecActionsTest.kt`

- [ ] **Step 1: Write the failing test** (exhaustive over all 8 statuses — satisfies ac9)

```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecAction
import com.atomikpanda.groundcontrol.data.availableActions
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion
import com.atomikpanda.groundcontrol.data.isReviewInteractive
import com.atomikpanda.groundcontrol.data.parseApproveBlockers
import com.atomikpanda.groundcontrol.data.statusBanner
import com.atomikpanda.groundcontrol.data.summaryOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecActionsTest {
    private val allStatuses = listOf(
        "captured", "drafting", "needs_review", "needs_clarification",
        "approved", "dispatched", "implemented", "archived",
    )

    @Test fun available_actions_is_exhaustive_over_all_8_statuses() {
        assertEquals(
            setOf(SpecAction.REQUEST_CHANGES, SpecAction.APPROVE, SpecAction.APPROVE_ANYWAY),
            availableActions("needs_review"),
        )
        assertEquals(setOf(SpecAction.REQUEST_CHANGES, SpecAction.DISPATCH), availableActions("approved"))
        // the remaining 6 are read-only (no actions)
        listOf("captured", "drafting", "needs_clarification", "dispatched", "implemented", "archived")
            .forEach { assertTrue("$it should have no actions", availableActions(it).isEmpty()) }
        // every known status is covered above
        assertEquals(8, allStatuses.size)
    }

    @Test fun review_is_interactive_only_in_needs_review() {
        assertTrue(isReviewInteractive("needs_review"))
        listOf("approved", "dispatched", "needs_clarification", "drafting", "captured", "implemented", "archived")
            .forEach { assertEquals("$it not interactive", false, isReviewInteractive(it)) }
    }

    @Test fun status_banner_null_when_actionable_else_describes_status() {
        assertNull(statusBanner("needs_review", null))
        assertNull(statusBanner("approved", null))
        assertEquals("dispatched · task t1", statusBanner("dispatched", "t1"))
        assertEquals("dispatched", statusBanner("dispatched", null))
        assertTrue(statusBanner("needs_clarification", null)!!.contains("re-draft"))
    }

    @Test fun summary_counts_verdicts_and_unanswered_questions() {
        val crit = listOf(
            ReviewCriterion("ac1", "", "approved"),
            ReviewCriterion("ac2", "", "approved"),
            ReviewCriterion("ac3", "", "flagged"),
            ReviewCriterion("ac4", "", "unreviewed"),
        )
        val qs = listOf(ReviewQuestion("q1", "", "a"), ReviewQuestion("q2", "", null))
        val s = summaryOf(crit, qs)
        assertEquals(4, s.criteriaTotal)
        assertEquals(2, s.approved)
        assertEquals(1, s.flagged)
        assertEquals(1, s.unreviewed)
        assertEquals(1, s.unansweredQuestions)
    }

    @Test fun parse_blockers_splits_fastapi_detail() {
        val detail = "cannot approve: acceptance criteria not approved: ac2; open questions unanswered: q1"
        assertEquals(
            listOf("acceptance criteria not approved: ac2", "open questions unanswered: q1"),
            parseApproveBlockers(detail),
        )
        // tolerates detail without the prefix
        assertEquals(listOf("something"), parseApproveBlockers("something"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecActionsTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the pure logic**

Create `data/SpecActions.kt`:

```kotlin
package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion

/** Actions Ground Control offers for a spec, gated by status. */
enum class SpecAction { APPROVE, APPROVE_ANYWAY, REQUEST_CHANGES, DISPATCH }

/** Criteria/questions are editable only while a spec is in review. */
fun isReviewInteractive(status: String): Boolean = status == "needs_review"

/** Status-aware action set. Covers all 8 mship spec statuses; unknown → none. */
fun availableActions(status: String): Set<SpecAction> = when (status) {
    "needs_review" -> setOf(SpecAction.REQUEST_CHANGES, SpecAction.APPROVE, SpecAction.APPROVE_ANYWAY)
    "approved" -> setOf(SpecAction.REQUEST_CHANGES, SpecAction.DISPATCH)
    else -> emptySet()   // captured, drafting, needs_clarification, dispatched, implemented, archived
}

/** Read-only banner for non-actionable statuses; null when the action bar is shown. */
fun statusBanner(status: String, taskSlug: String?): String? = when (status) {
    "needs_review", "approved" -> null
    "dispatched" -> "dispatched" + (taskSlug?.let { " · task $it" } ?: "")
    "needs_clarification" -> "needs clarification — re-draft to reopen review"
    "drafting" -> "drafting"
    "captured" -> "captured"
    "implemented" -> "implemented"
    "archived" -> "archived"
    else -> status
}

data class Summary(
    val criteriaTotal: Int,
    val approved: Int,
    val flagged: Int,
    val unreviewed: Int,
    val unansweredQuestions: Int,
)

fun summaryOf(criteria: List<ReviewCriterion>, questions: List<ReviewQuestion>): Summary = Summary(
    criteriaTotal = criteria.size,
    approved = criteria.count { it.verdict == "approved" },
    flagged = criteria.count { it.verdict == "flagged" },
    unreviewed = criteria.count { it.verdict == "unreviewed" },
    unansweredQuestions = questions.count { it.answer == null },
)

/** Split FastAPI's `cannot approve: a; b; c` 409 detail into individual blockers. */
fun parseApproveBlockers(detail: String): List<String> {
    val tail = detail.substringAfter("cannot approve:", detail)
    return tail.split(";").map { it.trim() }.filter { it.isNotEmpty() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecActionsTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/data/SpecActions.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/SpecActionsTest.kt
git commit -m "feat(gc): status-aware actions, summary, approve-blocker parser"
mship journal "added availableActions/summaryOf/parseApproveBlockers (exhaustive 8-status test)" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=3 -->
### Task 3: API errors + SpecApi read/write methods

**Files:**
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/MshipClient.kt`
- Modify: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecApiTest.kt`

- [ ] **Step 1: Write the failing tests** — append to `SpecApiTest.kt` (keep existing tests). Replace the `client(handler)` helper so the test client installs the *same* defaults (validator) as production, then add the new cases:

Replace the existing helper:
```kotlin
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(buildJson()) } }
```
with:
```kotlin
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { mshipDefaults() }
```
Add the import `import com.atomikpanda.groundcontrol.data.mshipDefaults` and these tests:
```kotlin
    private val reviewJson = """
        {"id":"s1","status":"approved","acceptance_criteria":[{"id":"ac1","text":"t","verdict":"approved"}],
         "open_questions":[],"summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}
    """.trimIndent()

    @Test fun get_spec_hits_id_path() = runTest {
        var url: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString()
            respond("""{"id":"s1","title":"T","status":"needs_review","body":"b"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val rec = api.getSpec(conn, "s1")
        assertEquals("s1", rec.id)
        assertTrue(url!!.endsWith("/specs/s1"))
    }

    @Test fun post_verdict_sends_body_and_returns_review() = runTest {
        var body: String? = null
        var method: String? = null
        val api = SpecApi(client { req ->
            method = req.method.value
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(reviewJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val rev = api.setVerdict(conn, "s1", "ac1", "approved")
        assertEquals("POST", method)
        assertTrue(body!!.contains("\"criterion_id\":\"ac1\""))
        assertTrue(body!!.contains("\"verdict\":\"approved\""))
        assertEquals(1, rev.summary.approved)
    }

    @Test fun approve_sends_bypass_flag() = runTest {
        var body: String? = null
        val api = SpecApi(client { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(reviewJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        api.approve(conn, "s1", bypassGate = true)
        assertTrue(body!!.contains("\"bypass_gate\":true"))
    }

    @Test fun dispatch_returns_result() = runTest {
        val api = SpecApi(client {
            respond("""{"spec":{"id":"s1","title":"T","status":"dispatched"},"task_slug":"s1","spawned":true,"handoff":"go"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val dr = api.dispatch(conn, "s1")
        assertEquals("s1", dr.taskSlug)
        assertTrue(dr.spawned)
    }

    @Test(expected = com.atomikpanda.groundcontrol.data.NotFoundException::class)
    fun get_spec_maps_404() = runTest {
        val api = SpecApi(client { respond("""{"detail":"no spec"}""", HttpStatusCode.NotFound,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        api.getSpec(conn, "missing")
    }

    @Test fun approve_409_maps_to_conflict_with_detail() = runTest {
        val api = SpecApi(client { respond("""{"detail":"cannot approve: ac2; q1"}""", HttpStatusCode.Conflict,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            api.approve(conn, "s1", bypassGate = false)
            throw AssertionError("expected ApiConflictException")
        } catch (e: com.atomikpanda.groundcontrol.data.ApiConflictException) {
            assertTrue(e.detail.contains("cannot approve"))
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecApiTest"`
Expected: FAIL — `mshipDefaults`, `NotFoundException`, `ApiConflictException`, `getSpec`, `setVerdict`, `approve`, `dispatch` unresolved.

- [ ] **Step 3: Rewrite `MshipClient.kt`**

Replace the whole file with:

```kotlin
package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.AnswerBody
import com.atomikpanda.groundcontrol.data.dto.ApproveBody
import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.HealthResponse
import com.atomikpanda.groundcontrol.data.dto.QuestionBody
import com.atomikpanda.groundcontrol.data.dto.ReasonBody
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.SpecReview
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.VerdictBody
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
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

fun buildJson(): Json = Json { ignoreUnknownKeys = true }

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

    suspend fun dispatch(conn: WorkspaceConnection, id: String): DispatchResult =
        client.post("${conn.baseUrl}/specs/$id/dispatch") { auth(conn) }.body()

    private fun HttpRequestBuilder.auth(conn: WorkspaceConnection) {
        conn.token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json); setBody(body)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecApiTest"`
Expected: PASS (existing + new). The existing `list_specs_throws_on_401` still passes (now an `AuthException`, a subclass of `Exception`).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/data/MshipClient.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/SpecApiTest.kt
git commit -m "feat(gc): SpecApi detail/review/act calls + typed 401/404/409 mapping"
mship journal "extended SpecApi with 8 calls + shared mshipDefaults validator; error-mapping tests green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=4 -->
### Task 4: SpecDetailRepository

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/SpecDetailRepository.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecDetailRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpecDetailRepositoryTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun load_returns_full_record() = runTest {
        val repo = SpecDetailRepository(SpecApi(HttpClient(MockEngine {
            respond("""{"id":"s1","title":"T","status":"needs_review","body":"## Problem\n\nx"}""",
                HttpStatusCode.OK, jsonHdr)
        }) { mshipDefaults() }))
        val rec = repo.load(conn, "s1")
        assertEquals("s1", rec.id)
        assertEquals("## Problem\n\nx", rec.body)
    }

    @Test fun set_verdict_returns_review() = runTest {
        val repo = SpecDetailRepository(SpecApi(HttpClient(MockEngine {
            respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"t","verdict":"approved"}],
                       "open_questions":[],"summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }) { mshipDefaults() }))
        val rev = repo.setVerdict(conn, "s1", "ac1", "approved")
        assertEquals(1, rev.summary.approved)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecDetailRepositoryTest"`
Expected: FAIL — `SpecDetailRepository` unresolved.

- [ ] **Step 3: Write the repository**

Create `data/SpecDetailRepository.kt`:

```kotlin
package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.SpecReview

/** Detail-screen seam over SpecApi, scoped to one workspace connection + spec id. */
class SpecDetailRepository(private val api: SpecApi) {
    suspend fun load(conn: WorkspaceConnection, id: String): SpecRecord = api.getSpec(conn, id)
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecDetailRepositoryTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/data/SpecDetailRepository.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/SpecDetailRepositoryTest.kt
git commit -m "feat(gc): SpecDetailRepository (per-connection detail/act seam)"
mship journal "added SpecDetailRepository wrapping the 8 SpecApi calls" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=5 -->
### Task 5: SpecDetailViewModel state machine

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/SpecDetailViewModel.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecDetailViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailUiState
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpecDetailViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        handler: io.ktor.client.engine.mock.MockRequestHandler,
    ): SpecDetailViewModel {
        val repo = SpecDetailRepository(SpecApi(HttpClient(MockEngine(handler)) { mshipDefaults() }))
        return SpecDetailViewModel(repo, conn, "s1", testScope = scope)
    }

    @Test fun load_success_builds_content_with_full_body() = runTest {
        val vm = vm(this) {
            respond("""{"id":"s1","title":"T","status":"needs_review","body":"## Problem\n\nhi\n## Architecture\n\nz",
                        "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],
                        "open_questions":[{"id":"q1","text":"q","answer":null}],
                        "non_goals":["ng"],"risks":["rk"]}""", HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("T", c.detail.title)
        assertTrue(c.detail.bodyMarkdown.contains("## Architecture"))
        assertEquals(1, c.detail.summary.unansweredQuestions)
    }

    @Test fun load_404_maps_to_not_found() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.NotFound) }
        vm.load()?.join()
        assertEquals(ErrorKind.NOT_FOUND, (vm.state.value as SpecDetailUiState.Error).kind)
    }

    @Test fun load_401_maps_to_auth() = runTest {
        val vm = vm(this) { respondError(HttpStatusCode.Unauthorized) }
        vm.load()?.join()
        assertEquals(ErrorKind.AUTH, (vm.state.value as SpecDetailUiState.Error).kind)
    }

    @Test fun set_verdict_patches_state_from_returned_review() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[]}""",
                HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"needs_review",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[],
                "summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.setVerdict("ac1", "approved")?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("approved", c.detail.criteria.first().verdict)
        assertEquals(1, c.detail.summary.approved)
    }

    @Test fun approve_gated_409_surfaces_blockers() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"unreviewed"}],"open_questions":[]}""",
                HttpStatusCode.OK, jsonHdr)
            else respond("""{"detail":"cannot approve: ac1 not approved; q1 unanswered"}""",
                HttpStatusCode.Conflict, jsonHdr)
        }
        vm.load()?.join()
        vm.approve(bypass = false)?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("needs_review", c.detail.status)         // unchanged
        assertNotNull(c.blockers)
        assertEquals(2, c.blockers!!.size)
    }

    @Test fun dispatch_success_sets_result_and_status() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"approved","body":"b"}""", HttpStatusCode.OK, jsonHdr)
            else respond("""{"spec":{"id":"s1","title":"T","status":"dispatched"},"task_slug":"s1","spawned":true,"handoff":"go"}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.dispatch()?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("dispatched", c.detail.status)
        assertEquals("s1", c.dispatchResult!!.taskSlug)
    }

    @Test fun stale_409_on_dispatch_sets_banner_and_refetches() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            when (call) {
                1 -> respond("""{"id":"s1","title":"T","status":"approved","body":"b"}""", HttpStatusCode.OK, jsonHdr)
                2 -> respond("""{"detail":"invalid transition approved -> dispatched"}""", HttpStatusCode.Conflict, jsonHdr)
                else -> respond("""{"id":"s1","title":"T","status":"dispatched","body":"b"}""", HttpStatusCode.OK, jsonHdr)
            }
        }
        vm.load()?.join()
        vm.dispatch()?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertNotNull(c.banner)
        assertEquals("dispatched", c.detail.status)            // refetched truth
    }

    @Test fun answer_patches_question() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[],"open_questions":[{"id":"q1","text":"q","answer":null}]}""",
                HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[],
                "open_questions":[{"id":"q1","text":"q","answer":"yes"}],
                "summary":{"criteria_total":0,"approved":0,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.answer("q1", "yes")?.join()
        val c = vm.state.value as SpecDetailUiState.Content
        assertEquals("yes", c.detail.questions.first().answer)
        assertEquals(0, c.detail.summary.unansweredQuestions)
    }

    @Test fun approve_success_transitions_to_approved() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[]}""",
                HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"approved",
                "acceptance_criteria":[{"id":"ac1","text":"a","verdict":"approved"}],"open_questions":[],
                "summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.approve(bypass = false)?.join()
        assertEquals("approved", (vm.state.value as SpecDetailUiState.Content).detail.status)
    }

    @Test fun request_changes_transitions_to_needs_clarification() = runTest {
        var call = 0
        val vm = vm(this) {
            call++
            if (call == 1) respond("""{"id":"s1","title":"T","status":"needs_review","body":"b",
                "acceptance_criteria":[],"open_questions":[]}""", HttpStatusCode.OK, jsonHdr)
            else respond("""{"id":"s1","status":"needs_clarification","acceptance_criteria":[],"open_questions":[],
                "summary":{"criteria_total":0,"approved":0,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }
        vm.load()?.join()
        vm.requestChanges("needs work")?.join()
        assertEquals("needs_clarification", (vm.state.value as SpecDetailUiState.Content).detail.status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecDetailViewModelTest"`
Expected: FAIL — `SpecDetailViewModel`, `SpecDetailUiState`, `ErrorKind` unresolved.

- [ ] **Step 3: Write the ViewModel**

Create `ui/specdetail/SpecDetailViewModel.kt`:

```kotlin
package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.Summary
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.SpecReview
import com.atomikpanda.groundcontrol.data.parseApproveBlockers
import com.atomikpanda.groundcontrol.data.summaryOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ErrorKind { NETWORK, AUTH, NOT_FOUND }

/** Which action is in flight, so the UI can show a targeted spinner. */
sealed interface ActionRef {
    data class Verdict(val criterionId: String) : ActionRef
    data class Answer(val questionId: String) : ActionRef
    data object Ask : ActionRef
    data object Approve : ActionRef
    data object RequestChanges : ActionRef
    data object Dispatch : ActionRef
}

data class SpecDetail(
    val id: String,
    val title: String,
    val status: String,
    val bodyMarkdown: String,
    val nonGoals: List<String>,
    val risks: List<String>,
    val affectedRepos: List<String>,
    val taskSlug: String?,
    val criteria: List<ReviewCriterion>,
    val questions: List<ReviewQuestion>,
) {
    val summary: Summary get() = summaryOf(criteria, questions)
}

data class DispatchInfo(val taskSlug: String, val spawned: Boolean, val handoff: String)

sealed interface SpecDetailUiState {
    data object Loading : SpecDetailUiState
    data class Error(val kind: ErrorKind, val message: String) : SpecDetailUiState
    data class Content(
        val detail: SpecDetail,
        val inFlight: ActionRef? = null,
        val banner: String? = null,            // transient note (e.g. "spec changed")
        val blockers: List<String>? = null,    // approve-gate 409 → blocker sheet
        val dispatchResult: DispatchInfo? = null,
    ) : SpecDetailUiState
}

private fun SpecRecord.toDetail() = SpecDetail(
    id = id, title = title, status = status, bodyMarkdown = body,
    nonGoals = nonGoals, risks = risks, affectedRepos = affectedRepos, taskSlug = taskSlug,
    criteria = acceptanceCriteria, questions = openQuestions,
)

class SpecDetailViewModel(
    private val repo: SpecDetailRepository,
    private val conn: WorkspaceConnection,
    private val specId: String,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<SpecDetailUiState>(SpecDetailUiState.Loading)
    val state: StateFlow<SpecDetailUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope
    private fun content() = _state.value as? SpecDetailUiState.Content

    fun load(): Job? {
        _state.value = SpecDetailUiState.Loading
        return scope().launch {
            runCatching { repo.load(conn, specId) }
                .onSuccess { _state.value = SpecDetailUiState.Content(it.toDetail()) }
                .onFailure { _state.value = SpecDetailUiState.Error(it.toKind(), it.message ?: "error") }
        }
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }

    /** Apply a write's returned review payload over the current detail (body unchanged). */
    private fun applyReview(rev: SpecReview) {
        val c = content() ?: return
        _state.value = c.copy(
            detail = c.detail.copy(status = rev.status, criteria = rev.acceptanceCriteria, questions = rev.openQuestions),
            inFlight = null,
        )
    }

    /** Run a write that returns a review; on success patch state, else surface a banner. */
    private fun write(ref: ActionRef, block: suspend () -> SpecReview): Job? {
        val c = content() ?: return null
        _state.value = c.copy(inFlight = ref, banner = null, blockers = null)
        return scope().launch {
            runCatching { block() }
                .onSuccess { applyReview(it) }
                .onFailure { failWrite(it) }
        }
    }

    private fun failWrite(t: Throwable) {
        val c = content() ?: return
        when (t) {
            is ApiConflictException ->
                if (t.detail.contains("cannot approve"))
                    _state.value = c.copy(inFlight = null, blockers = parseApproveBlockers(t.detail))
                else { _state.value = c.copy(inFlight = null, banner = "Spec changed since you opened it."); load() }
            is AuthException -> _state.value = SpecDetailUiState.Error(ErrorKind.AUTH, t.message ?: "unauthorized")
            is NotFoundException -> _state.value = SpecDetailUiState.Error(ErrorKind.NOT_FOUND, t.message ?: "gone")
            else -> _state.value = c.copy(inFlight = null, banner = "Couldn't reach workspace — retry.")
        }
    }

    fun setVerdict(criterionId: String, verdict: String): Job? =
        write(ActionRef.Verdict(criterionId)) { repo.setVerdict(conn, specId, criterionId, verdict) }

    fun answer(questionId: String, answer: String): Job? =
        write(ActionRef.Answer(questionId)) { repo.answer(conn, specId, questionId, answer) }

    fun ask(text: String): Job? = write(ActionRef.Ask) { repo.ask(conn, specId, text) }

    fun approve(bypass: Boolean): Job? = write(ActionRef.Approve) { repo.approve(conn, specId, bypass) }

    fun requestChanges(reason: String): Job? =
        write(ActionRef.RequestChanges) { repo.requestChanges(conn, specId, reason) }

    fun dispatch(): Job? {
        val c = content() ?: return null
        _state.value = c.copy(inFlight = ActionRef.Dispatch, banner = null, blockers = null)
        return scope().launch {
            runCatching { repo.dispatch(conn, specId) }
                .onSuccess { applyDispatch(it) }
                .onFailure { failWrite(it) }
        }
    }

    private fun applyDispatch(dr: DispatchResult) {
        val c = content() ?: return
        _state.value = c.copy(
            detail = c.detail.copy(status = dr.spec.status, taskSlug = dr.taskSlug),
            inFlight = null,
            dispatchResult = DispatchInfo(dr.taskSlug, dr.spawned, dr.handoff),
        )
    }

    fun dismissBlockers() { content()?.let { _state.value = it.copy(blockers = null) } }
    fun dismissDispatchResult() { content()?.let { _state.value = it.copy(dispatchResult = null) } }
    fun dismissBanner() { content()?.let { _state.value = it.copy(banner = null) } }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecDetailViewModelTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/SpecDetailViewModel.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/SpecDetailViewModelTest.kt
git commit -m "feat(gc): SpecDetailViewModel — load + review/act state machine"
mship journal "added SpecDetailViewModel: load, verdict/answer/ask, approve(+blockers), request-changes, dispatch, stale-409 refetch" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=6 -->
### Task 6: Markdown dependency + body renderer

No emulator → this task is build-verified (compilation), not unit-tested.

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/SpecBodyMarkdown.kt`

- [ ] **Step 1: Add the dependency**

In `android/app/build.gradle.kts`, add to the `dependencies { }` block (next to the other `implementation` lines):

```kotlin
    // Compose-native markdown rendering for the full spec body.
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
```

> Version note: if Gradle can't resolve `0.27.0`, bump to the latest stable `multiplatform-markdown-renderer-m3` on Maven Central (`https://reposearch.maven.org` → group `com.mikepenz`). The artifact is on Maven Central, which `settings.gradle.kts` already includes. The `-m3` variant supplies Material3-themed defaults so `Markdown(content)` needs no extra theming args.

- [ ] **Step 2: Write the wrapper composable**

Create `ui/specdetail/SpecBodyMarkdown.kt`:

```kotlin
package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown

/** Render the complete spec body markdown (all sections, verbatim) like `mship view spec`. */
@Composable
fun SpecBodyMarkdown(body: String, modifier: Modifier = Modifier) {
    Markdown(content = body, modifier = modifier)
}
```

- [ ] **Step 3: Build to verify the dependency + API resolve**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. If the `Markdown(...)` signature differs in the resolved version, adjust the call to that version's `Markdown` composable (the m3 module always exposes a `Markdown(content: String, modifier: Modifier = ...)` overload) until it compiles.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts \
        android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/SpecBodyMarkdown.kt
git commit -m "feat(gc): add markdown renderer + SpecBodyMarkdown wrapper"
mship journal "added multiplatform-markdown-renderer-m3 + SpecBodyMarkdown; assembleDebug green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=7 -->
### Task 7: SpecDetailScreen (Compose)

Build-verified (no emulator). The state machine it drives is already covered by Task 5 tests.

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/SpecDetailScreen.kt`

- [ ] **Step 1: Write the screen**

Create `ui/specdetail/SpecDetailScreen.kt`. It renders: top bar (back), summary chip, full body markdown, non-goals/risks, interactive criteria (✓/⚑ when `isReviewInteractive`), questions (answer/ask when interactive), a status-aware bottom action bar, and dialogs for request-changes / dispatch confirm / dispatch result / approve blockers. Pull-to-refresh mirrors the inbox.

```kotlin
package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.SpecAction
import com.atomikpanda.groundcontrol.data.availableActions
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion
import com.atomikpanda.groundcontrol.data.isReviewInteractive
import com.atomikpanda.groundcontrol.data.statusBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecDetailScreen(vm: SpecDetailViewModel, title: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        bottomBar = { (state as? SpecDetailUiState.Content)?.let { ActionBar(it, vm) } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                SpecDetailUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is SpecDetailUiState.Error -> ErrorView(s, vm, onBack)
                is SpecDetailUiState.Content -> ContentView(s, vm)
            }
        }
    }
}

@Composable
private fun ErrorView(s: SpecDetailUiState.Error, vm: SpecDetailViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        val msg = when (s.kind) {
            ErrorKind.AUTH -> "Token rejected. Fix this connection in Settings."
            ErrorKind.NOT_FOUND -> "This spec is no longer available."
            ErrorKind.NETWORK -> s.message
        }
        Text(msg, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.kind == ErrorKind.NETWORK) Button(onClick = { vm.load() }) { Text("Retry") }
            if (s.kind == ErrorKind.NOT_FOUND) Button(onClick = onBack) { Text("Back to inbox") }
            if (s.kind == ErrorKind.AUTH) OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentView(s: SpecDetailUiState.Content, vm: SpecDetailViewModel) {
    val d = s.detail
    val interactive = isReviewInteractive(d.status)
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) LaunchedEffect(true) { vm.load()?.join(); pull.endRefresh() }

    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp, 8.dp)) {
                    val sum = d.summary
                    statusBanner(d.status, d.taskSlug)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } ?: Text("● ${d.status}", style = MaterialTheme.typography.labelLarge)
                    Text("repos: ${d.affectedRepos.joinToString().ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("${sum.approved}/${sum.criteriaTotal} approved · ${sum.flagged} flagged · ${sum.unansweredQuestions} unanswered Q",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item { SpecBodyMarkdown(d.bodyMarkdown, Modifier.padding(16.dp, 4.dp)) }
            if (d.nonGoals.isNotEmpty()) {
                item { SectionLabel("NON-GOALS") }
                items(d.nonGoals.size) { i -> BulletText(d.nonGoals[i]) }
            }
            if (d.risks.isNotEmpty()) {
                item { SectionLabel("RISKS") }
                items(d.risks.size) { i -> BulletText(d.risks[i]) }
            }
            if (d.criteria.isNotEmpty()) {
                item { SectionLabel("ACCEPTANCE CRITERIA") }
                items(d.criteria.size, key = { d.criteria[it].id }) { i ->
                    CriterionRow(d.criteria[i], interactive, s.inFlight, vm)
                }
            }
            item { SectionLabel("OPEN QUESTIONS") }
            items(d.questions.size, key = { d.questions[it].id }) { i ->
                QuestionRow(d.questions[i], interactive, s.inFlight, vm)
            }
            if (interactive) item { AskQuestionRow(vm) }
        }
        PullToRefreshContainer(pull, modifier = Modifier.align(Alignment.TopCenter))
        s.banner?.let { BannerToast(it) { vm.dismissBanner() } }
    }

    s.blockers?.let { BlockersDialog(it, vm) }
    s.dispatchResult?.let { DispatchResultDialog(it, vm) }
}

@Composable private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))

@Composable private fun BulletText(text: String) =
    Text("• $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 2.dp))

@Composable
private fun CriterionRow(c: ReviewCriterion, interactive: Boolean, inFlight: ActionRef?, vm: SpecDetailViewModel) {
    val busy = inFlight is ActionRef.Verdict && inFlight.criterionId == c.id
    Row(Modifier.fillMaxWidth().padding(12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.padding(8.dp))
        } else if (interactive) {
            IconToggleButton(checked = c.verdict == "approved",
                onCheckedChange = { vm.setVerdict(c.id, if (c.verdict == "approved") "unreviewed" else "approved") }) {
                Icon(Icons.Filled.Check, "approve",
                    tint = if (c.verdict == "approved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
            IconToggleButton(checked = c.verdict == "flagged",
                onCheckedChange = { vm.setVerdict(c.id, if (c.verdict == "flagged") "unreviewed" else "flagged") }) {
                Icon(Icons.Filled.Flag, "flag",
                    tint = if (c.verdict == "flagged") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
            }
        } else {
            Text(verdictGlyph(c.verdict), Modifier.padding(8.dp))
        }
        Text(c.text, Modifier.padding(start = 4.dp))
    }
}

private fun verdictGlyph(v: String) = when (v) { "approved" -> "✓"; "flagged" -> "⚑"; else -> "•" }

@Composable
private fun QuestionRow(q: ReviewQuestion, interactive: Boolean, inFlight: ActionRef?, vm: SpecDetailViewModel) {
    var draft by remember(q.id) { mutableStateOf(q.answer ?: "") }
    val busy = inFlight is ActionRef.Answer && inFlight.questionId == q.id
    Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
        Text(q.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (interactive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = draft, onValueChange = { draft = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text(if (q.answer == null) "answer" else "edit answer") })
                if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
                else TextButton(onClick = { if (draft.isNotBlank()) vm.answer(q.id, draft) }) { Text("Send") }
            }
        } else {
            Text("answer: ${q.answer ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AskQuestionRow(vm: SpecDetailViewModel) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text("A new question blocks gated approve until answered.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f), singleLine = true, label = { Text("Ask a question") })
            TextButton(onClick = { if (text.isNotBlank()) { vm.ask(text); text = "" } }) { Text("Ask") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionBar(s: SpecDetailUiState.Content, vm: SpecDetailViewModel) {
    val actions = availableActions(s.detail.status)
    if (actions.isEmpty()) return
    var menu by remember { mutableStateOf(false) }
    var showReason by remember { mutableStateOf(false) }
    var showDispatch by remember { mutableStateOf(false) }
    val busy = s.inFlight != null

    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (SpecAction.REQUEST_CHANGES in actions)
                OutlinedButton(enabled = !busy, onClick = { showReason = true }) { Text("Request changes") }
            if (SpecAction.APPROVE in actions) {
                Box {
                    Button(enabled = !busy, onClick = { vm.approve(bypass = false) }) { Text("Approve") }
                    TextButton(enabled = !busy, onClick = { menu = true }) { Text("▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Approve anyway") },
                            onClick = { menu = false; vm.approve(bypass = true) })
                    }
                }
            }
            if (SpecAction.DISPATCH in actions)
                Button(enabled = !busy, onClick = { showDispatch = true }) { Text("Dispatch") }
        }
    }

    if (showReason) ReasonDialog(onDismiss = { showReason = false }) { showReason = false; vm.requestChanges(it) }
    if (showDispatch) ConfirmDialog(
        title = "Dispatch this spec?",
        body = "This binds/spawns a task and starts work on the host.",
        confirm = "Dispatch", onDismiss = { showDispatch = false },
    ) { showDispatch = false; vm.dispatch() }
}

@Composable
private fun ReasonDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request changes") },
        text = {
            OutlinedTextField(value = reason, onValueChange = { reason = it },
                label = { Text("Reason") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = reason.isNotBlank(), onClick = { onSubmit(reason) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BlockersDialog(blockers: List<String>, vm: SpecDetailViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dismissBlockers() },
        title = { Text("Can't approve yet") },
        text = { Column { blockers.forEach { Text("• $it") } } },
        confirmButton = { TextButton(onClick = { vm.dismissBlockers(); vm.approve(bypass = true) }) { Text("Approve anyway") } },
        dismissButton = { TextButton(onClick = { vm.dismissBlockers() }) { Text("OK") } },
    )
}

@Composable
private fun DispatchResultDialog(info: DispatchInfo, vm: SpecDetailViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dismissDispatchResult() },
        title = { Text(if (info.spawned) "Dispatched (spawned task)" else "Dispatched") },
        text = {
            Column {
                Text("task: ${info.taskSlug}")
                if (info.handoff.isNotBlank()) Text(info.handoff.take(280), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissDispatchResult() }) { Text("Done") } },
    )
}

@Composable
private fun BannerToast(message: String, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(8.dp), tonalElevation = 4.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/SpecDetailScreen.kt
git commit -m "feat(gc): SpecDetailScreen — body, criteria, questions, status-aware action bar + dialogs"
mship journal "added SpecDetailScreen (full body, verdict toggles, Q&A, approve/blockers, request-changes, dispatch); assembleDebug green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=8 -->
### Task 8: Wire the inbox row tap → navigation

**Files:**
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specs/SpecInboxViewModel.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specs/SpecInboxScreen.kt`
- Modify: `android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt`
- Modify: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecInboxViewModelTest.kt`

- [ ] **Step 1: Add a failing test** — assert the inbox section now exposes its `connectionId`. Append to `SpecInboxViewModelTest.kt`:

```kotlin
    @Test fun section_carries_connection_id_for_navigation() = runTest {
        val vm = SpecInboxViewModel(repo(), {
            listOf(WorkspaceConnection("conn-7", "http://h:47100", null, "ws-a"))
        }, this)
        vm.refresh()?.join()
        val content = vm.state.value as InboxUiState.Content
        assertEquals("conn-7", content.sections[0].connectionId)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecInboxViewModelTest"`
Expected: FAIL — `connectionId` unresolved on `WorkspaceSection`.

- [ ] **Step 3: Add `connectionId` to `WorkspaceSection`**

In `ui/specs/SpecInboxViewModel.kt`, change the data class and its construction:

```kotlin
data class WorkspaceSection(
    val workspaceName: String,
    val connectionId: String,
    val groups: Result<List<GroupBlock>>,
)
```
and in `refresh()`'s `results.map { ws -> WorkspaceSection(...) }`, add `connectionId = ws.connection.id`:

```kotlin
                    WorkspaceSection(
                        workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                        connectionId = ws.connection.id,
                        groups = ws.specs.map { specs -> toGroupBlocks(specs) },
                    )
```

- [ ] **Step 4: Run to verify the test passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.atomikpanda.groundcontrol.SpecInboxViewModelTest"`
Expected: PASS (existing 2 + new 1).

- [ ] **Step 5: Make inbox rows clickable**

In `ui/specs/SpecInboxScreen.kt`: add imports
```kotlin
import androidx.compose.foundation.clickable
```
change the signature to accept a callback:
```kotlin
fun SpecInboxScreen(vm: SpecInboxViewModel, onSpecClick: (connectionId: String, specId: String) -> Unit) {
```
and add the modifier to the row `ListItem` (inside `items(block.specs.size ...)`, where `section` is in scope):
```kotlin
                                    ListItem(
                                        headlineContent = { Text(spec.title) },
                                        supportingContent = { Text("${spec.status} · ${spec.affectedRepos.joinToString().ifBlank { "—" }}") },
                                        modifier = Modifier.clickable { onSpecClick(section.connectionId, spec.id) },
                                    )
```

- [ ] **Step 6: Add the nav route + wire the callback**

In `GroundControlApp.kt`:

Add imports:
```kotlin
import androidx.navigation.NavType
import androidx.navigation.compose.navArgument
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailScreen
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
```

Build a detail repo alongside the others:
```kotlin
    val detailRepo = remember { SpecDetailRepository(api) }
```

Change the Specs composable to pass the callback:
```kotlin
            composable(Section.SPECS.route) {
                val vm = viewModel {
                    SpecInboxViewModel(specRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                SpecInboxScreen(vm) { connId, specId ->
                    nav.navigate("specDetail/$connId/$specId")
                }
            }
```

Add the detail destination (after the Settings composable, inside the `NavHost { }`):
```kotlin
            composable(
                route = "specDetail/{connectionId}/{specId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("specId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val specId = entry.arguments?.getString("specId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the inbox.") }
                } else {
                    val title = remember(specId) { specId }
                    val vm = viewModel(key = "detail-$connectionId-$specId") {
                        SpecDetailViewModel(detailRepo, conn, specId)
                    }
                    SpecDetailScreen(vm, title = title, onBack = { nav.popBackStack() })
                }
            }
```

> The header title shows the spec id until load completes; the loaded screen renders the full record. (Passing the tapped row's title through the route is a possible polish, out of scope here.)

- [ ] **Step 7: Build + run the full unit suite**

Run: `cd android && ./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specs/SpecInboxViewModel.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specs/SpecInboxScreen.kt \
        android/app/src/main/java/com/atomikpanda/groundcontrol/GroundControlApp.kt \
        android/app/src/test/java/com/atomikpanda/groundcontrol/SpecInboxViewModelTest.kt
git commit -m "feat(gc): open spec detail from inbox row tap (nav route + connection resolve)"
mship journal "wired inbox row tap → specDetail route; section carries connectionId; build+tests green" --action committed
```
<!-- /mship:task -->

<!-- mship:task id=9 -->
### Task 9: Full verification + phase transition

**Files:** none (verification only).

- [ ] **Step 1: Run the full mship test (the ac10 evidence)**

Run: `mship test`
Expected: ground-control green (`assembleDebug` + `testDebugUnitTest`). `mship test` sources the toolchain via the workspace `env_runner`. If it fails, fix and re-run before proceeding.

- [ ] **Step 2: Confirm acceptance criteria**

Re-read `specs/2026-06-22-ground-control-c4c6.md` and check each AC against the implemented behavior (ac1–ac9 are exercised by the screen + the unit tests; ac10 is Step 1). Note any gap in the journal.

- [ ] **Step 3: Journal + transition toward review**

```bash
mship journal "spec-detail review/act loop implemented; full app build + unit tests green" --action completed --test-state pass
mship phase review
```

> Then use the `requesting-code-review` skill (or `mship finish` when ready) per your team's flow. `mship finish` will open the PR — pass `--body-file` with a real Summary + Test plan.
<!-- /mship:task -->

---

## Notes on what is intentionally NOT here (from the spec's non-goals)

Editing/drafting the body, notifications/push, the `needs_clarification → needs_review` reopen, Capture/Decisions/Tasks screens, iOS, offline cache, Compose/instrumentation tests, and marking `implemented` are all out of scope. The detail screen is read-only for criteria/questions outside `needs_review`, by design.
