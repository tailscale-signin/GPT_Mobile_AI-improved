package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings
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

class AirbnbNativeToolTest {
    private val page = """<script id="data-deferred-state-0">{"niobeClientData":[["key",{"data":{"presentation":{"staysSearch":{"results":{"searchResults":[{"demandStayListing":{"id":"RGVtYW5kU3RheUxpc3Rpbmc6MTIz"},"structuredContent":{"primaryLine":{"body":"Toronto loft"}},"contextualPictures":[{"picture":"https://a0.muscache.com/photo.jpg"}]}]}}}}}]]}</script>"""
    private fun http(engine: MockEngine) = HttpClient(engine) {
        followRedirects = false
        install(HttpTimeout)
    }

    @Test fun publicSearchHasNoCredentialsAndCreatesPhotoCards() = runBlocking {
        http(
            MockEngine { request ->
                assertEquals("www.airbnb.com", request.url.host)
                assertEquals("/s/Toronto--Canada/homes", request.url.encodedPath)
                assertEquals("2026-11-01", request.url.parameters["checkin"])
                assertEquals("2", request.url.parameters["adults"])
                assertEquals(null, request.headers["Authorization"])
                assertEquals(null, request.headers["Cookie"])
                respond(page, headers = headersOf("Content-Type", "text/html"))
            }
        ).use { http ->
            val tool = AirbnbNativeTool(PublicAirbnbClient(http), { true }, { error("No fallback expected") })
            val result = tool.execute(
                "call",
                buildJsonObject {
                    put("location", "Toronto, Canada")
                    put("checkin", "2026-11-01")
                    put("checkout", "2026-11-03")
                    put("adults", 2)
                }
            )
            assertFalse(result.content.toString(), result.isError)
            val listing = AirbnbListings.normalize((result.content as ToolResultContent.Json).value).single()
            assertEquals("123", listing.id)
            assertEquals("Toronto loft", listing.title)
            assertEquals(listOf("https://a0.muscache.com/photo.jpg"), listing.photos)
            assertEquals(2, listing.nights)
            assertEquals(null, listing.price)
        }
    }

    @Test fun blockedSearchFallsBackToIndexedListingsWithoutClaimingAvailability() = runBlocking {
        http(MockEngine { respond("Blocked", HttpStatusCode.Forbidden) }).use { http ->
            val tool = AirbnbNativeTool(PublicAirbnbClient(http), { true }) { query ->
                assertTrue(query.startsWith("site:airbnb.com/rooms/ "))
                buildJsonObject {
                    put(
                        "results",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("url", "https://www.airbnb.com/rooms/123")
                                    put("title", "Public listing")
                                },
                                buildJsonObject {
                                    put("url", "https://airbnb.com.evil.example/rooms/321")
                                    put("title", "Wrong host")
                                }
                            )
                        )
                    )
                }
            }
            val result = tool.execute("call", buildJsonObject { put("location", "Toronto") })
            assertFalse(result.isError)
            val payload = (result.content as ToolResultContent.Json).value as JsonObject
            assertEquals(JsonPrimitive(true), payload["indexedFallback"])
            assertTrue(payload.toString().contains("unconfirmed"))
            assertEquals("123", AirbnbListings.normalize(payload).single().id)
        }
    }

    @Test fun disabledPluginAndInvalidArgumentsPerformNoNetworkRequests() = runBlocking {
        http(MockEngine { error("Must not request data") }).use { http ->
            val client = PublicAirbnbClient(http)
            val disabled = AirbnbNativeTool(client, { false }, { error("Must not search") })
            assertTrue(disabled.execute("call", buildJsonObject { put("location", "Toronto") }).isError)
            val enabled = AirbnbNativeTool(client, { true }, { error("Must not search") })
            assertTrue(
                enabled.execute(
                    "call",
                    buildJsonObject {
                        put("action", "details")
                        put("id", "../login")
                    }
                ).isError
            )
            assertTrue(
                enabled.execute(
                    "call",
                    buildJsonObject {
                        put("location", "Toronto")
                        put("adults", "2")
                    }
                ).isError
            )
            assertTrue(
                enabled.execute(
                    "call",
                    buildJsonObject {
                        put("location", "Toronto")
                        put("checkin", "2026-11-01")
                    }
                ).isError
            )
        }
    }

    @Test fun listingDetailsParseFactsAndUseDateParameterNames() {
        http(MockEngine { error("Parsing requires no network") }).use { http ->
            val client = PublicAirbnbClient(http)
            val args = buildJsonObject {
                put("id", "123")
                put("checkin", "2026-11-01")
                put("checkout", "2026-11-03")
            }
            val url = client.url("details", args)
            assertTrue(url.contains("check_in=2026-11-01"))
            assertTrue(url.contains("check_out=2026-11-03"))
            val html = """<meta property="og:title" content="Toronto loft"><meta property="og:image" content="https://a0.muscache.com/photo.jpg"><script id="data-deferred-state-0">{"data":{"presentation":{"stayProductDetailPage":{"sections":{"sections":[{"sectionId":"DESCRIPTION_DEFAULT","section":{"htmlDescription":{"htmlText":"Bright <b>loft</b>"}}}]}}}}}</script>"""
            val listing = client.parse(html, args).single()
            assertEquals("Bright loft", listing.description)
            assertEquals("Toronto loft", listing.title)
            assertEquals(null, listing.totalPrice)
        }
    }
}
