package dev.chungjungsoo.gptmobile.data.amazon

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.AmazonPriceHistoryTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.presentation.ui.chat.amazonProductResults
import dev.chungjungsoo.gptmobile.presentation.ui.chat.collectChatSources
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonPublicPriceHistoryClientTest {
    private fun png() = ByteArrayOutputStream().also { ImageIO.write(java.awt.image.BufferedImage(800, 400, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test fun publicChartsHaveFixedOriginNoCredentialsAndReuseIdenticalLookups() = runBlocking {
        var requests = 0
        HttpClient(
            MockEngine { request ->
                requests++
                assertEquals("graph.keepa.com", request.url.host)
                assertEquals("https", request.url.protocol.name)
                assertEquals("B000000001", request.url.parameters["asin"])
                assertEquals("ca", request.url.parameters["domain"])
                assertEquals("365", request.url.parameters["range"])
                assertNull(request.headers["Authorization"])
                assertNull(request.url.parameters["key"])
                respond(png(), HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        ).use { http ->
            val client = AmazonPublicPriceHistoryClient(http)
            val first = client.chart("amazon.ca", "B000000001")
            val second = client.chart("amazon.ca", "B000000001")
            assertEquals(first, second)
            assertTrue(AmazonPublicPriceHistoryClient.validChart(first.png))
            assertEquals("https://keepa.com/#!product/6-B000000001", first.reference.sourceUrl)
            assertTrue(runCatching { client.chart("amazon.ca", "B000000001", allowed = { false }) }.isFailure)
        }
        assertEquals(1, requests)
    }

    @Test fun redirectsUnsupportedMarketsAndNonImagesNeverBecomeHistory() = runBlocking {
        assertNull(AmazonPublicPriceHistoryClient.reference("amazon.ca.evil.test", "B000000001"))
        assertNull(AmazonPublicPriceHistoryClient.reference("amazon.ca", "invalid"))
        assertNull(AmazonPublicPriceHistoryClient.reference("amazon.ca", "B000000001", 0))
        assertNull(AmazonPublicPriceHistoryClient.reference("amazon.com.au", "B000000001"))
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond("redirect", HttpStatusCode.Found, headersOf("Location", "https://evil.test/chart.png"))
            }
        ).use { http ->
            assertTrue(runCatching { AmazonPublicPriceHistoryClient(http).chart("amazon.ca", "B000000001") }.exceptionOrNull() is AmazonProviderException)
        }
        assertEquals(1, requests)
        assertFalse(AmazonPublicPriceHistoryClient.validChart("not an image".toByteArray()))
    }

    @Test fun revokedAccessWithholdsFetchedEvidence() = runBlocking {
        HttpClient(MockEngine { respond(png(), HttpStatusCode.OK, headersOf("Content-Type", "image/png")) }).use { http ->
            var checks = 0
            assertTrue(runCatching { AmazonPublicPriceHistoryClient(http).chart("amazon.ca", "B000000001", allowed = { ++checks == 1 }) }.exceptionOrNull()?.message.orEmpty().contains("revoked"))
        }
    }

    @Test fun historyToolPublishesProviderEvidenceAndClickableCardsWithoutInventingPrices() = runBlocking {
        HttpClient(MockEngine { respond(png(), HttpStatusCode.OK, headersOf("Content-Type", "image/png")) }).use { http ->
            var allowed = true
            val tool = AmazonPriceHistoryTool("profile", AmazonPublicPriceHistoryClient(http), null, { allowed }, { false })
            suspend fun fetch(asin: String) = tool.execute(
                asin,
                buildJsonObject {
                    put("asin", asin)
                    put("marketplace", "amazon.ca")
                }
            )
            val results = listOf(fetch("B000000001"), fetch("B000000002"))
            val events = results.mapIndexed { index, result ->
                assertFalse(result.isError)
                val payload = (result.content as ToolResultContent.Json).value.jsonObject
                assertTrue(payload.getValue("localObservations").jsonArray.isEmpty())
                assertFalse(payload.getValue("products").jsonArray.single().jsonObject.containsKey("priceAmount"))
                ToolEvent(
                    eventId = "history-$index", runId = "run", sequence = index, callId = result.callId,
                    connectionUidSnapshot = null, connectionNameSnapshot = "Keepa",
                    toolName = "amazon_get_price_history", modelToolName = AmazonPriceHistoryTool.NAME,
                    arguments = "{}", result = payload.toString(), resultType = ToolEventResultType.JSON,
                    status = ToolEventStatus.COMPLETED
                )
            }
            val sources = collectChatSources("", events).sources
            assertEquals(2, sources.count { it.host == "keepa.com" })
            assertEquals(2, sources.count { it.host == "amazon.ca" })
            assertEquals(2, amazonProductResults(events).size)
            allowed = false
            assertTrue(fetch("B000000001").isError)
        }
    }
}
