package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiLogPageTest {
    @Test fun cachedLogKeepsItsAcquisitionTimeWithoutAnotherHttpRead() = kotlinx.coroutines.test.runTest {
        var requests = 0
        val engine = io.ktor.client.engine.mock.MockEngine {
            requests++
            respond("complete log")
        }
        io.ktor.client.HttpClient(engine).use { client ->
            val reader = GitHubCiLogReader(client, "test", GitHubRequestScheduler(), GitHubResponseCache())
            val path = "/repos/example/project/actions/jobs/17/logs"
            val started = System.currentTimeMillis()
            reader.read(path, immutable = true)
            val completed = System.currentTimeMillis()
            var originalTime: Long? = null
            repeat(2) { index ->
                val accounting = GitHubDispatchAccounting(record = {})
                val log = withContext(accounting) { reader.read(path, immutable = true) }
                val result = accounting.finish(AgentToolResult("cached-$index", ToolResultContent.Text(log.text), false, dispatched = false), false)
                val receipt = requireNotNull(result.executionReceipt)
                val observed = requireNotNull(receipt["source_observed_at_ms"]?.jsonPrimitive?.longOrNull)
                assertTrue(observed in started..completed)
                if (originalTime != null) assertEquals(originalTime, observed)
                originalTime = observed
                assertEquals(JsonPrimitive(0), receipt["read_requests"])
                assertEquals(JsonPrimitive(1), receipt["cached_read_observations"])
                assertEquals(JsonPrimitive("RETAINED"), receipt["read_source"])
                assertFalse(result.dispatched)
            }
            assertEquals(1, requests)
        }
    }

    @org.junit.Test fun interruptedLogRestartsAtOriginalJobAndCachesOnlyDeliveredBody() = kotlinx.coroutines.test.runTest {
        val paths = mutableListOf<String>()
        val engine = io.ktor.client.engine.mock.MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.host == "api.github.com") {
                respond("", io.ktor.http.HttpStatusCode.Found, io.ktor.http.headersOf("Location", "https://logs.githubusercontent.com/log-${paths.size}"))
            } else {
                org.junit.Assert.assertNull(request.headers["Authorization"])
                if (paths.size == 2) throw java.io.EOFException("connection reset")
                respond("complete log")
            }
        }
        io.ktor.client.HttpClient(engine).use { client ->
            val reader = GitHubCiLogReader(client, "test", GitHubRequestScheduler(), GitHubResponseCache())
            val job = "/repos/example/project/actions/jobs/17/logs"
            val expected = GitHubCiLogReader.Log("complete log", 12, true)
            assertEquals(expected, reader.read(job, immutable = true))
            assertEquals(expected, reader.read(job, immutable = true))
            assertEquals(listOf(job, "/log-1", job, "/log-3"), paths)
        }
    }

    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test fun aLongUnicodeLineReassemblesWithoutLossAcrossPages() {
        val original = "🙂é".repeat(2000)
        val log = GitHubCiLogReader.Log(original, original.encodeToByteArray().size, true)
        var request = args("""{"job_id":8,"start_line":1,"end_line":1}""")
        val pieces = StringBuilder()
        var pages = 0
        while (true) {
            val page = GitHubCiLogPage.render(log, request, byteBudget = 1024)
            pages++
            page["excerpts"]!!.jsonArray.forEach { pieces.append(it.jsonObject["text"]!!.jsonPrimitive.content) }
            assertEquals(JsonPrimitive(false), page["truncated"])
            val next = page["continuation"] as? JsonObject ?: break
            assertEquals(JsonPrimitive(8), next["job_id"])
            request = next
            assertTrue(pages < 30)
        }
        assertTrue(pages > 1)
        assertEquals(original, pieces.toString())
    }

    @Test fun implicitLineWindowsContinueUntilAllCapturedLinesWereRead() {
        val text = (1..401).joinToString("\n") { "Line $it" }
        val log = GitHubCiLogReader.Log(text, text.length, true)
        val first = GitHubCiLogPage.render(log, args("""{"job_id":8}"""))
        assertEquals(200, first["excerpts"]!!.jsonArray.size)
        val second = GitHubCiLogPage.render(log, first["continuation"]!!.jsonObject)
        assertEquals(200, second["excerpts"]!!.jsonArray.size)
        val last = GitHubCiLogPage.render(log, second["continuation"]!!.jsonObject)
        assertEquals(1, last["excerpts"]!!.jsonArray.size)
        assertEquals(JsonPrimitive(false), last["has_more"])
    }

    @Test fun ingestionTruncationAndEmptyRangesAreExplicit() {
        val log = GitHubCiLogReader.Log("captured", 8, false)
        val page = GitHubCiLogPage.render(log, args("""{"job_id":8,"start_line":3}"""))
        assertEquals(JsonPrimitive(true), page["truncated"])
        assertEquals("empty_range", page["status"]!!.jsonPrimitive.content)
        assertTrue(page["limit_note"]!!.jsonPrimitive.content.contains("captured bytes only"))
        assertFalse(page.containsKey("continuation"))
    }
}
