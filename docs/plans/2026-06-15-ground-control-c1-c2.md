# Ground Control C1/C2 — Android App Shell + Multi-Workspace Spec Inbox — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `c1-c2-app-shell-and-spec-inbox-mos-154` (approved) — `specs/2026-06-15-c1-c2-app-shell-and-spec-inbox-mos-154.md` in the workspace. Linear: MOS-154 (C1), MOS-155 (C2).

**Goal:** Ship the first Ground Control Android app — a buildable Compose project with a 5-section shell whose home is a spec inbox that aggregates specs across multiple `mship serve` workspaces, grouped workspace → status.

**Architecture:** Single Gradle `:app` module, MVVM. `data/` holds a Ktor client + DTOs + repositories + DataStore; `ui/` holds Compose screens with `ViewModel`s exposing `StateFlow`; `nav/` holds the Navigation-Compose graph. Multi-workspace from the start: Settings stores a list of `WorkspaceConnection`s; the inbox fans out to all of them in parallel and renders partial results.

**Tech Stack:** Kotlin 2.0, Jetpack Compose (BOM 2024.06.00, Material3), Navigation-Compose, Ktor Client 2.3.12 + kotlinx.serialization, AndroidX DataStore (Preferences), coroutines; JUnit4 + Ktor `MockEngine` + coroutines-test. Gradle 8.7 (wrapper), AGP 8.5.2, compileSdk 34, minSdk 26.

**Environment note:** No emulator/system-image is installed, so **verification is JVM unit tests only** (`./gradlew testDebugUnitTest`) plus a successful `./gradlew assembleDebug`. Before running Gradle in a shell: `source ~/toolchains/android-env.sh` (sets `JAVA_HOME`, `ANDROID_HOME`, PATH). The Gradle wrapper downloads Gradle on first run.

**Package root:** `com.atomikpanda.groundcontrol`. All paths below are relative to the `ground-control` repo root; the app lives under `android/`.

---

## Prerequisite (separate mothership task — NOT in this worktree)

The inbox row shows `affected_repos`, but `GET /specs` doesn't return it yet. This is a **separate small `mship` task in the `mothership` repo** (spawn it independently; this plan does not block on it because app tests use `MockEngine` sample JSON that already includes the field).

- Modify `src/mship/core/serve.py` — in `list_specs()`, add `affected_repos` to each dict:
  ```python
  @app.get("/specs")
  def list_specs():
      return [
          {"id": s.id, "title": s.title, "status": s.status,
           "task_slug": s.task_slug, "affected_repos": s.affected_repos}
          for s in store.list()
      ]
  ```
- Update `tests/core/test_serve.py::test_list_specs` to assert the new key, e.g. add `"affected_repos": []` (the seeded spec in `_seed_spec` has no repos) to the expected dict.
- TDD: change the test first, watch it fail, then add the field.

---

## File Structure (created under `android/`)

```
android/
  settings.gradle.kts            # module + repositories
  build.gradle.kts               # root: plugin versions
  gradle.properties              # jvm args, AndroidX flags
  gradle/wrapper/                # gradle-wrapper.properties (Gradle 8.7)
  gradlew, gradlew.bat
  local.properties               # sdk.dir (generated, gitignored)
  .gitignore                     # build/, .gradle/, local.properties
  app/
    build.gradle.kts             # app module: plugins, deps, android config
    src/main/AndroidManifest.xml
    src/main/java/com/atomikpanda/groundcontrol/
      MainActivity.kt            # single activity → Compose root
      GroundControlApp.kt        # Scaffold + bottom nav + NavHost
      data/
        dto/Dtos.kt              # SpecSummary, HealthResponse (@Serializable)
        SpecStatus.kt            # status enum + SpecGroup mapping (pure)
        WorkspaceConnection.kt   # connection model + ConnectionsCodec (pure)
        ConnectionsRepository.kt # DataStore-backed CRUD (thin)
        MshipClient.kt           # Ktor HttpClient factory + SpecApi (health, listSpecs)
        SpecRepository.kt        # listAllSpecs() aggregation across connections
      ui/
        nav/Section.kt           # 5-section enum (route, label, icon)
        specs/SpecInboxViewModel.kt
        specs/SpecInboxScreen.kt
        settings/SettingsViewModel.kt
        settings/SettingsScreen.kt
        placeholder/PlaceholderScreen.kt
        theme/Theme.kt
    src/test/java/com/atomikpanda/groundcontrol/
      SpecStatusTest.kt
      DtosTest.kt
      ConnectionsCodecTest.kt
      SpecApiTest.kt
      SpecRepositoryTest.kt
      SpecInboxViewModelTest.kt
```

Companion to every commit step: `mship journal "<what>; tests passing" --action committed` (run from the worktree).

---

## Task 1: Gradle scaffold that builds

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/.gitignore`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/atomikpanda/groundcontrol/MainActivity.kt`, `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/theme/Theme.kt`

- [ ] **Step 1: Create the Gradle wrapper files**

`android/gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```
Generate the wrapper jar + scripts: from `android/`, run `gradle wrapper --gradle-version 8.7` if a system `gradle` exists; otherwise download the wrapper jar:
```bash
cd android && mkdir -p gradle/wrapper
curl -fsSL -o gradle/wrapper/gradle-wrapper.jar \
  https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar
curl -fsSL -o gradlew  https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradlew
curl -fsSL -o gradlew.bat https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradlew.bat
chmod +x gradlew
```

- [ ] **Step 2: Create `android/settings.gradle.kts`**
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "GroundControl"
include(":app")
```

- [ ] **Step 3: Create `android/build.gradle.kts`**
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false
}
```

- [ ] **Step 4: Create `android/gradle.properties`**
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Create `android/.gitignore`**
```gitignore
*.iml
.gradle/
/local.properties
/build
/app/build
.idea/
```

- [ ] **Step 6: Create `android/app/build.gradle.kts`**
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.atomikpanda.groundcontrol"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.atomikpanda.groundcontrol"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.ktor:ktor-client-mock:2.3.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

- [ ] **Step 7: Create `android/app/src/main/AndroidManifest.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:allowBackup="true"
        android:label="Ground Control"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 8: Create `ui/theme/Theme.kt`**
```kotlin
package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun GroundControlTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
```

- [ ] **Step 9: Create a minimal `MainActivity.kt`** (replaced in Task 8)
```kotlin
package com.atomikpanda.groundcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GroundControlTheme { Text("Ground Control") } }
    }
}
```

- [ ] **Step 10: Generate `local.properties` and build**

Run:
```bash
source ~/toolchains/android-env.sh
cd android
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`. (First run downloads Gradle + dependencies.)

- [ ] **Step 11: Commit**
```bash
git add android/ && git commit -m "feat(android): scaffold buildable Compose project (C1)"
mship journal "android gradle scaffold builds (assembleDebug green)" --action committed
```

---

## Task 2: Spec status model + inbox grouping (pure)

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/SpecStatus.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecStatusTest.kt`

- [ ] **Step 1: Write the failing test**

`SpecStatusTest.kt`:
```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecGroup
import com.atomikpanda.groundcontrol.data.groupForStatus
import com.atomikpanda.groundcontrol.data.orderedGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecStatusTest {
    @Test fun maps_every_status_to_its_group() {
        assertEquals(SpecGroup.NEEDS_REVIEW, groupForStatus("needs_review"))
        assertEquals(SpecGroup.NEEDS_REVIEW, groupForStatus("needs_clarification"))
        assertEquals(SpecGroup.READY_TO_DISPATCH, groupForStatus("approved"))
        assertEquals(SpecGroup.IN_IMPLEMENTATION, groupForStatus("dispatched"))
        assertEquals(SpecGroup.DRAFTING, groupForStatus("captured"))
        assertEquals(SpecGroup.DRAFTING, groupForStatus("drafting"))
        assertEquals(SpecGroup.DONE, groupForStatus("implemented"))
    }

    @Test fun archived_and_unknown_have_no_group() {
        assertNull(groupForStatus("archived"))   // excluded from inbox
        assertNull(groupForStatus("bogus"))
    }

    @Test fun ordered_groups_are_actionable_first() {
        assertEquals(
            listOf(
                SpecGroup.NEEDS_REVIEW, SpecGroup.READY_TO_DISPATCH,
                SpecGroup.IN_IMPLEMENTATION, SpecGroup.DRAFTING, SpecGroup.DONE,
            ),
            orderedGroups(),
        )
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `source ~/toolchains/android-env.sh && cd android && ./gradlew testDebugUnitTest --tests "*SpecStatusTest"`
Expected: FAIL — unresolved reference `SpecGroup` / `groupForStatus`.

- [ ] **Step 3: Implement `SpecStatus.kt`**
```kotlin
package com.atomikpanda.groundcontrol.data

/** Display groups for the inbox, in actionable-first order. */
enum class SpecGroup(val label: String) {
    NEEDS_REVIEW("Needs review"),
    READY_TO_DISPATCH("Ready to dispatch"),
    IN_IMPLEMENTATION("In implementation"),
    DRAFTING("Drafting"),
    DONE("Done"),
}

/** Map a raw mship spec status to its inbox group, or null to hide it. */
fun groupForStatus(status: String): SpecGroup? = when (status) {
    "needs_review", "needs_clarification" -> SpecGroup.NEEDS_REVIEW
    "approved" -> SpecGroup.READY_TO_DISPATCH
    "dispatched" -> SpecGroup.IN_IMPLEMENTATION
    "captured", "drafting" -> SpecGroup.DRAFTING
    "implemented" -> SpecGroup.DONE
    else -> null   // archived + anything unknown are excluded
}

/** Group display order. */
fun orderedGroups(): List<SpecGroup> = SpecGroup.entries.toList()
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew testDebugUnitTest --tests "*SpecStatusTest"` → Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add android/app/src && git commit -m "feat(android): spec status→group mapping (C2)"
mship journal "status→group mapping + tests" --action committed
```

---

## Task 3: DTOs + JSON deserialization

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/dto/Dtos.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/DtosTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.HealthResponse
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parses_spec_summary_with_snake_case_fields() {
        val s = json.decodeFromString<SpecSummary>(
            """{"id":"dq","title":"Decision queue","status":"needs_review",
                "task_slug":"dq","affected_repos":["mothership","ground-control"]}"""
        )
        assertEquals("dq", s.id)
        assertEquals("Decision queue", s.title)
        assertEquals("needs_review", s.status)
        assertEquals(listOf("mothership", "ground-control"), s.affectedRepos)
    }

    @Test fun spec_summary_tolerates_missing_repos_and_unknown_keys() {
        val s = json.decodeFromString<SpecSummary>(
            """{"id":"x","title":"X","status":"drafting","task_slug":null,"extra":42}"""
        )
        assertEquals(emptyList<String>(), s.affectedRepos)   // default
        assertEquals(null, s.taskSlug)
    }

    @Test fun parses_health() {
        val h = json.decodeFromString<HealthResponse>("""{"status":"ok","workspace":"mship-workspace"}""")
        assertEquals("ok", h.status)
        assertEquals("mship-workspace", h.workspace)
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew testDebugUnitTest --tests "*DtosTest"` → FAIL (unresolved `SpecSummary`).

- [ ] **Step 3: Implement `dto/Dtos.kt`**
```kotlin
package com.atomikpanda.groundcontrol.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpecSummary(
    val id: String,
    val title: String,
    val status: String,
    @SerialName("task_slug") val taskSlug: String? = null,
    @SerialName("affected_repos") val affectedRepos: List<String> = emptyList(),
)

@Serializable
data class HealthResponse(
    val status: String,
    val workspace: String,
)
```

- [ ] **Step 4: Run, verify pass** — `./gradlew testDebugUnitTest --tests "*DtosTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add android/app/src && git commit -m "feat(android): serve DTOs (SpecSummary, HealthResponse)"
mship journal "DTOs + JSON parsing tests" --action committed
```

---

## Task 4: WorkspaceConnection model + ConnectionsCodec (pure)

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/WorkspaceConnection.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/ConnectionsCodecTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionsCodecTest {
    @Test fun round_trips_a_connection_list() {
        val list = listOf(
            WorkspaceConnection("1", "http://host:47100", "tok", "ws-a"),
            WorkspaceConnection("2", "http://other:47100", null, "ws-b"),
        )
        val restored = ConnectionsCodec.decode(ConnectionsCodec.encode(list))
        assertEquals(list, restored)
    }

    @Test fun decode_of_blank_is_empty() {
        assertEquals(emptyList<WorkspaceConnection>(), ConnectionsCodec.decode(""))
        assertEquals(emptyList<WorkspaceConnection>(), ConnectionsCodec.decode("not json"))
    }

    @Test fun normalizes_base_url_trailing_slash_and_validates() {
        assertEquals("http://h:47100", normalizedBaseUrl(" http://h:47100/ "))
        assertEquals("https://h", normalizedBaseUrl("https://h"))
        assertNull(normalizedBaseUrl("notaurl"))        // no scheme
        assertNull(normalizedBaseUrl(""))
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew testDebugUnitTest --tests "*ConnectionsCodecTest"` → FAIL.

- [ ] **Step 3: Implement `WorkspaceConnection.kt`**
```kotlin
package com.atomikpanda.groundcontrol.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WorkspaceConnection(
    val id: String,
    val baseUrl: String,
    val token: String? = null,
    val workspaceName: String = "",
)

/** Pure (de)serialization of the connection list stored in DataStore. */
object ConnectionsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(list: List<WorkspaceConnection>): String = json.encodeToString(list)

    fun decode(raw: String): List<WorkspaceConnection> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<WorkspaceConnection>>(raw) }.getOrDefault(emptyList())
}

/** Trim, strip a trailing slash, and require an http(s) scheme. Returns null if invalid. */
fun normalizedBaseUrl(input: String): String? {
    val t = input.trim().trimEnd('/')
    if (!t.startsWith("http://") && !t.startsWith("https://")) return null
    if (t.substringAfter("://").isBlank()) return null
    return t
}
```

- [ ] **Step 4: Run, verify pass** — `./gradlew testDebugUnitTest --tests "*ConnectionsCodecTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add android/app/src && git commit -m "feat(android): WorkspaceConnection model + codec + url validation"
mship journal "connection model + codec tests" --action committed
```

---

## Task 5: Ktor client + SpecApi (health, listSpecs)

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/MshipClient.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecApiTest.kt`

`SpecApi` takes an `HttpClient` so tests can inject a `MockEngine`. Each call targets a connection's `baseUrl` + bearer token.

- [ ] **Step 1: Write the failing test**
```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecApiTest {
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(buildJson()) } }

    private val conn = WorkspaceConnection("1", "http://host:47100", "secret", "ws")

    @Test fun list_specs_hits_specs_path_with_bearer() = runTest {
        var seenAuth: String? = null
        var seenUrl: String? = null
        val api = SpecApi(client { req ->
            seenAuth = req.headers[HttpHeaders.Authorization]
            seenUrl = req.url.toString()
            respond(
                """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val specs = api.listSpecs(conn)
        assertEquals(1, specs.size)
        assertEquals("a", specs[0].id)
        assertEquals("Bearer secret", seenAuth)
        assertTrue(seenUrl!!.endsWith("/specs"))
    }

    @Test fun health_returns_workspace_name() = runTest {
        val api = SpecApi(client { respond(
            """{"status":"ok","workspace":"mship-workspace"}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        assertEquals("mship-workspace", api.health(conn).workspace)
    }

    @Test(expected = Exception::class)
    fun list_specs_throws_on_401() = runTest {
        val api = SpecApi(client { respond("nope", HttpStatusCode.Unauthorized) })
        api.listSpecs(conn)
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew testDebugUnitTest --tests "*SpecApiTest"` → FAIL (unresolved `SpecApi`/`buildJson`).

- [ ] **Step 3: Implement `MshipClient.kt`**
```kotlin
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
```
Note: the test's mock client doesn't install `HttpResponseValidator`, so the `401` test relies on `.body()` deserialization failing on a non-JSON 401 body — which throws. (Production uses `defaultHttpClient()` with the explicit validator.) Keep the `@Test(expected = Exception::class)` broad.

- [ ] **Step 4: Run, verify pass** — `./gradlew testDebugUnitTest --tests "*SpecApiTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add android/app/src && git commit -m "feat(android): Ktor SpecApi (health, listSpecs) with bearer auth"
mship journal "SpecApi + MockEngine tests" --action committed
```

---

## Task 6: SpecRepository.listAllSpecs (parallel aggregation + partial failure)

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/data/SpecRepository.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecRepositoryTest {
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun apiRoutingByHost() = SpecApi(HttpClient(MockEngine { req ->
        when (req.url.host) {
            "good" -> respond(
                """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":[]}]""",
                HttpStatusCode.OK, jsonHdr)
            else -> respond("boom", HttpStatusCode.InternalServerError)
        }
    }) { install(ContentNegotiation) { json(buildJson()) } })

    @Test fun aggregates_across_connections_and_isolates_failures() = runTest {
        val repo = SpecRepository(apiRoutingByHost())
        val results = repo.listAllSpecs(listOf(
            WorkspaceConnection("1", "http://good:47100", null, "ws-good"),
            WorkspaceConnection("2", "http://bad:47100", null, "ws-bad"),
        ))
        assertEquals(2, results.size)
        val good = results.first { it.connection.workspaceName == "ws-good" }
        val bad = results.first { it.connection.workspaceName == "ws-bad" }
        assertEquals(listOf("a"), good.specs.getOrThrow().map { it.id })  // success
        assertTrue(bad.specs.isFailure)                                   // isolated failure
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew testDebugUnitTest --tests "*SpecRepositoryTest"` → FAIL.

- [ ] **Step 3: Implement `SpecRepository.kt`**
```kotlin
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
```

- [ ] **Step 4: Run, verify pass** — `./gradlew testDebugUnitTest --tests "*SpecRepositoryTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add android/app/src && git commit -m "feat(android): SpecRepository parallel aggregation + partial failure"
mship journal "SpecRepository aggregation tests" --action committed
```

---

## Task 7: SpecInboxViewModel (UI state + grouping)

**Files:**
- Create: `android/app/src/main/java/com/atomikpanda/groundcontrol/ui/specs/SpecInboxViewModel.kt`
- Test: `android/app/src/test/java/com/atomikpanda/groundcontrol/SpecInboxViewModelTest.kt`

The ViewModel turns `List<WorkspaceSpecs>` into a renderable tree: workspace sections, each with ordered status subgroups, plus an empty-config state.

- [ ] **Step 1: Write the failing test**
```kotlin
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecGroup
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.ui.specs.InboxUiState
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpecInboxViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun repo() = SpecRepository(SpecApi(HttpClient(MockEngine {
        respond(
            """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]},
                {"id":"b","title":"B","status":"needs_review","task_slug":null,"affected_repos":[]}]""",
            HttpStatusCode.OK, jsonHdr)
    }) { install(ContentNegotiation) { json(buildJson()) } }))

    @Test fun no_connections_yields_empty_config_state() = runTest {
        val vm = SpecInboxViewModel(repo()) { emptyList() }
        vm.refresh(); advanceUntilIdle()
        assertEquals(InboxUiState.EmptyConfig, vm.state.value)
    }

    @Test fun loads_and_groups_workspace_then_status() = runTest {
        val vm = SpecInboxViewModel(repo()) {
            listOf(WorkspaceConnection("1", "http://h:47100", null, "ws-a"))
        }
        vm.refresh(); advanceUntilIdle()
        val content = vm.state.value as InboxUiState.Content
        assertEquals(1, content.sections.size)
        val sec = content.sections[0]
        assertEquals("ws-a", sec.workspaceName)
        // ordered groups: NEEDS_REVIEW before READY_TO_DISPATCH
        assertEquals(
            listOf(SpecGroup.NEEDS_REVIEW, SpecGroup.READY_TO_DISPATCH),
            sec.groups.getOrThrow().map { it.group },
        )
        assertEquals(listOf("b"), sec.groups.getOrThrow()[0].specs.map { it.id })
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew testDebugUnitTest --tests "*SpecInboxViewModelTest"` → FAIL.

- [ ] **Step 3: Implement `SpecInboxViewModel.kt`**
```kotlin
package com.atomikpanda.groundcontrol.ui.specs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecGroup
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.groupForStatus
import com.atomikpanda.groundcontrol.data.orderedGroups
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GroupBlock(val group: SpecGroup, val specs: List<SpecSummary>)

data class WorkspaceSection(
    val workspaceName: String,
    val groups: Result<List<GroupBlock>>,   // failure → show error chip
)

sealed interface InboxUiState {
    data object Loading : InboxUiState
    data object EmptyConfig : InboxUiState                       // no connections configured
    data class Content(val sections: List<WorkspaceSection>) : InboxUiState
}

/** `connectionsProvider` is a suspend-free snapshot supplier (the repo/DataStore feeds it). */
class SpecInboxViewModel(
    private val repo: SpecRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
) : ViewModel() {

    private val _state = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    fun refresh() {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = InboxUiState.EmptyConfig; return }
        _state.value = InboxUiState.Loading
        viewModelScope.launch {
            val results = repo.listAllSpecs(connections)
            _state.value = InboxUiState.Content(
                results.map { ws ->
                    WorkspaceSection(
                        workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                        groups = ws.specs.map { specs -> toGroupBlocks(specs) },
                    )
                }
            )
        }
    }

    private fun toGroupBlocks(specs: List<SpecSummary>): List<GroupBlock> {
        val byGroup = specs.groupBy { groupForStatus(it.status) }
        return orderedGroups().mapNotNull { g ->
            byGroup[g]?.takeIf { it.isNotEmpty() }?.let { GroupBlock(g, it) }
        }   // empty groups + null-group (archived/unknown) omitted
    }
}
```

- [ ] **Step 4: Run, verify pass** — `./gradlew testDebugUnitTest --tests "*SpecInboxViewModelTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add android/app/src && git commit -m "feat(android): SpecInboxViewModel state + workspace→status grouping (C2)"
mship journal "inbox viewmodel + grouping tests" --action committed
```

---

## Task 8: Compose UI — shell, settings, inbox (build-verified)

No unit tests (per spec — no emulator). Verified by `assembleDebug`. Wire real `ConnectionsRepository` (DataStore) here.

**Files:**
- Create: `data/ConnectionsRepository.kt`, `ui/nav/Section.kt`, `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`, `ui/specs/SpecInboxScreen.kt`, `ui/placeholder/PlaceholderScreen.kt`, `GroundControlApp.kt`
- Modify: `MainActivity.kt`

- [ ] **Step 1: `data/ConnectionsRepository.kt`** (DataStore-backed; thin wrapper over `ConnectionsCodec`)
```kotlin
package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ground_control")
private val CONNECTIONS = stringPreferencesKey("connections")

class ConnectionsRepository(private val context: Context) {
    val connections: Flow<List<WorkspaceConnection>> =
        context.dataStore.data.map { ConnectionsCodec.decode(it[CONNECTIONS] ?: "") }

    suspend fun snapshot(): List<WorkspaceConnection> =
        ConnectionsCodec.decode(context.dataStore.data.first()[CONNECTIONS] ?: "")

    suspend fun save(list: List<WorkspaceConnection>) {
        context.dataStore.edit { it[CONNECTIONS] = ConnectionsCodec.encode(list) }
    }

    suspend fun upsert(conn: WorkspaceConnection) =
        save(snapshot().filterNot { it.id == conn.id } + conn)

    suspend fun remove(id: String) = save(snapshot().filterNot { it.id == id })
}
```

- [ ] **Step 2: `ui/nav/Section.kt`**
```kotlin
package com.atomikpanda.groundcontrol.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.RuleFolder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Section(val route: String, val label: String, val icon: ImageVector) {
    SPECS("specs", "Specs", Icons.Filled.Inbox),
    CAPTURE("capture", "Capture", Icons.Filled.MicNone),
    DECISIONS("decisions", "Decisions", Icons.Filled.RuleFolder),
    TASKS("tasks", "Tasks", Icons.Filled.Assignment),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}
```

- [ ] **Step 3: `ui/placeholder/PlaceholderScreen.kt`**
```kotlin
package com.atomikpanda.groundcontrol.ui.placeholder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun PlaceholderScreen(title: String, ticket: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$title — coming soon ($ticket)")
    }
}
```

- [ ] **Step 4: `ui/settings/SettingsViewModel.kt`**
```kotlin
package com.atomikpanda.groundcontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel(
    private val repo: ConnectionsRepository,
    private val api: SpecApi,
) : ViewModel() {
    val connections: StateFlow<List<WorkspaceConnection>> get() = _connections
    private val _connections = MutableStateFlow<List<WorkspaceConnection>>(emptyList())
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init { viewModelScope.launch { repo.connections.collect { _connections.value = it } } }

    /** Validate URL, probe /health, persist with the discovered workspace name. */
    fun addOrUpdate(id: String?, baseUrlInput: String, token: String?) {
        val base = normalizedBaseUrl(baseUrlInput) ?: run {
            _testResult.value = "Invalid URL (need http:// or https://)"; return
        }
        viewModelScope.launch {
            val probe = WorkspaceConnection(id ?: UUID.randomUUID().toString(), base, token?.ifBlank { null }, "")
            val named = runCatching { api.health(probe).workspace }
                .fold({ probe.copy(workspaceName = it) }, { probe })
            repo.upsert(named)
            _testResult.value = named.workspaceName.ifBlank { "Saved (couldn't reach /health)" }
        }
    }

    fun remove(id: String) { viewModelScope.launch { repo.remove(id) } }
}
```

- [ ] **Step 5: `ui/settings/SettingsScreen.kt`**
```kotlin
package com.atomikpanda.groundcontrol.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.WorkspaceConnection

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val connections by vm.connections.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Workspace connections", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, label = { Text("mship serve URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Bearer token (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.addOrUpdate(null, url, token); url = ""; token = "" }) { Text("Add / test") }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(connections, key = { it.id }) { c: WorkspaceConnection ->
                ListItem(
                    headlineContent = { Text(c.workspaceName.ifBlank { c.baseUrl }) },
                    supportingContent = { Text(c.baseUrl) },
                    trailingContent = { TextButton(onClick = { vm.remove(c.id) }) { Text("Remove") } },
                )
            }
        }
    }
}
```

- [ ] **Step 6: `ui/specs/SpecInboxScreen.kt`** (sectioned list + pull-to-refresh)
```kotlin
package com.atomikpanda.groundcontrol.ui.specs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecInboxScreen(vm: SpecInboxViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    var refreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; vm.refresh(); refreshing = false }) {
        when (val s = state) {
            InboxUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            InboxUiState.EmptyConfig -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Add an mship serve endpoint in Settings.")
            }
            is InboxUiState.Content -> LazyColumn(Modifier.fillMaxSize()) {
                s.sections.forEach { section ->
                    item { Text(section.workspaceName, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 12.dp)) }
                    section.groups.fold(
                        onSuccess = { blocks ->
                            blocks.forEach { block ->
                                item { Text(block.group.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 4.dp)) }
                                items(block.specs.size) { i ->
                                    val spec = block.specs[i]
                                    ListItem(
                                        headlineContent = { Text(spec.title) },
                                        supportingContent = { Text("${spec.status} · ${spec.affectedRepos.joinToString().ifBlank { "—" }}") },
                                    )
                                }
                            }
                        },
                        onFailure = { item { AssistChip(onClick = {}, label = { Text("unreachable") }, modifier = Modifier.padding(16.dp, 4.dp)) } },
                    )
                }
            }
        }
    }
}
```
> `material3:1.2.1` includes `PullToRefreshBox`. If the API differs at build time, fall back to `androidx.compose.material3.pulltorefresh.PullToRefreshContainer` + `rememberPullToRefreshState()`; keep the `onRefresh → vm.refresh()` behavior. Add `import androidx.compose.ui.Alignment`.

- [ ] **Step 7: `GroundControlApp.kt`** (Scaffold + bottom nav + NavHost, with a manual ViewModel factory)
```kotlin
package com.atomikpanda.groundcontrol

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atomikpanda.groundcontrol.data.*
import com.atomikpanda.groundcontrol.ui.nav.Section
import com.atomikpanda.groundcontrol.ui.placeholder.PlaceholderScreen
import com.atomikpanda.groundcontrol.ui.settings.SettingsScreen
import com.atomikpanda.groundcontrol.ui.settings.SettingsViewModel
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxScreen
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.content.Context

@Composable
fun GroundControlApp(context: Context) {
    val nav = rememberNavController()
    val connRepo = remember { ConnectionsRepository(context.applicationContext) }
    val api = remember { SpecApi(defaultHttpClient()) }
    val specRepo = remember { SpecRepository(api) }

    Scaffold(bottomBar = {
        val current by nav.currentBackStackEntryAsState()
        NavigationBar {
            Section.entries.forEach { s ->
                NavigationBarItem(
                    selected = current?.destination?.route == s.route,
                    onClick = { nav.navigate(s.route) { launchSingleTop = true } },
                    icon = { Icon(s.icon, s.label) },
                    label = { Text(s.label) },
                )
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = Section.SPECS.route, modifier = androidx.compose.ui.Modifier.padding(padding)) {
            composable(Section.SPECS.route) {
                val vm = viewModel { SpecInboxViewModel(specRepo) { runBlockingSnapshot(connRepo) } }
                SpecInboxScreen(vm)
            }
            composable(Section.CAPTURE.route) { PlaceholderScreen("Capture", "C3") }
            composable(Section.DECISIONS.route) { PlaceholderScreen("Decisions", "C7") }
            composable(Section.TASKS.route) { PlaceholderScreen("Tasks", "C7") }
            composable(Section.SETTINGS.route) {
                val vm = viewModel { SettingsViewModel(connRepo, api) }
                SettingsScreen(vm)
            }
        }
    }
}

/** Bridge the suspend snapshot to the VM's sync provider on first refresh. */
private fun runBlockingSnapshot(repo: ConnectionsRepository): List<WorkspaceConnection> =
    kotlinx.coroutines.runBlocking { repo.snapshot() }
```
> Implementation note: `viewModel { ... }` uses the Compose `viewModel` initializer overload (lifecycle-viewmodel-compose 2.8.x). `runBlockingSnapshot` is a pragmatic v0 bridge; a cleaner follow-up is to make `connectionsProvider` suspend and collect the flow — out of scope for C1/C2.

- [ ] **Step 8: Replace `MainActivity.kt`**
```kotlin
package com.atomikpanda.groundcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GroundControlTheme { GroundControlApp(this) } }
    }
}
```

- [ ] **Step 9: Build**

Run: `source ~/toolchains/android-env.sh && cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. Fix any API mismatches (notably the `PullToRefreshBox`/`viewModel` overloads and the `filterNot` typo) until it builds.

- [ ] **Step 10: Commit**
```bash
git add android/ && git commit -m "feat(android): app shell, settings (multi-connection), spec inbox UI (C1/C2)"
mship journal "compose shell + settings + inbox UI; assembleDebug green" --action committed
```

---

## Task 9: Taskfile wiring + full verification

**Files:**
- Modify: `Taskfile.yml` (repo root)

- [ ] **Step 1: Replace the stub `Taskfile.yml` commands**
```yaml
version: '3'

tasks:
  setup:
    desc: One-time / per-worktree setup (writes local.properties from ANDROID_HOME).
    dir: android
    cmds:
      - 'test -n "$ANDROID_HOME" || (echo "ANDROID_HOME unset — source ~/toolchains/android-env.sh" && exit 1)'
      - 'printf "sdk.dir=%s\n" "$ANDROID_HOME" > local.properties'

  build:
    desc: Build the app.
    dir: android
    cmds:
      - './gradlew assembleDebug'

  lint:
    desc: Lint / static analysis.
    dir: android
    cmds:
      - './gradlew lintDebug'

  test:
    desc: Run unit tests. Mothership records evidence from this task (`mship test`).
    dir: android
    cmds:
      - './gradlew testDebugUnitTest'

  run:
    desc: Install + launch on a connected device/emulator (best-effort; none in CI).
    dir: android
    cmds:
      - './gradlew installDebug'
      - 'adb shell am start -n com.atomikpanda.groundcontrol/.MainActivity'
```

- [ ] **Step 2: Verify via mship**

Run (from the worktree, env sourced):
```bash
source ~/toolchains/android-env.sh
mship test --task c1-c2-app-shell-and-spec-inbox-mos-154
```
Expected: ground-control `pass` (runs `./gradlew testDebugUnitTest`).

- [ ] **Step 3: Commit**
```bash
git add Taskfile.yml && git commit -m "build: wire Taskfile to the real Android Gradle build (C1)"
mship journal "Taskfile wired to gradle; mship test green" --action committed --test-state pass
```

---

## Acceptance Criteria Checklist (from the spec)

- [ ] ac1: `./gradlew assembleDebug` succeeds (Task 1, 8).
- [ ] ac2: `./gradlew testDebugUnitTest` green via `mship test` (Task 9).
- [ ] ac3: Boots to Specs home; 5 sections navigable (Task 8 — `GroundControlApp` bottom nav, start = Specs).
- [ ] ac4: Settings adds/edits/removes multiple connections, persisted, validated via `/health` (Task 4, 8 — `ConnectionsRepository`, `SettingsViewModel`).
- [ ] ac5: Inbox aggregates + groups workspace→status, empty groups hidden, archived excluded (Task 2, 6, 7).
- [ ] ac6: Rows show title + status + affected repos; pull-to-refresh (Task 7, 8).
- [ ] ac7: Unreachable/401 workspace → error chip, others still render (Task 6 partial-failure + Task 8 `onFailure` chip).
- [ ] ac8: Exhaustive status mapping test + MockEngine aggregation/partial-failure tests (Task 2, 6, 7).
- [ ] (mothership, separate task) `GET /specs` returns `affected_repos`.
