package dev.chungjungsoo.gptmobile.data.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreboardFeedTest {
    private val emptyIndex = """{"schemaVersion":1,"updatedAt":100,"entries":[]}"""

    @Test fun `published empty feed is a successful anonymous response`() = runTest {
        HttpClient(
            MockEngine { request ->
                assertEquals(ScoreboardFeed.URL, request.url.toString())
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.IfNoneMatch])
                respond(emptyIndex, headers = headersOf(HttpHeaders.ETag, "first"))
            }
        ).use { client ->
            val result = ScoreboardFeed(client).fetch(null)
            assertTrue(result.index.entries.isEmpty())
            assertEquals("first", result.etag)
            assertEquals(100L, result.index.updatedAt)
        }
    }

    @Test fun `ETag revalidation retains the validated cached scores`() = runTest {
        val saved = ScoreboardFeedSnapshot(emptyIndex, ScoreboardFeed.decode(emptyIndex), "saved")
        HttpClient(
            MockEngine { request ->
                assertEquals("saved", request.headers[HttpHeaders.IfNoneMatch])
                respond("", HttpStatusCode.NotModified)
            }
        ).use { client -> assertEquals(saved, ScoreboardFeed(client).fetch(saved)) }
    }

    @Test fun `unpublished rate limited and unavailable feeds never become an empty success`() = runTest {
        for (status in listOf(304, 401, 403, 404, 429, 500)) {
            HttpClient(MockEngine { respond("", HttpStatusCode.fromValue(status)) }).use { client ->
                val failure = runCatching { ScoreboardFeed(client).fetch(null) }.exceptionOrNull()
                assertTrue("HTTP $status must fail", failure is IOException)
            }
        }
    }

    @Test fun `malformed unknown and duplicate results are rejected for live and saved indexes`() {
        for (raw in listOf("{}", "<html>outage</html>", emptyIndex.replace("100", "-1"), emptyIndex.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))) {
            assertTrue(runCatching { ScoreboardFeed.decode(raw) }.isFailure)
        }
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("scoreboard-contract.json")).bufferedReader().use { it.readText() }
        val submission = Json.decodeFromJsonElement<ScoreboardSubmission>(Json.parseToJsonElement(text).jsonArray.first().jsonObject.getValue("submission"))
        val duplicate = Json { encodeDefaults = true }.encodeToString(ScoreboardIndex(updatedAt = 100, entries = listOf(submission, submission)))
        assertTrue(runCatching { ScoreboardFeed.decode(duplicate) }.isFailure)
    }

    @Test fun `new response replaces cached ETag and validates full contract results`() = runTest {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("scoreboard-contract.json")).bufferedReader().use { it.readText() }
        val submission = Json.decodeFromJsonElement<ScoreboardSubmission>(Json.parseToJsonElement(text).jsonArray.first().jsonObject.getValue("submission"))
        val raw = Json { encodeDefaults = true }.encodeToString(ScoreboardIndex(updatedAt = 200, entries = listOf(submission)))
        HttpClient(MockEngine { respond(raw) }).use { client ->
            val result = ScoreboardFeed(client).fetch(ScoreboardFeedSnapshot(emptyIndex, ScoreboardFeed.decode(emptyIndex), "old"))
            assertEquals(200L, result.index.updatedAt)
            assertEquals(submission, result.index.entries.single())
            assertNull(result.etag)
            assertFalse(result.index.entries.isEmpty())
        }
    }

    @Test fun `cancellation propagates without manufacturing results`() = runTest {
        HttpClient(MockEngine { throw CancellationException("canceled") }).use { client ->
            assertTrue(runCatching { ScoreboardFeed(client).fetch(null) }.exceptionOrNull() is CancellationException)
        }
    }
}
