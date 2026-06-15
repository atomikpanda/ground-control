package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.HealthResponse
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun buildJson(): Json = Json { ignoreUnknownKeys = true }

class AuthException(message: String) : Exception(message)

/** Default production client (OkHttp engine). Tests inject a MockEngine-backed client. */
fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(buildJson()) }
    HttpResponseValidator {
        validateResponse { resp: HttpResponse ->
            if (resp.status == HttpStatusCode.Unauthorized) throw AuthException("401 from ${resp.call.request.url}")
            if (!resp.status.isSuccess()) throw Exception("HTTP ${resp.status.value} from ${resp.call.request.url}")
        }
    }
}

/** Thin wrapper over mship serve endpoints. One client; per-call base URL + bearer. */
class SpecApi(private val client: HttpClient) {

    suspend fun health(conn: WorkspaceConnection): HealthResponse =
        client.get("${conn.baseUrl}/health") { auth(conn) }.body()

    suspend fun listSpecs(conn: WorkspaceConnection): List<SpecSummary> =
        client.get("${conn.baseUrl}/specs") { auth(conn) }.body()

    private fun io.ktor.client.request.HttpRequestBuilder.auth(conn: WorkspaceConnection) {
        conn.token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }
}
