package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Local reads only. Watch writes are explicit native UI actions, outside the automatic read path. */
class AmazonLocalTool(
    private val owner: String,
    private val history: AmazonHistoryRepository,
    private val access: AmazonAccessPolicy,
    private val settings: suspend () -> PluginExecutionSettings,
    private val listWatches: Boolean = false
) : AgentTool {
    override val definition = AgentToolDefinition(
        name = if (listWatches) WATCHES else HISTORY,
        description = if (listWatches) {
            "List only this AI profile's explicitly saved manual Amazon watches. Local read, no network, checks, writes, background work, or notifications. Incomplete offer context remains awaiting a matching price."
        } else {
            "Read this AI profile's locally observed Amazon prices for an ASIN. No network. History begins with this installation's successful checks; sampled listing prices are not comparable offers or evidence of a historical low. Search/detail series remain separate."
        },
        inputSchema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", if (listWatches) 20 else 100)
                        }
                    )
                    if (!listWatches) {
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
                                put("enum", JsonArray(AmazonFreeMarket.entries.map { JsonPrimitive(it.domain) }))
                            }
                        )
                    }
                }
            )
            if (!listWatches) put("required", JsonArray(listOf(JsonPrimitive("asin"))))
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        try {
            val config = settings().normalized()
            val permittedKeys = if (listWatches) setOf("limit") else setOf("asin", "marketplace", "limit")
            require(arguments.keys.all { it in permittedKeys })
            val limit = arguments["limit"]?.let { raw -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("Invalid limit") } ?: if (listWatches) 20 else 100
            require(limit in 1..if (listWatches) 20 else 100)
            if (!access.allowed(owner, network = false)) return failure(callId, "PLUGIN_DISABLED", "Enable the free Amazon plugin and local tools for this profile.")
            var result = if (listWatches) {
                buildJsonObject {
                    put("schema", "amazon_watches_v1")
                    put("backgroundEnabled", false)
                    put(
                        "watches",
                        JsonArray(
                            history.listWatches(owner).take(limit).map { watch ->
                                buildJsonObject {
                                    put("watchId", watch.id)
                                    put("ownerProfileUid", watch.ownerProfileUid)
                                    put("asin", watch.asin)
                                    put("marketplace", watch.marketplace)
                                    put("targetAmount", watch.targetAmount)
                                    put("currency", watch.currency)
                                    put("state", watch.state)
                                    put("generation", watch.generation)
                                    put("conditionPolicy", watch.conditionPolicy)
                                    put("variantPolicy", watch.variantPolicy)
                                    watch.lastAttemptAt?.let { put("lastAttemptAt", java.time.Instant.ofEpochMilli(it).toString()) }
                                    watch.lastSuccessAt?.let { put("lastSuccessAt", java.time.Instant.ofEpochMilli(it).toString()) }
                                    watch.lastOutcome?.let { put("lastOutcome", it) }
                                }
                            }
                        )
                    )
                    put("notice", "Manual saved targets only. Check or edit them explicitly in Toolkit. Unknown seller, condition, variant and destination cannot trigger a price-drop alert.")
                }
            } else {
                val rawAsin = string(arguments, "asin") ?: error("Missing ASIN")
                require(rawAsin.length == 10)
                val asin = AmazonProducts.asin(rawAsin) ?: error("Invalid ASIN")
                val market = if ("marketplace" in arguments) AmazonFreeMarket.fromDomain(string(arguments, "marketplace").orEmpty()) else AmazonFreeMarket.fromDomain(config.amazonMarketplace)
                history.history(owner, requireNotNull(market), asin, limit).toJson(market, asin)
            }
            if (!access.allowed(owner, network = false)) return failure(callId, "PLUGIN_DISABLED", "Amazon access was revoked. Local data was withheld.")
            val arrayKey = if (listWatches) "watches" else "observations"
            while (result.toString().length > config.maxOutputCharacters && (result[arrayKey] as JsonArray).isNotEmpty()) {
                result = JsonObject(result + (arrayKey to JsonArray((result[arrayKey] as JsonArray).dropLast(1))) + ("truncated" to JsonPrimitive(true)))
            }
            if (!listWatches) result = JsonObject(result + ("returnedObservationCount" to JsonPrimitive((result[arrayKey] as JsonArray).size)))
            return AgentToolResult(callId, ToolResultContent.Json(result), false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return failure(callId, "INVALID_ARGUMENT", "Supply a supported market, ten-character ASIN and bounded integer limit.")
        } catch (_: IllegalStateException) {
            return failure(callId, "INVALID_ARGUMENT", "Supply only the fields in the local Amazon schema.")
        } catch (_: Exception) {
            return failure(callId, "STORAGE_ERROR", "Local Amazon data could not be read.")
        }
    }

    private fun string(arguments: JsonObject, key: String) = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun failure(callId: String, code: String, message: String) = AgentToolResult(
        callId,
        ToolResultContent.Json(
            buildJsonObject {
                put("schema", "amazon_local_error_v1")
                put("code", code)
                put("message", message)
            }
        ),
        true
    )

    companion object {
        const val HISTORY = "amazon_get_price_history__free_native"
        const val WATCHES = "amazon_list_price_watches__free_native"
        val names = setOf(HISTORY, WATCHES)
    }
}
