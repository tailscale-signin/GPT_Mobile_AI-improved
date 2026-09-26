package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Children must already be bound to the run's shared budget and permission gate. */
class MultiEngineSearchTool(private val engines: List<ResolvedAgentTool>) : AgentTool {
    override val managesExecutionBudget = true
    override val definition = AgentToolDefinition(
        "web_search",
        "Search every enabled web search engine in parallel. Returns deduplicated sources and each engine's status. An unavailable engine does not discard other results.",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("query", buildJsonObject { put("type", "string") })
                    put(
                        "maxResults",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 10)
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
            put("additionalProperties", false)
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = coroutineScope {
        val query = (arguments["query"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        if (query.isEmpty()) return@coroutineScope AgentToolResult(callId, ToolResultContent.Text("A search query is required."), true)
        val permits = Semaphore(4)
        val responses = engines.mapIndexed { index, engine ->
            async {
                permits.withPermit {
                    val properties = engine.tool.definition.inputSchema["properties"] as? JsonObject
                    val mapped = buildJsonObject {
                        val queryKey = if (properties?.containsKey("query") == true) "query" else "q"
                        put(queryKey, query)
                        arguments.filterKeys { it != "query" && properties?.containsKey(it) == true }.forEach { (key, value) -> put(key, value) }
                    }
                    try {
                        engine to engine.tool.execute("$callId:engine:$index", mapped)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        engine to AgentToolResult(callId, ToolResultContent.Text("Engine unavailable."), true)
                    }
                }
            }
        }.awaitAll()
        val seen = mutableSetOf<String>()
        val sources = mutableListOf<JsonElement>()
        val statuses = responses.map { (engine, result) ->
            val label = engine.connectionName ?: "Built-in search"
            val payload = when (val content = result.content) {
                is ToolResultContent.Json -> content.value
                is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) }.getOrNull()
                is ToolResultContent.ResourceLinks -> JsonArray(
                    content.links.map { link ->
                        buildJsonObject {
                            put("url", link.uri)
                            put("title", link.name.orEmpty())
                        }
                    }
                )
            }
            val extracted = if (result.isError) emptyList() else extractSources(payload)
            extracted.forEach { source ->
                val url = (source["url"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                if (seen.add(canonicalSearchUrl(url))) sources += JsonObject(source + ("engine" to JsonPrimitive(label)))
            }
            buildJsonObject {
                put("engine", label)
                put("tool", engine.realToolName)
                put("status", if (result.isError) "unavailable" else "completed")
                put("results", extracted.size)
                // MCP servers can return useful prose instead of structured sources.
                if (extracted.isEmpty()) {
                    val text = when (val content = result.content) {
                        is ToolResultContent.Text -> content.text
                        is ToolResultContent.Json -> content.value.toString()
                        is ToolResultContent.ResourceLinks -> content.links.joinToString { it.uri }
                    }
                    put("detail", text.take(6000))
                }
            }
        }
        AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("query", query)
                    put("engines", JsonArray(statuses))
                    put("results", JsonArray(sources))
                }
            ),
            isError = responses.all { it.second.isError },
            outputBudgetExhausted = responses.any { it.second.outputBudgetExhausted }
        )
    }

    private fun extractSources(value: JsonElement?): List<JsonObject> = when (value) {
        is JsonArray -> value.flatMap(::extractSources)
        is JsonObject -> if (value["url"] is JsonPrimitive) {
            listOf(value)
        } else {
            listOf("results", "data", "web", "content").flatMap { extractSources(value[it]) }
        }
        else -> emptyList()
    }
}

internal fun canonicalSearchUrl(url: String): String = runCatching {
    val uri = URI(url)
    val query = uri.rawQuery?.split('&')?.filterNot {
        val name = it.substringBefore('=').lowercase()
        name.startsWith("utm_") || name in setOf("fbclid", "gclid")
    }?.sorted()?.joinToString("&")?.takeIf { it.isNotEmpty() }
    URI(uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path.orEmpty().trimEnd('/'), query, null).toString()
}.getOrDefault(url)

internal fun ResolvedAgentTool.isWebSearchEngine(): Boolean {
    if (realToolName == "web_search") return true
    val name = realToolName.lowercase()
    val knownWebSearch = name.contains("web_search") ||
        name.contains("search_web") ||
        name in setOf("brave_search", "bing_search", "google_search", "tavily_search", "exa_search", "duckduckgo_search", "search_engine")
    if (!knownWebSearch) return false
    val schema = tool.definition.inputSchema
    val properties = schema["properties"] as? JsonObject ?: return false
    val queryKey = listOf("query", "q").firstOrNull { properties.containsKey(it) } ?: return false
    val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return required.all { it == queryKey }
}

/** Compose only after child authorization/budget wrappers have been installed. */
internal fun aggregateWebSearch(tools: List<ResolvedAgentTool>): List<ResolvedAgentTool> {
    val engines = tools.filter { it.isWebSearchEngine() }
    if (engines.isEmpty()) return tools
    val aggregate = MultiEngineSearchTool(engines)
    return tools.filterNot { it in engines } + ResolvedAgentTool(aggregate, null, "Multi-engine search", "web_search", "web_search")
}
