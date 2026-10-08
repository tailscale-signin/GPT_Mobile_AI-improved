package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Children retain the run's consent, timeout and budget wrappers. No provider is enabled implicitly. */
internal class AmazonCombinedTool(private val providers: List<ResolvedAgentTool>, name: String) : AgentTool {
    override val managesExecutionBudget = true
    private val preferred = providers.sortedBy { it.modelToolName in AmazonNativeTool.names }
    override val definition = preferred.first().tool.definition.copy(
        name = name,
        description = preferred.first().tool.definition.description + " Uses all compatible enabled Amazon providers together, preserves provider provenance, and reuses identical lookups within this response. Product cards are rendered by the app; summarize results before starting another search."
    )
    private val cache = ConcurrentHashMap<String, AgentToolResult>()
    private val locks = Array(16) { Mutex() }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val key = JsonObject(arguments.toSortedMap()).toString()
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            // Evidence was already authorized for this same response; reuse does not perform I/O.
            cache[key]?.let { return@withLock it.copy(callId = callId, sharedResult = true) }
            val result = fetch(callId, arguments)
            if (!result.isError && !result.toolCallBudgetExhausted && !result.outputBudgetExhausted) {
                if (cache.size >= 24) cache.keys.firstOrNull()?.let(cache::remove)
                cache[key] = result
            }
            result
        }
    }

    private suspend fun fetch(callId: String, arguments: JsonObject): AgentToolResult = coroutineScope {
        val results = preferred.mapIndexed { index, provider ->
            async {
                val properties = provider.tool.definition.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val mapped = arguments.filterKeys { it in properties }.toMutableMap()
                if (provider.modelToolName in AmazonNativeTool.names) {
                    val market = (arguments["marketplace"] as? JsonPrimitive)?.contentOrNull
                    if ((market != null && market !in setOf("amazon.ca", "amazon.com")) ||
                        (arguments["page"] as? JsonPrimitive)?.contentOrNull?.let { it != "1" } == true ||
                        (arguments["query"] as? JsonPrimitive)?.contentOrNull.orEmpty().length > 200
                    ) {
                        return@async provider to AgentToolResult(callId, ToolResultContent.Text("Public-page provider does not support this marketplace, page or query length."), true)
                    }
                    if (mapped["sort"] == JsonPrimitive("featured")) mapped["sort"] = JsonPrimitive("source")
                    for (field in listOf("minPrice", "maxPrice")) {
                        (mapped[field] as? JsonPrimitive)?.let { mapped[field] = JsonPrimitive(it.content) }
                    }
                }
                try {
                    provider to provider.tool.execute("$callId:amazon:$index", JsonObject(mapped))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    provider to AgentToolResult(callId, ToolResultContent.Text("Amazon provider unavailable; successful sibling results remain available."), true)
                }
            }
        }.awaitAll()
        val products = results.flatMap { (_, result) ->
            // The shared budget may append a progress checkpoint and wrap otherwise
            // complete JSON as text. Parse only delivered evidence, not retained overflow.
            val parsed = when (val content = result.content) {
                is ToolResultContent.Json -> content.value
                is ToolResultContent.Text -> parseSearchPayload(content.text)
                else -> null
            }
            val payload = when (parsed) {
                is JsonObject -> parsed
                is JsonArray -> parsed.filterIsInstance<JsonObject>().firstOrNull { it["schema"] == JsonPrimitive(AmazonProducts.SCHEMA) }
                else -> null
            }
            if (result.isError || payload?.get("schema") != JsonPrimitive(AmazonProducts.SCHEMA)) {
                emptyList()
            } else {
                (payload["products"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            }
        }
        AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", AmazonProducts.SCHEMA)
                    put("provider", "Enabled Amazon plugins")
                    put("products", JsonArray(AmazonProducts.mergeProducts(products)))
                    put(
                        "providers",
                        JsonArray(
                            results.map { (provider, result) ->
                                buildJsonObject {
                                    put("name", provider.connectionName ?: provider.realToolName)
                                    put("status", if (result.isError) "unavailable" else "completed")
                                    if (result.isError) put("detail", result.content.researchText().take(500))
                                }
                            }
                        )
                    )
                    put("notice", "Product cards combine compatible enabled providers. Prices are observations and may change; use the collected results instead of repeating the same lookup.")
                }
            ),
            isError = results.all { it.second.isError },
            outputBudgetExhausted = results.any { it.second.outputBudgetExhausted },
            toolCallBudgetExhausted = results.any { it.second.toolCallBudgetExhausted }
        )
    }
}

internal fun aggregateAmazonTools(tools: List<ResolvedAgentTool>): List<ResolvedAgentTool> {
    val groups = listOf(AmazonSearchTool.SEARCH, AmazonSearchTool.GET_PRODUCTS).associateWith { name ->
        tools.filter { it.realToolName == name && it.tool.definition.inputSchema["properties"] is JsonObject }
    }.filterValues { it.isNotEmpty() }
    val children = groups.values.flatten().toSet()
    return tools.filterNot { it in children } + groups.map { (name, providers) ->
        ResolvedAgentTool(AmazonCombinedTool(providers, name), null, "Amazon plugins", name, name)
    }
}
