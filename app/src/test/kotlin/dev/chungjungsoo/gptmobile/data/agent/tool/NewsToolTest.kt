package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsToolTest {
    private val xml = """<rss><channel><item><title>Google first</title><link>https://news.google.com/read/first</link><source>Publisher</source><pubDate>Fri, 9 Oct 2026 10:00:00 GMT</pubDate><description>&lt;p&gt;Summary&lt;/p&gt;</description></item><item><title>Google second</title><link>https://news.google.com/read/second</link></item></channel></rss>"""

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(HttpTimeout)
        followRedirects = false
    }

    @Test fun allSourcesInterleaveAndKeepDatesAndPublishersWithoutSendingCredentials() = runBlocking {
        client(
            MockEngine { request ->
                assertEquals(null, request.headers["Authorization"])
                assertEquals(null, request.headers["Cookie"])
                if (request.url.host == "news.google.com") {
                    assertEquals("CA", request.url.parameters["gl"])
                    assertEquals("Canada AI", request.url.parameters["q"])
                    respond(xml, headers = headersOf("Content-Type", "application/rss+xml"))
                } else {
                    respond("""{"hits":[{"title":"Hacker first","url":"https://example.com/article","created_at":"2026-10-09T10:00:00Z","points":10}]}""", headers = headersOf("Content-Type", "application/json"))
                }
            }
        ).use { http ->
            val result = NewsTool(PublicNewsClient(http), { true }).execute(
                "call",
                buildJsonObject {
                    put("query", "Canada AI")
                    put("maxResults", 2)
                }
            )
            assertFalse(result.isError)
            val payload = (result.content as ToolResultContent.Json).value as JsonObject
            val articles = payload["articles"] as JsonArray
            assertEquals(listOf("Google first", "Hacker first"), articles.map { ((it as JsonObject)["title"] as JsonPrimitive).content })
            assertTrue(payload.toString().contains("Publisher"))
            assertTrue(payload.toString().contains("Fri, 9 Oct 2026"))
        }
    }

    @Test fun oneFailedFeedDoesNotDiscardAnotherProvidersResults() = runBlocking {
        client(
            MockEngine { request ->
                if (request.url.host == "news.google.com") {
                    respond("Unavailable", HttpStatusCode.ServiceUnavailable)
                } else {
                    respond("""{"hits":[{"title":"Available story","objectID":"123","created_at":"2026-10-09T10:00:00Z"}]}""", headers = headersOf("Content-Type", "application/json"))
                }
            }
        ).use { http ->
            val result = NewsTool(PublicNewsClient(http), { true }).execute("call", buildJsonObject { put("query", "AI") })
            assertFalse(result.isError)
            assertTrue(result.content.toString().contains("https://news.ycombinator.com/item?id=123"))
        }
    }

    @Test fun trendsIncludeTrafficAndDisabledPluginPerformsNoReads() = runBlocking {
        var requests = 0
        client(
            MockEngine {
                requests++
                respond("<rss><channel><item><title>Trending topic</title><link>https://trends.google.com/trending</link><ht:approx_traffic>10000+</ht:approx_traffic></item></channel></rss>")
            }
        ).use { http ->
            val result = NewsTool(PublicNewsClient(http), { true }).execute("call", buildJsonObject { put("action", "trending") })
            assertTrue(result.content.toString().contains("10000+"))
            assertTrue(NewsTool(PublicNewsClient(http), { false }).execute("disabled", buildJsonObject { put("query", "AI") }).isError)
            assertEquals(1, requests)
        }
    }
}
