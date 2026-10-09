package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Children retain their bound authorization/budget wrappers. The legacy parallel argument no longer selects sequential execution. */
@Suppress("UNUSED_PARAMETER")
class MultiEngineSearchTool(
    engines: List<ResolvedAgentTool>,
    private val clock: Clock = Clock.systemUTC(),
    parallel: Boolean = true,
    deduplicate: Boolean = true,
    private val afterSearch: (suspend (String, List<JsonObject>) -> JsonObject)? = null,
    private val canExecute: () -> Boolean = { true },
    private val remainingBytes: () -> Int = { Int.MAX_VALUE },
    private val engineTimeoutMillis: Long = 8_000L,
    private val searchTimeoutMillis: Long = 12_000L,
    private val policy: SearchMergePolicy = SearchMergePolicy(dedupeUrls = deduplicate),
    private val ownerRoute: String = "client",
    private val configurationRevision: suspend () -> String = { "" },
    private val nanoTime: () -> Long = System::nanoTime
) : AgentTool {
    private val selected = engines.distinctBy { it.selectionId() }.toList()
    init {
        require(engineTimeoutMillis > 0L && searchTimeoutMillis > 0L)
        require(selected.map { it.tool.executionOwner() }.distinct().size <= 1) { "Search engines must have one execution owner." }
    }

    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override val managesExecutionBudget = true
    override val definition = AgentToolDefinition(
        "web_search",
        "Search all selected compatible engines concurrently with shared permissions/budgets. Sources are interleaved in selected-engine order and retain attribution. maxResults is the per-engine contribution cap (1–10). totalResults is the final source count (1–50); request 20 for a new search. If omitted, the legacy per-engine ceiling applies. Slow or failed engines do not discard sibling evidence.",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("query", buildJsonObject { put("type", "string") })
                    for ((name, maximum) in listOf("maxResults" to 10, "totalResults" to 50)) {
                        put(
                            name,
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", maximum)
                            }
                        )
                    }
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

    private val resultCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AgentToolResult>>()
    private val queryLocks = Array(32) { Mutex() }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        if (!policy.reuseIdenticalRequests) return search(callId, arguments)
        val key = "$ownerRoute|${selected.joinToString { it.selectionId() }}|$policy|${configurationRevision()}|${JsonObject(arguments.toSortedMap())}"
        return queryLocks[(key.hashCode() and Int.MAX_VALUE) % queryLocks.size].withLock {
            val now = clock.millis()
            val reusable = selected.all { it.canReuseResult?.invoke() ?: (it.tool !is PreparableAgentTool) }
            if (reusable) {
                resultCache[key]?.takeIf { now - it.first in 0..30_000L }?.let {
                    return@withLock it.second.copy(callId = callId, sharedResult = true)
                }
            } else {
                resultCache.clear()
            }
            val result = search(callId, arguments)
            val partial = (((result.content as? ToolResultContent.Json)?.value as? JsonObject)?.get("merge") as? JsonObject)?.get("partial") == JsonPrimitive(true)
            if (reusable &&
                !partial &&
                !result.isError &&
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

    private data class Response(val result: AgentToolResult, val outcome: String, val effectiveCount: Int)
    private data class Prepared(val invoke: suspend () -> AgentToolResult, val effectiveCount: Int)

    private suspend fun search(callId: String, arguments: JsonObject): AgentToolResult = supervisorScope {
        fun invalid(message: String) = AgentToolResult(callId, ToolResultContent.Text(message), true)
        val query = (arguments["query"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().trim()
        if (query.isEmpty()) return@supervisorScope invalid("A search query is required.")
        fun integer(name: String) = (arguments[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val maxResults = if (arguments.containsKey("maxResults")) integer("maxResults") else 10
        val totalResults = if (arguments.containsKey("totalResults")) integer("totalResults") else null
        val recencyDays = integer("recencyDays")
        val validDomains = listOf("includeDomains", "excludeDomains").all { name ->
            !arguments.containsKey(name) || (arguments[name] as? JsonArray)?.all { it is JsonPrimitive && it.isString } == true
        }
        if (maxResults == null ||
            maxResults !in 1..10 ||
            (arguments.containsKey("totalResults") && (totalResults == null || totalResults !in 1..50)) ||
            !validDomains ||
            (arguments.containsKey("recencyDays") && (recencyDays == null || recencyDays < 0))
        ) {
            return@supervisorScope invalid("Invalid search filters/count. Use maxResults 1–10, totalResults 1–50, nonnegative recency days, and domain lists.")
        }
        fun domains(name: String) = (arguments[name] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content.trim() }
        val includes = domains("includeDomains")
        val excludes = domains("excludeDomains")
        if (includes.isNotEmpty() && excludes.isNotEmpty()) return@supervisorScope invalid("Use either included or excluded domains.")
        if (!(includes + excludes).all(::isSearchDomain) || (recencyDays != null && runCatching { braveSearchFreshness(recencyDays, clock) }.isFailure)) {
            return@supervisorScope invalid("Use valid host names and a recency within the supported calendar range.")
        }
        val target = totalResults ?: (selected.size * maxResults)
        val fetchRequest = JsonObject(arguments - "totalResults" + ("maxResults" to JsonPrimitive(policy.fetchLimitPerEngine)))
        fun failure(id: String, outcome: String, message: String, budget: Boolean = false) = Response(AgentToolResult(id, ToolResultContent.Text(message), true, outputBudgetExhausted = budget), outcome, 0)
        suspend fun prepare(index: Int, request: JsonObject, refill: Boolean): Prepared {
            val engine = selected[index]
            val id = "$callId:engine:$index${if (refill) ":unique" else ""}"
            val unavailable = when {
                (blockedUntil[engine.selectionId()] ?: 0L) > clock.millis() -> failure(id, "cooldown", "Engine cooling down after an authentication/subscription failure.")
                !canExecute() || remainingBytes() < 1024 -> failure(id, "budget_exhausted", "Search skipped: insufficient remaining run budget.", true)
                else -> null
            }
            if (unavailable != null) return Prepared({ unavailable.result }, 0)
            return try {
                val adapter = requireNotNull(WebSearchEngineAdapter.forTool(engine.realToolName, engine.tool.definition))
                val mapped = if (refill) requireNotNull(adapter.nextPage(request, clock)) else adapter.arguments(request, clock)
                val count = adapter.effectiveCount(policy.fetchLimitPerEngine)
                val invoke: suspend () -> AgentToolResult = (engine.tool as? PreparableAgentTool)?.prepareExecution(id, mapped) ?: { engine.tool.execute(id, mapped) }
                Prepared(invoke, count)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                Prepared({ failure(id, "invalid_request", invalid.message ?: "Provider request is invalid.").result }, 0)
            } catch (_: Exception) {
                Prepared({ failure(id, "failed", "Engine unavailable.").result }, 0)
            }
        }
        // Serial permission preflight is outside the provider deadline; each reservation still belongs to the bound child.
        val prepared = selected.indices.map { prepare(it, fetchRequest, false) }
        val started = nanoTime()
        fun remainingMillis() = (searchTimeoutMillis - (nanoTime() - started) / 1_000_000L).coerceAtLeast(0)
        suspend fun attempt(index: Int, ready: Prepared, refill: Boolean): Response {
            currentCoroutineContext().ensureActive()
            val id = "$callId:engine:$index${if (refill) ":unique" else ""}"
            val allowance = minOf(engineTimeoutMillis, remainingMillis())
            if (allowance <= 0) return failure(id, "timeout", "Search phase deadline reached; other sources remain available.")
            val result = try {
                withTimeoutOrNull(allowance) { ready.invoke() } ?: return failure(id, "timeout", "Engine timed out; sibling results remain available.")
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                return failure(id, "timeout", "Engine timed out; sibling results remain available.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failure(id, "failed", "Engine unavailable.")
            }
            val text = resultText(result)
            val outcome = when {
                result.outputBudgetExhausted || result.toolCallBudgetExhausted -> "budget_exhausted"
                result.isError && text.contains("permission", true) -> "denied"
                result.isError && Regex("(?i)cooling down").containsMatchIn(text) -> "cooldown"
                result.isError && Regex("(?i)invalid|exceeds.*limit").containsMatchIn(text) -> "invalid_request"
                result.isError && Regex("(?i)401|402|403|authenticat|requires an api key|api key required").containsMatchIn(text) -> "authentication_failed"
                result.isError && Regex("(?i)429|rate.?limit").containsMatchIn(text) -> "rate_limited"
                result.isError && text.contains("timed out", true) -> "timeout"
                result.isError -> "failed"
                else -> searchEvidenceOutcome(result)
            }
            if (outcome == "authentication_failed") blockedUntil[selected[index].selectionId()] = clock.millis() + 5 * 60_000L
            return Response(if (outcome == "malformed_response") result.copy(isError = true) else result, outcome, ready.effectiveCount)
        }
        val initial = selected.indices.map { index -> async { attempt(index, prepared[index], false) } }.awaitAll()
        val allAttempts = initial.toMutableList()
        fun candidates(index: Int, response: Response): List<SearchCandidate> {
            if (response.result.isError) return emptyList()
            return resultSources(response.result).asSequence().filter { source ->
                matchesSearchDomains((source["url"] as? JsonPrimitive)?.contentOrNull.orEmpty(), includes, excludes)
            }.mapIndexedNotNull { rank, source -> SearchCandidate.create(source, selected[index].selectionId(), index, selected[index].connectionName ?: "Built-in search", rank + 1) }
                .take(response.effectiveCount.coerceAtLeast(0)).toList()
        }
        val buckets = withContext(Dispatchers.Default) {
            initial.mapIndexed { index, response ->
                currentCoroutineContext().ensureActive()
                candidates(index, response)
            }.toMutableList()
        }
        suspend fun merge(): SearchMergeResult = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            SearchResultMerger.merge(buckets, maxResults, target, policy) { context.ensureActive() }
        }
        var merged = merge()
        val refillResponses = mutableMapOf<Int, Response>()
        if (merged.sources.size < target && remainingMillis() > 0 && canExecute() && remainingBytes() >= 1024) {
            val eligible = selected.indices.filter { index ->
                val response = initial[index]
                !response.result.isError &&
                    !response.result.outputBudgetExhausted &&
                    !response.result.toolCallBudgetExhausted &&
                    response.effectiveCount > 0 &&
                    resultSources(response.result).size >= response.effectiveCount &&
                    WebSearchEngineAdapter.forTool(selected[index].realToolName, selected[index].tool.definition)?.nextPage(fetchRequest, clock) != null
            }
            val next = eligible.map { index -> index to prepare(index, fetchRequest, true) }
            next.map { (index, ready) -> async { index to attempt(index, ready, true) } }.awaitAll().forEach { (index, response) ->
                refillResponses[index] = response
                allAttempts += response
                buckets[index] = buckets[index] + withContext(Dispatchers.Default) { candidates(index, response) }
            }
            if (next.isNotEmpty()) merged = merge()
        }
        val searchDuration = ((nanoTime() - started) / 1_000_000L).coerceAtLeast(0)
        val statuses = initial.mapIndexed { index, response ->
            buildJsonObject {
                put("engine", selected[index].connectionName ?: "Built-in search")
                (resultPayload(response.result) as? JsonObject)?.get("engines")?.let { put("engines", it) }
                put("tool", selected[index].realToolName)
                put("status", if (response.result.isError) "unavailable" else "completed")
                put("outcome", response.outcome)
                put("results", buckets[index].size)
                put("requestedCount", policy.fetchLimitPerEngine)
                put("effectiveCount", response.effectiveCount)
                put("uniqueResults", merged.uniqueGroups[index] ?: 0)
                put("contributedResults", merged.contributions[index] ?: 0)
                put("duplicateResults", merged.duplicatesByOwner[index] ?: 0)
                if (response.result.isError || buckets[index].isEmpty()) put("detail", resultText(response.result).take(6000))
                if (recencyDays != null && WebSearchEngineAdapter.forTool(selected[index].realToolName, selected[index].tool.definition)?.supportsRecency == false) put("unsupportedFilters", JsonArray(listOf(JsonPrimitive("recencyDays"))))
                refillResponses[index]?.let { extra ->
                    put(
                        "refill",
                        buildJsonObject {
                            put("outcome", extra.outcome)
                            put("outputBudgetExhausted", extra.result.outputBudgetExhausted)
                            put("toolCallBudgetExhausted", extra.result.toolCallBudgetExhausted)
                        }
                    )
                }
                put("outputBudgetExhausted", response.result.outputBudgetExhausted)
                put("toolCallBudgetExhausted", response.result.toolCallBudgetExhausted)
            }
        }
        val crawlStarted = nanoTime()
        val crawl = if (afterSearch != null && merged.sources.isNotEmpty()) {
            try {
                withTimeoutOrNull(30_000L) { afterSearch.invoke(callId, merged.sources) } ?: crawlUnavailable("Page enrichment timed out; search results were preserved.")
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                crawlUnavailable("Page enrichment timed out; search results were preserved.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                crawlUnavailable("Page enrichment failed; search results were preserved.")
            }
        } else {
            null
        }
        val crawlDuration = if (crawl == null) 0L else ((nanoTime() - crawlStarted) / 1_000_000L).coerceAtLeast(0)
        fun envelope(sources: List<JsonObject>) = buildJsonObject {
            put("query", query)
            put("schemaVersion", 2)
            put("engines", JsonArray(statuses))
            put("results", JsonArray(sources))
            if (selected.isEmpty()) {
                put("outcome", "no_engines")
                put("notice", "No selected compatible search engines are available.")
            }
            put(
                "merge",
                buildJsonObject {
                    put("policyVersion", policy.version)
                    put("candidateCount", merged.candidateCount)
                    put("urlDuplicates", merged.urlDuplicates)
                    put("contentDuplicates", merged.contentDuplicates)
                    put("shadowContentDuplicates", merged.shadowContentDuplicates)
                    put("contentMode", policy.contentMode.name.lowercase())
                    put("degradedContentChecking", merged.degradedContentChecking)
                    put("returnedCount", merged.sources.size)
                    put("partial", allAttempts.any { it.result.isError || it.result.outputBudgetExhausted || it.result.toolCallBudgetExhausted })
                    put("searchDurationMs", searchDuration)
                    put("crawlDurationMs", crawlDuration)
                }
            )
            crawl?.let { put("pages", it) }
        }
        val content = ToolResultContent.Json(envelope(merged.sources))
        val retained = ToolResultContent.Json(envelope(merged.retainedSources))
        AgentToolResult(
            callId, content,
            isError = initial.all { it.result.isError },
            retainedContent = retained.takeUnless { it == content },
            outputBudgetExhausted = allAttempts.any { it.result.outputBudgetExhausted },
            toolCallBudgetExhausted = allAttempts.any { it.result.toolCallBudgetExhausted },
            toolCallBudgetUsed = allAttempts.mapNotNull { it.result.toolCallBudgetUsed }.maxOrNull(),
            toolCallBudgetLimit = allAttempts.mapNotNull { it.result.toolCallBudgetLimit }.maxOrNull(),
            toolCallBudgetConfigured = allAttempts.mapNotNull { it.result.toolCallBudgetConfigured }.maxOrNull(),
            toolCallBudgetReserved = allAttempts.mapNotNull { it.result.toolCallBudgetReserved }.maxOrNull(),
            toolResultBudgetUsedBytes = allAttempts.mapNotNull { it.result.toolResultBudgetUsedBytes }.maxOrNull(),
            toolResultBudgetLimitBytes = allAttempts.mapNotNull { it.result.toolResultBudgetLimitBytes }.maxOrNull()
        )
    }

    private fun crawlUnavailable(message: String) = buildJsonObject {
        put("owner", "search")
        put("status", "unavailable")
        put("notice", message)
    }
    private fun resultText(result: AgentToolResult): String = when (val value = result.content) {
        is ToolResultContent.Text -> value.text
        is ToolResultContent.Json -> value.value.toString()
        is ToolResultContent.ResourceLinks -> value.links.joinToString { it.uri }
    }
    private fun resultPayload(result: AgentToolResult): JsonElement = when (val value = result.content) {
        is ToolResultContent.Json -> value.value
        is ToolResultContent.Text -> parseSearchPayload(value.text)
        is ToolResultContent.ResourceLinks -> JsonArray(
            value.links.map {
                buildJsonObject {
                    put("url", it.uri)
                    put("title", it.name.orEmpty())
                }
            }
        )
    }
    private fun resultSources(result: AgentToolResult) = extractSearchSources(resultPayload(result))

    private fun searchEvidenceOutcome(result: AgentToolResult): String {
        val sources = resultSources(result)
        if (sources.isNotEmpty()) return if (sources.any { SearchUrlIdentity.parse((it["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()) != null }) "success" else "malformed_response"
        val text = (result.content as? ToolResultContent.Text)?.text?.trim()
        if (text != null && (text.isEmpty() || (text.first() in setOf('{', '[') && runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.isFailure && (resultPayload(result) as? JsonArray)?.isEmpty() == true))) return "malformed_response"
        if (text != null && text.firstOrNull() !in setOf('{', '[')) return "unstructured"
        fun emptyEnvelope(value: JsonElement?, depth: Int = 0): Boolean {
            if (depth > 8) return false
            return when (value) {
                is JsonArray -> value.isEmpty() || value.any { emptyEnvelope(it, depth + 1) }
                is JsonObject -> listOf("results", "sources", "web", "data", "organic", "organic_results", "search_results", "items", "result", "structuredContent").any { emptyEnvelope(value[it], depth + 1) }
                else -> false
            }
        }
        return if (emptyEnvelope(resultPayload(result))) "empty" else "unstructured"
    }
}

private fun AgentTool.executionOwner() = (this as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

/** Gateway-owned engines stay on their existing route; only client-owned children enter this aggregate. */
internal fun aggregateWebSearch(
    tools: List<ResolvedAgentTool>,
    parallel: Boolean = true,
    deduplicate: Boolean = true,
    afterSearch: (suspend (String, List<JsonObject>) -> JsonObject)? = null,
    canExecute: () -> Boolean = { true },
    remainingBytes: () -> Int = { Int.MAX_VALUE },
    policy: SearchMergePolicy = SearchMergePolicy(dedupeUrls = deduplicate),
    ownerRoute: String = "client",
    configurationRevision: suspend () -> String = { "" }
): List<ResolvedAgentTool> {
    val available = aggregateAmazonTools(tools)
    val engines = available.filter { it.isWebSearchEngine() && it.tool.executionOwner() == AgentToolExecutionOwner.CLIENT }
    if (engines.isEmpty()) return available
    val aggregate = MeasuredAgentTool(MultiEngineSearchTool(engines, parallel = parallel, afterSearch = afterSearch, canExecute = canExecute, remainingBytes = remainingBytes, policy = policy, ownerRoute = ownerRoute, configurationRevision = configurationRevision))
    return available.filterNot { it in engines } + ResolvedAgentTool(aggregate, null, "Multi-engine search", "web_search", "web_search")
}
