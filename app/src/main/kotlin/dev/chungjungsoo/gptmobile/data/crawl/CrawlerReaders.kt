package dev.chungjungsoo.gptmobile.data.crawl

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.ReadUrlTool
import dev.chungjungsoo.gptmobile.data.agent.tool.SpecialUseAddress
import dev.chungjungsoo.gptmobile.data.agent.tool.crawlerArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.pageText
import dev.chungjungsoo.gptmobile.data.agent.tool.researchPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class CrawlEngine(val id: String, val name: String, val tool: AgentTool, val allowed: suspend () -> Boolean = { true }) {
    suspend fun read(callId: String, url: String): CrawlPage {
        check(allowed()) { "Crawler engine permission changed" }
        val arguments = requireNotNull(crawlerArguments(tool.definition, url)) { "Reader has no compatible URL schema" }
        val properties = tool.definition.inputSchema["properties"] as? JsonObject
        val bounded = JsonObject(arguments + if (properties?.containsKey("includeLinks") == true) mapOf("includeLinks" to JsonPrimitive(true)) else emptyMap())
        val scoped = if (properties?.containsKey("includeDomains") == true) JsonObject(bounded + ("includeDomains" to JsonArray(listOf(JsonPrimitive(java.net.URI(url).host))))) else bounded
        val result = tool.execute(callId, scoped)
        check(!result.toolCallBudgetExhausted && !result.outputBudgetExhausted) { "BUDGET_EXHAUSTED" }
        check(!result.isError) { "${result.errorCode.orEmpty()} ${result.content.toString().take(240)}" }
        val content = result.retainedContent ?: result.content
        val payload = CrawlerCompanionEvidence.normalize(content.researchPayload(), url)
        val text = pageText(payload).ifBlank { if (payload is JsonPrimitive && payload.isString) payload.content else "" }
        check(text.isNotBlank() && !dev.chungjungsoo.gptmobile.data.agent.tool.looksLikeConsentOrScriptShell(text)) { "Reader returned no usable evidence" }
        val body = text.take(60_000)
        val objectValue = payload as? JsonObject
        val links = (objectValue?.get("links") as? JsonArray).orEmpty().mapNotNull { link ->
            (link as? JsonPrimitive)?.contentOrNull ?: ((link as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
        }
        return CrawlPage(url, (objectValue?.get("url") as? JsonPrimitive)?.contentOrNull ?: url, (objectValue?.get("title") as? JsonPrimitive)?.contentOrNull.orEmpty(), body, links.take(100), id, body != text || (objectValue?.get("truncated") as? JsonPrimitive)?.booleanOrNull == true, metadata = objectValue?.get("citationMetadata") as? JsonObject ?: JsonObject(emptyMap()))
    }
}

/** Optional proxy route: sends only an explicitly selected public URL, never cookies or chat history. */
internal class JinaCrawlerReader(private val reader: AgentTool = ReadUrlTool()) : AgentTool {
    override val definition = AgentToolDefinition(
        "jina_crawler_fetch",
        "Read a public URL through the optional anonymous Jina Reader service. Shared anonymous limits apply; no page challenge bypass is attempted.",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("url", buildJsonObject { put("type", "string") }) })
            put("required", JsonArray(listOf(JsonPrimitive("url"))))
            put("additionalProperties", false)
        }
    )
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val url = CrawlPolicy.url((arguments["url"] as? JsonPrimitive)?.content.orEmpty())
        withContext(Dispatchers.IO) {
            val addresses = java.net.InetAddress.getAllByName(java.net.URI(url).host)
            require(addresses.isNotEmpty() && addresses.none(SpecialUseAddress::isSpecialUse)) { "Jina cannot read private destinations" }
        }
        if (!reserve()) return AgentToolResult(callId, ToolResultContent.Text("Jina anonymous Reader cooldown; try after one minute or select another authorized reader"), true, errorCode = "RATE_LIMITED", dispatched = false)
        val result = reader.execute(
            callId,
            buildJsonObject {
                put("url", "https://r.jina.ai/$url")
                put("includeLinks", true)
            }
        )
        if (result.isError) {
            if (result.content.toString().contains("HTTP 429")) synchronized(requests) { blockedUntil = System.currentTimeMillis() + 60_000 }
            return result
        }
        val original = ((result.retainedContent ?: result.content) as? ToolResultContent.Json)?.value as? JsonObject ?: return result
        val body = (original["content"] as? JsonPrimitive)?.content.orEmpty()
        val links = Regex("\\]\\((https?://[^\\s)]+)\\)").findAll(body).mapNotNull { match -> runCatching { CrawlPolicy.url(match.groupValues[1]) }.getOrNull() }.distinct().take(100).toList()
        return result.copy(content = ToolResultContent.Json(JsonObject(original + mapOf("url" to JsonPrimitive(url), "reader" to JsonPrimitive("Jina Reader"), "links" to JsonArray(links.map(::JsonPrimitive))))), retainedContent = null)
    }
    companion object {
        private val requests = java.util.ArrayDeque<Long>()
        private var blockedUntil = 0L
        private fun reserve(): Boolean = synchronized(requests) {
            val now = System.currentTimeMillis()
            while (requests.isNotEmpty() && requests.first() <= now - 60_000) requests.removeFirst()
            if (now < blockedUntil || requests.size >= 20) {
                false
            } else {
                requests.addLast(now)
                true
            }
        }
    }
}
