package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Post-search reads inherit the parent's permissions and execution budget. */
internal class SearchCrawlStage(
    private val crawlers: List<ResolvedAgentTool>,
    private val maxPages: Int,
    private val stillEnabled: () -> Boolean = { true }
) {
    suspend fun execute(callId: String, sources: List<JsonObject>): JsonObject = coroutineScope {
        val urls = sources.mapNotNull { (it["url"] as? JsonPrimitive)?.contentOrNull }
            .filter { runCatching { URI(it).let { uri -> uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null } }.getOrDefault(false) }
            .distinctBy(::canonicalSearchUrl).take(maxPages.coerceIn(1, 20))
        val results = ConcurrentHashMap<String, JsonObject>()
        val attempted = ConcurrentHashMap.newKeySet<String>()
        val permits = Semaphore(2)
        val bounded = crawlers.map { resolved ->
            resolved.copy(
                tool = object : AgentTool {
                    override val definition = resolved.tool.definition
                    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                        if (!stillEnabled()) return AgentToolResult(callId, ToolResultContent.Text("Crawling canceled."), true)
                        val url = ((arguments["url"] ?: arguments["uri"]) as? JsonPrimitive)?.contentOrNull
                            ?: ((arguments["urls"] as? JsonArray)?.singleOrNull() as? JsonPrimitive)?.contentOrNull
                        val safe = url?.takeIf { it in urls }?.let { crawlerArguments(definition, it) }
                        if (safe == null) return AgentToolResult(callId, ToolResultContent.Text("Choose one URL from the search results with a supported page reader."), true)
                        val key = "${resolved.selectionId()}:$url"
                        if (!attempted.add(key)) return AgentToolResult(callId, ToolResultContent.Text("This page was already read in this search stage."), false)
                        val result = permits.withPermit {
                            try {
                                withTimeoutOrNull(30_000) { resolved.tool.execute(callId, safe) }
                                    ?: AgentToolResult(callId, ToolResultContent.Text("Page reader timed out."), true)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                AgentToolResult(callId, ToolResultContent.Text("Page reader unavailable."), true)
                            }
                        }
                        val text = when (val content = result.content) {
                            is ToolResultContent.Text -> content.text
                            is ToolResultContent.Json -> content.value.toString()
                            is ToolResultContent.ResourceLinks -> content.links.joinToString("\n") { it.uri }
                        }
                        results[key] = buildJsonObject {
                            put("url", url)
                            put("crawler", resolved.realToolName)
                            put("status", if (result.isError) "failed" else "completed")
                            put("content", text.take(6000))
                        }
                        return result
                    }
                }
            )
        }
        // Page retrieval is app-owned. A reviewer never receives tools or a research assignment.
        urls.flatMap { url -> bounded.map { tool -> url to tool } }.mapIndexed { index, (url, tool) ->
            async {
                if (stillEnabled()) crawlerArguments(tool.tool.definition, url)?.let { tool.tool.execute("$callId:crawl:$index", it) }
            }
        }.awaitAll()
        buildJsonObject {
            put("owner", "search")
            put("requestedPages", urls.size)
            put("results", JsonArray(results.toSortedMap().values.toList()))
            if (crawlers.isEmpty()) put("notice", "No selected crawler is available for this profile.")
        }
    }
}
