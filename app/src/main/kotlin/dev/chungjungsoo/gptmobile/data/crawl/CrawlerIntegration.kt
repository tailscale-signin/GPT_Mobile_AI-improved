package dev.chungjungsoo.gptmobile.data.crawl

import android.content.Context
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.ReadUrlTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.crawlerArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.selectionId
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CrawlerIntegration {
    fun attach(context: Context, scope: CrawlScope, features: AppFeatureSettings, tools: List<ResolvedAgentTool>, allowed: suspend () -> Boolean): List<ResolvedAgentTool> {
        if (!features.isToolPluginEnabledForProfile(scope.profileUid, ToolPluginId.CRAWLERS)) return tools
        val config = (features.pluginExecution[ToolPluginId.CRAWLERS] ?: dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings()).normalized()
        val store = CrawlStore.forDirectory(context.filesDir)
        val native = ReadUrlTool(documentContext = context)
        val cached = CachedPageReader(store, scope, native, allowed)
        val selected = features.profileBehavior[scope.profileUid]?.crawlerToolIds.orEmpty()
        val engines = buildList {
            if (config.crawlerNative && ":read_url" in selected) add(CrawlEngine("native", "Native HTML / PDF / Office", cached, allowed))
            if (config.crawlerJina && ":jina_crawler_fetch" in selected) add(CrawlEngine("jina", "Jina Reader", JinaCrawlerReader(), allowed))
            tools.filter { it.selectionId() in selected && it.shareableReadOnly && it.canReuseResult != null && crawlerArguments(it.tool.definition, "https://example.org/") != null }.forEach { resolved ->
                add(CrawlEngine(resolved.selectionId(), resolved.connectionName ?: resolved.realToolName, resolved.tool) { allowed() && resolved.canReuseResult?.invoke() == true })
            }
        }.distinctBy { it.id }.take(8)
        val controller = MultiCrawlerTool(store, scope, engines, ReadUrlTool(documentContext = context, preserveLineBreaks = true), config.crawlerMaxPages, config.crawlerMaxDepth, allowed)
        return tools.map { if (it.connectionUid == null && it.realToolName == "read_url") it.copy(tool = cached) else it } +
            ResolvedAgentTool(controller, null, "Crawlers", "crawler", "crawler", canReuseResult = allowed) +
            if (config.crawlerJina && ":jina_crawler_fetch" in selected) listOf(ResolvedAgentTool(JinaCrawlerReader(), null, "Jina Reader · optional URL sharing", "jina_crawler_fetch", "jina_crawler_fetch", canReuseResult = allowed)) else emptyList()
    }
}

/** Web Fetch and managed crawling reuse the same captured source within the same permission scope. */
internal class CachedPageReader(private val store: CrawlStore, private val scope: CrawlScope, private val delegate: AgentTool, private val allowed: suspend () -> Boolean) : AgentTool {
    override val definition = delegate.definition
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        check(allowed()) { "Crawler evidence permission changed" }
        // Domain/document options and malformed arguments must go through the reader's own validation.
        // In particular a cached response cannot skip an explicit include/exclude-domain restriction.
        if (arguments.keys.any { it !in setOf("url", "includeLinks") } ||
            (arguments["url"] as? JsonPrimitive)?.isString != true ||
            ("includeLinks" in arguments && arguments["includeLinks"] !in setOf(JsonPrimitive(true), JsonPrimitive(false)))
        ) {
            return delegate.execute(callId, arguments)
        }
        val url = runCatching { CrawlPolicy.url((arguments["url"] as? JsonPrimitive)?.content.orEmpty()) }.getOrNull()
            ?: return delegate.execute(callId, arguments)
        val cached = store.cached(scope, url)
        if (cached != null && !cached.truncated) {
            return AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("citationMetadata", cached.metadata)
                        put("title", cached.title)
                        put("url", cached.finalUrl)
                        put("content", cached.content)
                        put("sourceId", cached.sourceId)
                        put("retrievedAt", cached.retrievedAt)
                        put("truncated", false)
                        put("reader", cached.engine)
                        put("links", JsonArray(cached.links.map(::JsonPrimitive)))
                    }
                ),
                false,
                sharedResult = true,
                dispatched = false
            )
        }
        val result = delegate.execute(callId, JsonObject(arguments + ("includeLinks" to JsonPrimitive(true))))
        if (result.isError) return result
        val parsed = (result.retainedContent ?: result.content) as? ToolResultContent.Json ?: return result
        val payload = parsed.value as? JsonObject ?: return result
        val content = (payload["content"] as? JsonPrimitive)?.content ?: return result
        if (content.toByteArray().size > 256_000) return result
        val page = CrawlPage(url, (payload["url"] as? JsonPrimitive)?.content ?: url, content = content, links = (payload["links"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }, engine = "native", truncated = payload["truncated"] == JsonPrimitive(true), metadata = payload["citationMetadata"] as? JsonObject ?: JsonObject(emptyMap()))
        val id = crawlHash("${scope.evidenceScope}:fetch:$url")
        store.update(id) { it ?: CrawlJob(id, scope, listOf("native"), "quick", 1, 0, setOf(java.net.URI(url).host), emptyList()) }
        store.commitPage(id, page) { it.copy(state = "COMPLETED", pageIds = listOf(page.sourceId), visited = setOf(url)) }
        return result
    }
}
