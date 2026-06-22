package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
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
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksApiTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(h: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(h)) { mshipDefaults() }

    @Test fun list_tasks_hits_tasks_path_with_bearer() = runTest {
        var url: String? = null; var auth: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString(); auth = req.headers[HttpHeaders.Authorization]
            respond("""[{"slug":"t1","phase":"dev","branch":"feat/t1"}]""", HttpStatusCode.OK, jsonHdr)
        })
        val tasks = api.listTasks(conn)
        assertEquals(1, tasks.size); assertEquals("t1", tasks[0].slug)
        assertTrue(url!!.endsWith("/tasks")); assertEquals("Bearer secret", auth)
    }

    @Test fun get_journal_hits_journal_path() = runTest {
        var url: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString()
            respond("""[{"timestamp":"t","message":"m"}]""", HttpStatusCode.OK, jsonHdr)
        })
        val j = api.getJournal(conn, "t1")
        assertEquals(1, j.size); assertTrue(url!!.endsWith("/journal/t1"))
    }

    @Test(expected = com.atomikpanda.groundcontrol.data.NotFoundException::class)
    fun get_task_maps_404() = runTest {
        val api = SpecApi(client { respond("""{"detail":"no task"}""", HttpStatusCode.NotFound, jsonHdr) })
        api.getTask(conn, "missing")
    }
}
