package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test fun googleRedirectIsFollowedWithoutCredentialsAndLocaleIsPreserved() = runBlocking {
        var requests = 0
        client(
            MockEngine { request ->
                requests++
                assertEquals("news.google.com", request.url.host)
                assertEquals(null, request.headers["Cookie"])
                assertEquals(null, request.headers["Authorization"])
                if (requests == 1) {
                    assertEquals("CA:en", request.url.parameters["ceid"])
                    respond("", HttpStatusCode.Found, headersOf("Location", "/rss?hl=en-CA&gl=CA&ceid=CA:en"))
                } else {
                    respond(xml)
                }
            }
        ).use { http ->
            val result = PublicNewsClient(http).google("top", "", "CA", "en-CA", 10)
            assertEquals(2, requests)
            assertEquals(2, result.articles.size)
            assertFalse(result.fallbackUsed)
        }
    }

    @Test fun transientFailuresRetryThenUseLabeledFallbackWithDiagnosticStatus() = runBlocking {
        var googleCalls = 0
        client(
            MockEngine { request ->
                if (request.url.host == "news.google.com") {
                    googleCalls++
                    respond("private response body", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
                } else {
                    assertEquals("www.bing.com", request.url.host)
                    assertEquals("Muskoka", request.url.parameters["q"])
                    assertEquals("en-CA", request.url.parameters["setmkt"])
                    respond("<rss><channel><item><title>Fallback story</title><link>https://example.com/news</link><News:Source>Local paper</News:Source></item></channel></rss>")
                }
            }
        ).use { http ->
            val payload = payload(
                NewsTool(PublicNewsClient(http), { true }).execute(
                    "fallback",
                    buildJsonObject {
                        put("query", "Muskoka")
                        put("source", "google")
                    }
                )
            )
            assertEquals(3, googleCalls)
            assertEquals("partial", value(payload, "status"))
            assertEquals("true", value(payload, "fallbackUsed"))
            val article = (payload["articles"] as JsonArray).first() as JsonObject
            assertEquals("bing", value(article, "feedProvider"))
            assertEquals("Local paper", value(article, "publisher"))
            assertTrue(payload.toString().contains("429"))
            assertFalse(payload.toString().contains("private response body"))
        }
    }

    @Test fun validEmptyFeedsAreNotReportedAsNetworkOutage() = runBlocking {
        var requests = 0
        client(
            MockEngine {
                requests++
                respond("<rss><channel></channel></rss>")
            }
        ).use { http ->
            val result = NewsTool(PublicNewsClient(http), { true }).execute(
                "empty",
                buildJsonObject {
                    put("query", "unmatched term")
                    put("source", "google")
                }
            )
            assertTrue(result.isError)
            assertEquals("no_results", value(payload(result), "errorCode"))
            assertEquals(2, requests)
        }
    }

    @Test fun htmlChallengeAndForbiddenFeedsRemainDistinctFromNoResults() = runBlocking {
        client(
            MockEngine { request ->
                if (request.url.host == "news.google.com") {
                    respond("<html><body>Please sign in</body></html>")
                } else {
                    respond("Forbidden", HttpStatusCode.Forbidden)
                }
            }
        ).use { http ->
            val result = NewsTool(PublicNewsClient(http), { true }).execute(
                "outage",
                buildJsonObject {
                    put("action", "top")
                    put("source", "google")
                }
            )
            val payload = payload(result)
            assertTrue(result.isError)
            assertEquals("feed_unavailable", value(payload, "errorCode"))
            assertTrue(payload.toString().contains("invalid_feed"))
            assertTrue(payload.toString().contains("403"))
        }
    }

    @Test fun redirectOutsideFeedHostIsBlockedAndBingFallbackStillWorks() = runBlocking {
        client(
            MockEngine { request ->
                assertTrue(request.url.host in setOf("news.google.com", "www.bing.com"))
                if (request.url.host == "news.google.com") {
                    respond("", HttpStatusCode.Found, headersOf("Location", "https://accounts.google.com/login"))
                } else {
                    respond(xml)
                }
            }
        ).use { http ->
            val result = PublicNewsClient(http).google("top", "", "US", "en", 10)
            assertTrue(result.fallbackUsed)
            assertTrue(result.diagnostics.toString().contains("redirect_blocked"))
        }
    }

    @Test fun trendsUseGeoSpecificLinksRelatedHeadlinesAndExplicitCacheAge() = runBlocking {
        client(
            MockEngine { request ->
                assertEquals("US", request.url.parameters["geo"])
                respond(
                    """<rss><channel><item><title>café &amp; weather</title><link>https://trends.google.com/trending/rss?geo=US</link><ht:approx_traffic>500+</ht:approx_traffic><ht:news_item><ht:news_item_title>Related headline</ht:news_item_title><ht:news_item_url>https://example.com/story</ht:news_item_url><ht:news_item_source>Local paper</ht:news_item_source></ht:news_item></item></channel></rss>""",
                    headers = headersOf("Age", "120")
                )
            }
        ).use { http ->
            val payload = payload(
                NewsTool(PublicNewsClient(http), { true }).execute(
                    "trends",
                    buildJsonObject {
                        put("action", "trending")
                        put("geo", "us")
                    }
                )
            )
            val article = (payload["articles"] as JsonArray).first() as JsonObject
            assertEquals("US", value(payload, "country"))
            assertEquals("argument", value(payload, "regionSource"))
            assertTrue(value(article, "url").contains("geo=US&q=caf"))
            assertEquals("Related headline", value(article, "summary"))
            assertEquals("related_headlines", value(article, "summaryKind"))
            assertEquals("500+", value(article, "traffic"))
            assertEquals("false", value(article, "relatedQueriesAvailable"))
            assertEquals(0, (article["relatedQueries"] as JsonArray).size)
            val diagnostic = (payload["diagnostics"] as JsonArray).first() as JsonObject
            assertEquals("120", value(diagnostic, "cacheAgeSeconds"))
        }
    }

    @Test fun missingCacheAgeIsUnknownAndConfiguredRegionIsExplicit() = runBlocking {
        client(
            MockEngine { request ->
                assertEquals("GB", request.url.parameters["geo"])
                respond("<rss><channel><item><title>Trend</title></item></channel></rss>")
            }
        ).use { http ->
            val payload = payload(
                NewsTool(PublicNewsClient(http), { true }, { "GB" to "en" }).execute(
                    "trends",
                    buildJsonObject {
                        put("action", "trending")
                        put("related_queries", false)
                    }
                )
            )
            assertEquals("plugin_settings", value(payload, "regionSource"))
            val diagnostic = (payload["diagnostics"] as JsonArray).first() as JsonObject
            assertEquals("false", value(diagnostic, "cacheAgeKnown"))
            assertEquals(null, diagnostic["cacheAgeSeconds"])
            val article = (payload["articles"] as JsonArray).first() as JsonObject
            assertEquals(null, article["relatedNews"])
            assertEquals("unavailable", value(article, "summaryKind"))
        }
    }

    @Test fun conflictingGeoAndCountryAreRejectedWithoutRequest() = runBlocking {
        client(MockEngine { error("No request expected") }).use { http ->
            val result = NewsTool(PublicNewsClient(http), { true }).execute(
                "invalid",
                buildJsonObject {
                    put("action", "trending")
                    put("country", "CA")
                    put("geo", "US")
                }
            )
            assertTrue(result.isError)
            assertTrue(result.content.toString().contains("must agree"))
        }
    }

    @Test fun successfulRetryDoesNotRequestFallback() = runBlocking {
        var requests = 0
        client(
            MockEngine { request ->
                assertEquals("news.google.com", request.url.host)
                requests++
                if (requests == 1) respond("Unavailable", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "0")) else respond(xml)
            }
        ).use { http ->
            val result = PublicNewsClient(http).google("top", "", "CA", "en", 10)
            assertEquals(2, requests)
            assertFalse(result.fallbackUsed)
            assertEquals(2, result.articles.size)
        }
    }

    @Test fun cancellationDoesNotRetryOrInvokeFallback() = runBlocking {
        var requests = 0
        client(
            MockEngine {
                requests++
                throw CancellationException("cancel")
            }
        ).use { http ->
            try {
                PublicNewsClient(http).google("top", "", "CA", "en", 10)
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertEquals(1, requests)
            }
        }
    }

    private fun payload(result: dev.chungjungsoo.gptmobile.data.agent.AgentToolResult) =
        (result.content as ToolResultContent.Json).value as JsonObject

    private fun value(obj: JsonObject, key: String) = (obj[key] as JsonPrimitive).content
}
