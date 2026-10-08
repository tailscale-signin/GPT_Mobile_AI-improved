package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProviderException
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import java.math.BigDecimal
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One retail search per call, or at most five individual paid product lookups. */
class AmazonSearchTool(
    private val fetch: suspend (Map<String, String>) -> JsonObject,
    settings: PluginExecutionSettings = PluginExecutionSettings(),
    private val productDetails: Boolean = false,
    modelToolName: String = if (productDetails) GET_PRODUCTS else SEARCH,
    private val clock: Clock = Clock.systemUTC()
) : AgentTool {
    private val settings = settings.normalized()

    override val definition = AgentToolDefinition(
        name = modelToolName,
        description = if (productDetails) {
            "Retrieve Amazon product facts for 1–5 ASINs in one marketplace. Each ASIN costs one provider request. " +
                "Returns prices, availability, ratings and canonical product links when provided. Prices may change at checkout."
        } else {
            "Search Amazon retail products only in the configured marketplace ${settings.amazonMarketplace}. Tool arguments cannot change it. " +
                "Returns structured ASINs, prices, ratings, variants, availability and product links. " +
                "Price filters apply to the retrieved page only. Missing prices are unknown; do not invent them."
        },
        inputSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "marketplace",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(JsonPrimitive(settings.amazonMarketplace))))
                        }
                    )
                    put(
                        "fresh",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "Bypass the provider's cached response; consumes a new request. The plugin may require fresh responses.")
                        }
                    )
                    if (productDetails) {
                        put(
                            "asins",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("pattern", "^[A-Za-z0-9]{10}$")
                                    }
                                )
                                put("minItems", 1)
                                put("maxItems", 5)
                                put("uniqueItems", true)
                            }
                        )
                    } else {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("minLength", 1)
                                put("maxLength", 500)
                            }
                        )
                        put(
                            "maxResults",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", 10)
                            }
                        )
                        put(
                            "page",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", 20)
                            }
                        )
                        put(
                            "sort",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(SORTS.keys.map(::JsonPrimitive)))
                            }
                        )
                        listOf("minPrice", "maxPrice").forEach { key ->
                            put(
                                key,
                                buildJsonObject {
                                    put("type", "number")
                                    put("minimum", 0)
                                    put("maximum", 1000000000)
                                }
                            )
                        }
                    }
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive(if (productDetails) "asins" else "query"))))
            put("additionalProperties", false)
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val allowed = if (productDetails) setOf("asins", "marketplace", "fresh") else setOf("query", "marketplace", "fresh", "page", "sort", "maxResults", "minPrice", "maxPrice")
        if (arguments.keys.any { it !in allowed }) return error(callId, "Unsupported Amazon search argument.")
        val domain = AmazonProducts.marketplace(settings.amazonMarketplace)
            ?: return error(callId, "Choose a supported Amazon marketplace.")
        if ("marketplace" in arguments && string(arguments, "marketplace") == null) return error(callId, "Marketplace must be a string.")
        if ("marketplace" in arguments && string(arguments, "marketplace")?.let(AmazonProducts::marketplace) == null) return error(callId, "Choose a supported Amazon marketplace.")
        val fresh = (arguments["fresh"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if ("fresh" in arguments && fresh == null) return error(callId, "Fresh must be a boolean.")
        val params = linkedMapOf("engine" to if (productDetails) "amazon_product" else "amazon", "amazon_domain" to domain)
        if (settings.amazonFreshPrices || fresh == true) params["no_cache"] = "true"
        val limit = integer(arguments, "maxResults") ?: settings.searchResults
        if ("maxResults" in arguments && integer(arguments, "maxResults") !in 1..10) return error(callId, "maxResults must be between 1 and 10.")
        val minimum = price(arguments, "minPrice")
        val maximum = price(arguments, "maxPrice")
        if (("minPrice" in arguments && minimum == null) || ("maxPrice" in arguments && maximum == null) || (minimum != null && maximum != null && minimum > maximum)) {
            return error(callId, "Price bounds must be valid non-negative amounts in ascending order.")
        }
        val ids: List<String>
        if (productDetails) {
            val raw = arguments["asins"] as? JsonArray ?: return error(callId, "Provide 1–5 valid ASINs.")
            ids = raw.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.let(AmazonProducts::asin) }
            if (raw.size !in 1..5 || ids.size != raw.size || ids.distinct().size != ids.size) return error(callId, "Provide 1–5 distinct valid ASINs.")
        } else {
            val query = string(arguments, "query")?.trim()?.takeIf { it.isNotBlank() && it.length <= 500 }
                ?: return error(callId, "Amazon query must contain 1–500 characters.")
            val page = integer(arguments, "page") ?: 1
            if ("page" in arguments && integer(arguments, "page") !in 1..20) return error(callId, "Page must be between 1 and 20.")
            val sort = if ("sort" in arguments) string(arguments, "sort") else "featured"
            params["s"] = SORTS[sort] ?: return error(callId, "Choose a supported Amazon sort order.")
            params["k"] = query
            params["page"] = page.toString()
            ids = listOf("")
        }
        return try {
            val products = mutableListOf<JsonObject>()
            val failures = mutableListOf<String>()
            for (id in ids) {
                try {
                    val payload = fetch(if (productDetails) params + ("asin" to id) else params)
                    if (AmazonProducts.text(payload, "error") != null || (payload["search_metadata"] as? JsonObject)?.let { AmazonProducts.text(it, "status") } == "Error") {
                        throw AmazonProviderException("SerpApi could not complete this Amazon request. Check provider diagnostics and account access.")
                    }
                    val expected = if (productDetails) "product_results" else "organic_results"
                    if ((productDetails && payload[expected] !is JsonObject) || (!productDetails && payload[expected] !is JsonArray)) {
                        throw AmazonProviderException("SerpApi returned an unsupported Amazon response.")
                    }
                    val normalized = AmazonProducts.normalize(payload, domain, "SerpApi", clock, settings.amazonIncludeSponsored, 100)
                    if (!productDetails &&
                        (payload[expected] as JsonArray).isNotEmpty() &&
                        AmazonProducts.normalize(payload, domain, "SerpApi", clock, includeSponsored = true, maxResults = 1).isEmpty()
                    ) {
                        throw AmazonProviderException("SerpApi returned products without valid ASINs, titles or matching marketplace links.")
                    }
                    if (productDetails && normalized.none { AmazonProducts.text(it, "asin") == id }) {
                        throw AmazonProviderException("No matching product facts were returned for ASIN $id.")
                    }
                    products += if (productDetails) normalized.filter { AmazonProducts.text(it, "asin") == id } else normalized
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AmazonProviderException) {
                    failures += failure.message.orEmpty()
                }
            }
            if (failures.isNotEmpty() && products.isEmpty()) return error(callId, failures.first())
            val filtered = products.filter { item ->
                if (!productDetails && !AmazonProducts.hasPrice(item)) return@filter false
                if (minimum == null && maximum == null) {
                    true
                } else {
                    val amount = AmazonProducts.text(item, "priceAmount")?.toBigDecimalOrNull()
                    val upper = AmazonProducts.text(item, "priceMaxAmount")?.toBigDecimalOrNull() ?: amount
                    amount != null && (minimum == null || amount >= minimum) && (maximum == null || (upper != null && upper <= maximum))
                }
            }.take(if (productDetails) 5 else minOf(limit, settings.searchResults))
            val content = buildJsonObject {
                put("schema", AmazonProducts.SCHEMA)
                put("marketplace", domain)
                put("provider", "SerpApi")
                if (!productDetails) {
                    put("query", params.getValue("k"))
                    put("page", params.getValue("page").toInt())
                }
                put("products", JsonArray(filtered))
                put(
                    "notice",
                    "Prices and availability are provider observations and may change at checkout. " +
                        if (minimum != null || maximum != null) "Price filters apply only to the retrieved page; unknown prices are excluded." else "Provider responses may be cached unless fresh was requested."
                )
                if (failures.isNotEmpty()) put("partialErrors", JsonArray(failures.map(::JsonPrimitive)))
            }
            AgentToolResult(callId, ToolResultContent.Json(content), false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error(callId, "Amazon request failed. Check connectivity and the SerpApi connection; no product facts were verified.")
        }
    }

    private fun string(arguments: JsonObject, key: String) = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun integer(arguments: JsonObject, key: String) = (arguments[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun price(arguments: JsonObject, key: String): BigDecimal? {
        val raw = (arguments[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull ?: return null
        if (raw.length > 40) return null
        return raw.toBigDecimalOrNull()?.takeIf { it.scale() in -12..12 && it >= BigDecimal.ZERO && it <= BigDecimal("1000000000") }
    }

    private fun error(callId: String, message: String) = AgentToolResult(callId, ToolResultContent.Text(message), true)

    companion object {
        const val SEARCH = "amazon_search"
        const val GET_PRODUCTS = "amazon_get_products"
        private val SORTS = linkedMapOf(
            "featured" to "relevanceblender",
            "price_asc" to "price-asc-rank",
            "price_desc" to "price-desc-rank",
            "rating" to "review-rank",
            "newest" to "date-desc-rank",
            "bestselling" to "exact-aware-popularity-rank"
        )
    }
}
