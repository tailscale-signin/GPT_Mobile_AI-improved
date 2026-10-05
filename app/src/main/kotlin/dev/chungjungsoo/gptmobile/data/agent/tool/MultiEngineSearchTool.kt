package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Children must already be bound to the run's shared budget and permission gate. */
class MultiEngineSearchTool(private val engines: List<ResolvedAgentTool>, private val clock: Clock = Clock.systemUTC(), private val parallel: Boolean = true, private val deduplicate: Boolean = true, private val afterSearch: (suspend (String, List<JsonObject>) -> JsonObject)? = null, private val canExecute: () -> Boolean = { true }, private val remainingBytes: () -> Int = { Int.MAX_VALUE }, private val engineTimeoutMillis: Long = 20_000L) : AgentTool {
    init {
        require(engineTimeoutMillis > 0L)
    }

    private val reliability = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override val managesExecutionBudget = true
    override val definition = AgentToolDefinition(
        "web_search",
        "Run one query across all selected compatible web search engines with bounded concurrency and shared permissions/budgets. Returns deduplicated sources and each engine's status. maxResults limits each engine. A failed or slow engine does not discard successful results.",
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
                    for (name in listOf("includeDomains", "excludeDomains")) {
                        put(
                            name,
                            buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            }
                        )
                    }
                    put(
                        "recencyDays",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
            put("additionalProperties", false)
        }
    )

    // Per-turn single flight. Fixed lock stripes avoid retaining a mutex for every
    // distinct query ever attempted; only successful evidence enters the bounded cache.
    private val resultCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AgentToolResult>>()
    private val queryLocks = Array(32) { Mutex() }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        if (!deduplicate) return search(callId, arguments)
        val key = JsonObject(arguments.toSortedMap()).toString()
        return queryLocks[(key.hashCode() and Int.MAX_VALUE) % queryLocks.size].withLock {
            val now = clock.millis()
            resultCache[key]?.takeIf { now - it.first in 0..30_000L }?.let {
                return@withLock it.second.copy(callId = callId, sharedResult = true)
            }
            val result = search(callId, arguments)
            if (!result.isError &&
                !result.outputBudgetExhausted &&
                !result.toolCallBudgetExhausted &&
                (result.content as? ToolResultContent.Json)?.value?.let { extractSearchSources(it).isNotEmpty() } == true
            ) {
                resultCache.entries.removeIf { now - it.value.first > 30_000L }
                if (resultCache.size >= 32) resultCache.keys.firstOrNull()?.let(resultCache::remove)
                resultCache[key] = clock.millis() to result
            }
            result
        }
    }

    private suspend fun search(callId: String, arguments: JsonObject): AgentToolResult = coroutineScope {
        val query = (arguments["query"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().trim()
        if (query.isEmpty()) return@coroutineScope AgentToolResult(callId, ToolResultContent.Text("A search query is required."), true)
        fun integer(name: String) = (arguments[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val maxResults = if (arguments.containsKey("maxResults")) integer("maxResults") else 10
        val recencyDays = integer("recencyDays")
        val domainNames = listOf("includeDomains", "excludeDomains")
        val validDomains = domainNames.all { name ->
            !arguments.containsKey(name) || (arguments[name] as? JsonArray)?.all { it is JsonPrimitive && it.isString } == true
        }
        if (maxResults == null ||
            maxResults !in 1..10 ||
            !validDomains ||
            (arguments.containsKey("recencyDays") && (recencyDays == null || recencyDays < 0))
        ) {
            return@coroutineScope AgentToolResult(callId, ToolResultContent.Text("Invalid search filters or result count. Use 1–10 results, nonnegative recency days, and domain lists."), true)
        }
        fun domains(name: String) = (arguments[name] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content.trim() }
        val includeDomains = domains("includeDomains")
        val excludeDomains = domains("excludeDomains")
        if (includeDomains.isNotEmpty() && excludeDomains.isNotEmpty()) {
            return@coroutineScope AgentToolResult(callId, ToolResultContent.Text("Use either included or excluded domains."), true)
        }
        if (!(includeDomains + excludeDomains).all(::isSearchDomain) ||
            (recencyDays != null && runCatching { braveSearchFreshness(recencyDays, clock) }.isFailure)
        ) {
            return@coroutineScope AgentToolResult(callId, ToolResultContent.Text("Use valid host names and a recency within the supported calendar range."), true)
        }
        // Do not silently drop engine three and later. Each selected child still
        // passes its existing consent and shared-budget wrapper before execution.
        val selected = engines.distinctBy { it.selectionId() }
            .sortedByDescending { reliability[it.modelToolName]?.get() ?: 0 }
        val permits = Semaphore(if (parallel) 3 else 1)
        val responses = selected.mapIndexed { index, engine ->
            async {
                permits.withPermit {
                    try {
                        if ((blockedUntil[engine.modelToolName] ?: 0L) > clock.millis()) {
                            return@withPermit engine to AgentToolResult(callId, ToolResultContent.Text("Engine cooling down after an authentication or subscription failure."), true)
                        }
                        if (!canExecute() || remainingBytes() < 1024) {
                            return@withPermit engine to AgentToolResult(callId, ToolResultContent.Text("Search skipped: insufficient remaining run budget."), true, outputBudgetExhausted = true)
                        }
                        val adapter = requireNotNull(WebSearchEngineAdapter.forTool(engine.realToolName, engine.tool.definition))
                        val mapped = adapter.arguments(arguments, clock)
                        val result = withTimeoutOrNull(engineTimeoutMillis) {
                            engine.tool.execute("$callId:engine:$index", mapped)
                        } ?: AgentToolResult(callId, ToolResultContent.Text("Engine timed out; other engine results remain available."), true)
                        reliability.getOrPut(engine.modelToolName) { java.util.concurrent.atomic.AtomicInteger() }
                            .updateAndGet { (it + if (result.isError) -1 else 1).coerceIn(-20, 20) }
                        val detail = result.content.toString()
                        if (result.isError && Regex("HTTP (401|402|403)").containsMatchIn(detail)) {
                            blockedUntil[engine.modelToolName] = clock.millis() + 5 * 60_000L
                        }
                        engine to result
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        engine to AgentToolResult(callId, ToolResultContent.Text("Engine unavailable."), true)
                    }
                }
            }
        }.awaitAll()
        val sourceIndexes = mutableMapOf<String, Int>()
        val sources = mutableListOf<JsonElement>()
        val statuses = responses.map { (engine, result) ->
            val label = engine.connectionName ?: "Built-in search"
            val payload = when (val content = result.content) {
                is ToolResultContent.Json -> content.value
                is ToolResultContent.Text -> parseSearchPayload(content.text)
                is ToolResultContent.ResourceLinks -> JsonArray(
                    content.links.map { link ->
                        buildJsonObject {
                            put("url", link.uri)
                            put("title", link.name.orEmpty())
                        }
                    }
                )
            }
            val rawSources = if (result.isError) emptyList() else extractSearchSources(payload)
            val extracted = rawSources
                .filter { source -> matchesSearchDomains((source["url"] as? JsonPrimitive)?.contentOrNull.orEmpty(), includeDomains, excludeDomains) }
                .take(maxResults)
            extracted.forEach { source ->
                val url = (source["url"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val key = canonicalSearchUrl(url)
                val previous = sourceIndexes[key].takeIf { deduplicate }
                if (previous == null) {
                    sourceIndexes[key] = sources.size
                    sources += JsonObject(source + mapOf("engine" to JsonPrimitive(label), "engines" to JsonArray(listOf(JsonPrimitive(label)))))
                } else {
                    val existing = sources[previous] as JsonObject
                    val labels = ((existing["engines"] as? JsonArray).orEmpty() + JsonPrimitive(label)).distinct()
                    sources[previous] = JsonObject(existing + ("engines" to JsonArray(labels)))
                }
            }
            buildJsonObject {
                put("engine", label)
                put("tool", engine.realToolName)
                put("status", if (result.isError) "unavailable" else "completed")
                put("results", extracted.size)
                if (recencyDays != null && WebSearchEngineAdapter.forTool(engine.realToolName, engine.tool.definition)?.supportsRecency == false) {
                    put("unsupportedFilters", JsonArray(listOf(JsonPrimitive("recencyDays"))))
                }
                // MCP servers can return useful prose instead of structured sources.
                if (extracted.isEmpty()) {
                    val text = when (val content = result.content) {
                        is ToolResultContent.Text -> content.text
                        is ToolResultContent.Json -> content.value.toString()
                        is ToolResultContent.ResourceLinks -> content.links.joinToString { it.uri }
                    }
                    put("detail", if (!result.isError && rawSources.isNotEmpty()) "No sources matched the search filters." else text.take(6000))
                }
            }
        }
        // Crawling is an optional enrichment: it must never erase successful search
        // evidence. External cancellation still stops the entire operation promptly.
        val crawl = if (afterSearch != null && sources.isNotEmpty()) {
            try {
                withTimeoutOrNull(30_000L) { afterSearch.invoke(callId, sources.filterIsInstance<JsonObject>()) }
                    ?: crawlUnavailable("Page enrichment timed out; search results were preserved.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                crawlUnavailable("Page enrichment failed; search results were preserved.")
            }
        } else null
        AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("query", query)
                    put("engines", JsonArray(statuses))
                    put("results", JsonArray(sources))
                    if (selected.isEmpty()) put("notice", "No selected compatible search engines are available.")
                    crawl?.let { put("pages", it) }
                }
            ),
            isError = responses.all { it.second.isError },
            outputBudgetExhausted = responses.any { it.second.outputBudgetExhausted },
            toolCallBudgetExhausted = responses.any { it.second.toolCallBudgetExhausted },
            toolCallBudgetUsed = responses.mapNotNull { it.second.toolCallBudgetUsed }.maxOrNull(),
            toolCallBudgetLimit = responses.mapNotNull { it.second.toolCallBudgetLimit }.maxOrNull(),
            toolCallBudgetConfigured = responses.mapNotNull { it.second.toolCallBudgetConfigured }.maxOrNull(),
            toolCallBudgetReserved = responses.mapNotNull { it.second.toolCallBudgetReserved }.maxOrNull(),
            toolResultBudgetUsedBytes = responses.mapNotNull { it.second.toolResultBudgetUsedBytes }.maxOrNull(),
            toolResultBudgetLimitBytes = responses.mapNotNull { it.second.toolResultBudgetLimitBytes }.maxOrNull()
        )
    }

    private fun crawlUnavailable(message: String): JsonObject = buildJsonObject {
        put("owner", "search")
        put("status", "unavailable")
        put("notice", message)
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

/** Compose only after child authorization/budget wrappers have been installed. */
internal fun aggregateWebSearch(tools: List<ResolvedAgentTool>, parallel: Boolean = true, deduplicate: Boolean = true, afterSearch: (suspend (String, List<JsonObject>) -> JsonObject)? = null, canExecute: () -> Boolean = { true }, remainingBytes: () -> Int = { Int.MAX_VALUE }): List<ResolvedAgentTool> {
    val engines = tools.filter { it.isWebSearchEngine() }
    if (engines.isEmpty()) return tools
    val aggregate = MeasuredAgentTool(MultiEngineSearchTool(engines, parallel = parallel, deduplicate = deduplicate, afterSearch = afterSearch, canExecute = canExecute, remainingBytes = remainingBytes))
    return tools.filterNot { it in engines } + ResolvedAgentTool(aggregate, null, "Multi-engine search", "web_search", "web_search")
}
