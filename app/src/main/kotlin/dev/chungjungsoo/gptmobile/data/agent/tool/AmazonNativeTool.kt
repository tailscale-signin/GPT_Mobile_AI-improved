package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFetchResult
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonItemFailure
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductRequest
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadContext
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadError
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadException
import dev.chungjungsoo.gptmobile.data.amazon.AmazonSearchRequest
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Reviewed network reads only. Optional capture stays inside the live permission observer. */
class AmazonNativeTool(
    private val provider: AmazonProvider,
    private val productDetails: Boolean,
    private val settings: suspend () -> PluginExecutionSettings,
    private val isAllowed: suspend () -> Boolean,
    private val permissionChanges: Flow<Boolean>? = null,
    private val onFetched: (suspend (String, AmazonFreeMarket, AmazonFetchResult) -> Unit)? = null
) : AgentTool {
    override val definition = AgentToolDefinition(
        name = if (productDetails) GET_PRODUCTS else SEARCH,
        description = if (productDetails) {
            "Read Amazon Canada/US product details for 1–5 ASINs using public pages. Free native preview; no API key. Missing price or offer identity is unknown. Local observations may be saved outside temporary chats. Incomplete offer context cannot trigger an alert."
        } else {
            "Search Amazon Canada/US products using one public results page. Free native preview; no API key. Sort and price filters apply only to this page. Prices need a confirmed currency; unknown prices cannot pass numeric filters. Amazon may block public pages."
        },
        inputSchema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    put(
                        "marketplace",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(AmazonFreeMarket.entries.map { JsonPrimitive(it.domain) }))
                        }
                    )
                    if (productDetails) {
                        put(
                            "asins",
                            buildJsonObject {
                                put("type", "array")
                                put("minItems", 1)
                                put("maxItems", 5)
                                put("uniqueItems", true)
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("pattern", "^[A-Za-z0-9]{10}$")
                                    }
                                )
                            }
                        )
                    } else {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("minLength", 1)
                                put("maxLength", 200)
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
                            "sort",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(SORTS.map(::JsonPrimitive)))
                            }
                        )
                        listOf("minPrice", "maxPrice").forEach { name ->
                            put(
                                name,
                                buildJsonObject {
                                    put("type", "string")
                                    put("maxLength", 14)
                                    put("pattern", "^[0-9]{1,9}(\\.[0-9]{1,2})?$")
                                }
                            )
                        }
                    }
                }
            )
            put("required", JsonArray(listOf(if (productDetails) "asins" else "query").map(::JsonPrimitive)))
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        var market = AmazonFreeMarket.CANADA
        var outputLimit = 32_000
        try {
            val config = settings().normalized()
            outputLimit = config.maxOutputCharacters
            val default = AmazonFreeMarket.fromDomain(config.amazonMarketplace) ?: AmazonFreeMarket.CANADA
            val suppliedMarket = string(arguments, "marketplace")
            if ("marketplace" in arguments && suppliedMarket == null) invalid()
            market = suppliedMarket?.let { AmazonFreeMarket.fromDomain(it) ?: throw AmazonReadException(AmazonReadError.UNSUPPORTED_MARKETPLACE, "The native preview supports amazon.ca and amazon.com only.") } ?: default
            val properties = definition.inputSchema["properties"] as JsonObject
            if (arguments.keys.any { it !in properties }) invalid()
            val request = if (productDetails) null else searchRequest(arguments, market, config)
            val asins = if (productDetails) {
                val values = arguments["asins"] as? JsonArray ?: invalid()
                if (values.size !in 1..5) invalid()
                values.map { value ->
                    val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: invalid()
                    if (raw.length != 10) invalid()
                    AmazonProducts.asin(raw) ?: invalid()
                }.also { if (it.distinct().size != it.size) invalid() }
            } else {
                emptyList()
            }
            if (!isAllowed()) throw AmazonReadException(AmazonReadError.PLUGIN_DISABLED, "Enable Amazon Research Free globally and for this AI profile before using it.")
            val requestId = java.util.UUID.randomUUID().toString()
            val context = AmazonReadContext(config.timeoutSeconds, config.amazonDailyRequests, isAllowed)
            val result = supervisorScope {
                val operation = async {
                    val fetched = if (productDetails) provider.products(AmazonProductRequest(asins, market), context) else provider.search(requireNotNull(request), context)
                    if (!isAllowed()) throw PermissionRevoked()
                    try {
                        onFetched?.invoke(requestId, market, fetched)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: AmazonReadException) {
                        throw failure
                    } catch (_: Exception) {
                        throw AmazonReadException(AmazonReadError.STORAGE_ERROR, "Product facts were fetched, but local Amazon history could not be saved.")
                    }
                    fetched
                }
                val observer = permissionChanges?.let { changes -> launch { changes.collect { allowed -> if (!allowed) operation.cancel(PermissionRevoked()) } } }
                try {
                    operation.await()
                } catch (_: PermissionRevoked) {
                    throw AmazonReadException(AmazonReadError.PLUGIN_DISABLED, "Amazon Research Free was disabled during this lookup.")
                } finally {
                    observer?.cancel()
                }
            }
            if (!isAllowed()) throw AmazonReadException(AmazonReadError.PLUGIN_DISABLED, "Amazon Research Free was disabled during this lookup.")
            val content = ToolResultContent.Json(AmazonProducts.limitResult(result.toJson(requestId, market), outputLimit))
            return AgentToolResult(callId, content, result.products.isEmpty() && result.errors.isNotEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AmazonReadException) {
            return failure(callId, market, failure.code, failure.message.orEmpty(), outputLimit)
        } catch (_: Exception) {
            return failure(callId, market, AmazonReadError.NETWORK_ERROR, "The native Amazon lookup could not complete. No product facts were verified.", outputLimit)
        }
    }

    private fun searchRequest(arguments: JsonObject, market: AmazonFreeMarket, config: PluginExecutionSettings): AmazonSearchRequest {
        val query = string(arguments, "query") ?: invalid()
        if (query.isBlank() || query.length > 200 || query.any { it.code < 32 }) invalid()
        val limit = (arguments["maxResults"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (("maxResults" in arguments && limit == null) || (limit != null && limit !in 1..10)) invalid()
        val sort = string(arguments, "sort") ?: if ("sort" in arguments) invalid() else "source"
        if (sort !in SORTS) invalid()
        val minimum = price(arguments, "minPrice")
        val maximum = price(arguments, "maxPrice")
        if (minimum != null && maximum != null && minimum > maximum) invalid()
        return AmazonSearchRequest(query.trim(), market, minOf(limit ?: config.searchResults, config.searchResults), config.amazonIncludeSponsored, sort, minimum, maximum)
    }

    private fun price(arguments: JsonObject, name: String): BigDecimal? {
        if (name !in arguments) return null
        val raw = string(arguments, name) ?: invalid()
        if (!Regex("[0-9]{1,9}(?:\\.[0-9]{1,2})?").matches(raw)) invalid()
        return raw.toBigDecimal()
    }

    private fun string(arguments: JsonObject, name: String) = (arguments[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun invalid(): Nothing = throw AmazonReadException(AmazonReadError.INVALID_ARGUMENT, "Supply only the fields in the native Amazon tool schema, within their stated bounds.")

    private fun failure(callId: String, market: AmazonFreeMarket, code: AmazonReadError, message: String, limit: Int): AgentToolResult = AgentToolResult(
        callId,
        ToolResultContent.Json(AmazonProducts.limitResult(AmazonFetchResult(emptyList(), listOf(AmazonItemFailure(code, message)), 0).toJson(callId, market), limit)),
        true
    )

    private class PermissionRevoked : CancellationException("Amazon access revoked")

    companion object {
        const val SEARCH = "amazon_search__free_native"
        const val GET_PRODUCTS = "amazon_get_products__free_native"
        val names = setOf(SEARCH, GET_PRODUCTS)
        private val SORTS = listOf("source", "price_asc", "price_desc", "rating")
    }
}
