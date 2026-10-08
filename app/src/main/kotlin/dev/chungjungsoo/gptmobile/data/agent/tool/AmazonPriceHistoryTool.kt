package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicPriceHistoryClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A shared external history read for either Amazon plugin, with profile-owned local observations. */
internal class AmazonPriceHistoryTool(private val owner: String, private val client: AmazonPublicPriceHistoryClient, private val history: AmazonHistoryRepository?, private val allowed: suspend () -> Boolean, private val localAllowed: suspend () -> Boolean) : AgentTool {
    override val definition = AgentToolDefinition(
        NAME,
        "Retrieve the public Keepa price-history chart for an Amazon ASIN, plus this profile's local price observations when allowed. No API key is required for public charts. The app displays the chart in Product details. A chart is not numeric historical data: never invent past prices, dates, lows or coverage. Sources are provided separately by the app.",
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    put(
                        "asin",
                        buildJsonObject {
                            put("type", "string")
                            put("pattern", "^[A-Za-z0-9]{10}$")
                        }
                    )
                    put(
                        "marketplace",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("amazon.com", "amazon.ca", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.co.jp", "amazon.it", "amazon.es", "amazon.in", "amazon.com.mx").map(::JsonPrimitive)))
                        }
                    )
                    put(
                        "rangeDays",
                        buildJsonObject {
                            put("type", "integer")
                            put("enum", JsonArray(listOf(31, 90, 365).map(::JsonPrimitive)))
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("asin"), JsonPrimitive("marketplace"))))
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = try {
        require(arguments.keys.all { it in setOf("asin", "marketplace", "rangeDays") })
        fun string(key: String) = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val market = requireNotNull(string("marketplace")?.let(AmazonProducts::marketplace))
        val asin = requireNotNull(string("asin")?.let(AmazonProducts::asin))
        val days = arguments["rangeDays"]?.let { (it as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("Invalid range") } ?: 365
        val chart = client.chart(market, asin, days, allowed)
        val local = if (history != null && localAllowed()) history.history(owner, market, asin) else null
        if (!allowed()) error("Amazon access was revoked.")
        AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", AmazonProducts.SCHEMA)
                    put("provider", "Keepa public price history")
                    put("history", chart.reference.toJson())
                    put("retrievedAt", chart.retrievedAt.toString())
                    put(
                        "sources",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("url", chart.reference.sourceUrl)
                                    put("title", "Keepa price history")
                                }
                            )
                        )
                    )
                    put(
                        "products",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("marketplace", market)
                                    put("asin", asin)
                                    put("url", AmazonProducts.productUrl(market, asin))
                                    put("title", local?.observations?.firstOrNull()?.title ?: "Amazon product $asin")
                                    put("provider", "Keepa")
                                    put("historyOnly", true)
                                }
                            )
                        )
                    )
                    put(
                        "localObservations",
                        JsonArray(
                            local?.observations.orEmpty().map { point ->
                                buildJsonObject {
                                    put("amount", point.amount)
                                    put("currency", point.currency)
                                    put("observedAt", java.time.Instant.ofEpochMilli(point.observedAt).toString())
                                    put("sourceType", point.sourceType)
                                    put("seriesKey", point.seriesKey)
                                }
                            }
                        )
                    )
                    put("notice", "External chart and local sampled observations are separate evidence. Open the product card to inspect price history; do not claim exact external historical values from a chart URL alone.")
                }
            ),
            false
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        AgentToolResult(callId, ToolResultContent.Text(error.message ?: "Public Amazon price history is unavailable."), true)
    }

    companion object {
        const val NAME = "amazon_get_price_history__public"
    }
}
