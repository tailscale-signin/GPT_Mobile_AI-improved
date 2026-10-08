package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Combine only selected, authorized leaf tools after their permission and budget wrappers. */
internal class AmazonCombinedTool(private val children: List<ResolvedAgentTool>, private val details: Boolean) : AgentTool {
    override val managesExecutionBudget = true
    private val lock = Mutex()
    private val cache = linkedMapOf<String, AgentToolResult>()
    override val definition = AgentToolDefinition(
        if (details) AmazonSearchTool.GET_PRODUCTS else AmazonSearchTool.SEARCH,
        "Use all enabled Amazon providers together. Returns one deduplicated product list with provider provenance. " +
            "A blocked or unavailable provider does not discard another provider's results. Reuse returned listings; do not repeat the same lookup.",
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    put(
                        "marketplace",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(AmazonProducts.marketplaces.keys.map(::JsonPrimitive)))
                        }
                    )
                    if (details) {
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
                                put("enum", JsonArray(listOf("source", "price_asc", "price_desc", "rating").map(::JsonPrimitive)))
                            }
                        )
                        listOf("minPrice", "maxPrice").forEach { key ->
                            put(
                                key,
                                buildJsonObject {
                                    put("type", "number")
                                    put("minimum", 0)
                                    put("maximum", 999999999)
                                }
                            )
                        }
                    }
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive(if (details) "asins" else "query"))))
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = lock.withLock {
        val key = JsonObject(arguments.toSortedMap()).toString()
        cache[key]?.let { previous ->
            if (children.all { it.canReuseResult?.invoke() == true }) {
                return@withLock previous.copy(callId = callId, sharedResult = true)
            }
            cache.remove(key)
        }
        val results = coroutineScope {
            children.mapIndexed { index, child ->
                async {
                    val properties = child.tool.definition.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                    val market = arguments["marketplace"] as? JsonPrimitive
                    val markets = (properties["marketplace"] as? JsonObject)?.get("enum") as? JsonArray
                    if (market != null && markets != null && market !in markets) {
                        return@async child to AgentToolResult(callId, ToolResultContent.Text("This provider does not support the requested marketplace."), true)
                    }
                    val mapped = JsonObject(
                        arguments.filterKeys { it in properties }.mapValues { (name, value) ->
                            if ((properties[name] as? JsonObject)?.get("type") == JsonPrimitive("string") && value is JsonPrimitive && !value.isString) JsonPrimitive(value.content) else value
                        }
                    )
                    val result = try {
                        withTimeoutOrNull(30_000) { child.tool.execute("$callId:provider:$index", mapped) }
                            ?: AgentToolResult(callId, ToolResultContent.Text("Provider timed out; other results are preserved."), true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        AgentToolResult(callId, ToolResultContent.Text("Provider unavailable."), true)
                    }
                    child to result
                }
            }.awaitAll()
        }
        val products = linkedMapOf<List<String?>, JsonObject>()
        val statuses = results.map { (child, result) ->
            val payload = when (val content = result.content) {
                is ToolResultContent.Json -> content.value as? JsonObject
                is ToolResultContent.Text -> parseSearchPayload(content.text) as? JsonObject
                else -> null
            }
            if (!result.isError && AmazonProducts.text(payload ?: JsonObject(emptyMap()), "schema") == AmazonProducts.SCHEMA) {
                (payload?.get("products") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { product ->
                    val identity = listOf("marketplace", "asin", "seller", "condition", "variant").map { AmazonProducts.text(product, it) }
                    val previous = products[identity]
                    val providers = ((previous?.get("providers") as? JsonArray).orEmpty() + listOfNotNull(product["provider"], JsonPrimitive(child.connectionName.orEmpty()))).distinct()
                    // Preserve the earliest provider's observed price; fill only absent facts.
                    products[identity] = JsonObject(product + previous.orEmpty() + ("providers" to JsonArray(providers)))
                }
            }
            buildJsonObject {
                put("provider", child.connectionName ?: child.modelToolName)
                put("status", if (result.isError) "unavailable" else "completed")
                if (result.isError) put("notice", "This provider returned no verified products. Use the other provider's results or retry in a new response.")
            }
        }
        val allFailed = results.all { it.second.isError }
        val result = AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", AmazonProducts.SCHEMA)
                    put("provider", "combined")
                    put(
                        "status",
                        if (allFailed) {
                            "failure"
                        } else if (products.isEmpty()) {
                            "no_results"
                        } else if (results.any { it.second.isError }) {
                            "partial"
                        } else {
                            "success"
                        }
                    )
                    put("providers", JsonArray(statuses))
                    put("products", JsonArray(products.values.take(if (details) 5 else 20)))
                }
            ),
            allFailed,
            outputBudgetExhausted = results.any { it.second.outputBudgetExhausted },
            toolCallBudgetExhausted = results.any { it.second.toolCallBudgetExhausted }
        )
        if (cache.size >= 16) cache.remove(cache.keys.first())
        cache[key] = result
        result
    }
}

internal fun aggregateAmazonTools(tools: List<ResolvedAgentTool>): List<ResolvedAgentTool> {
    var result = tools
    for (details in listOf(false, true)) {
        val name = if (details) AmazonSearchTool.GET_PRODUCTS else AmazonSearchTool.SEARCH
        val children = result.filter { it.realToolName == name && "${if (details) "asins" else "query"}" in ((it.tool.definition.inputSchema["properties"] as? JsonObject) ?: JsonObject(emptyMap())) }
        if (children.size < 2) continue
        val aggregate = AmazonCombinedTool(children, details)
        result = result.filterNot { it in children } + ResolvedAgentTool(aggregate, null, "Amazon research", name, name)
    }
    return result
}
