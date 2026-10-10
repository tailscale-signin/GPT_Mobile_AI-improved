package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceFailure
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRequests
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request

class NativeMarketplaceTool(
    private val entry: GitHubMarketplacePackage,
    override val definition: AgentToolDefinition,
    private val registry: NativeMarketplaceRegistry,
    private val fetch: suspend (Request) -> JsonElement
) : AgentTool {
    private fun redactCredential(data: JsonElement, key: String): JsonElement {
        if (key.isEmpty()) return data
        val encoded = URLEncoder.encode(key, "UTF-8")
        fun redact(text: String) = text.replace(key, "[redacted]").replace(encoded, "[redacted]")
        fun visit(value: JsonElement): JsonElement = when (value) {
            is JsonArray -> JsonArray(value.map(::visit))
            is JsonObject -> JsonObject(value.entries.associate { (name, child) -> redact(name) to visit(child) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(redact(value.content)) else value
        }
        return visit(data)
    }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        try {
            val operation = definition.name.substringAfterLast("__")
            NativeMarketplaceCatalog.validate(entry.provider, operation, arguments)
            val configuration = registry.configuration(entry)
            val request = NativeMarketplaceRequests.build(entry, operation, arguments, configuration)
            registry.reserve(entry)
            val evidence = dev.chungjungsoo.gptmobile.data.marketplace.boundMarketplaceEvidence(fetch(request), configuration.installation.maxResults)
            val data = redactCredential(evidence.data, configuration.apiKey)
            val result = buildJsonObject {
                put("source", entry.preset.websiteUrl)
                put("provider", entry.preset.name)
                put("retrievedAt", Instant.now().toString())
                put("maxResults", configuration.installation.maxResults)
                put("coverage", "Bounded first response; missing access, fees, opening and accessibility attributes remain unknown.")
                put("truncated", evidence.truncated)
                if (evidence.truncated) put("continuation", "Narrow the request or retrieve details by a retained identifier. Omitted fields are unknown.")
                put("data", data)
            }
            return AgentToolResult(callId, ToolResultContent.Json(result), false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NativeMarketplaceFailure) {
            if (error.status == 429) registry.rateLimited(entry, error.retryAfterMs ?: 60_000)
            return AgentToolResult(callId, ToolResultContent.Text(error.message.orEmpty()), true)
        } catch (error: IllegalArgumentException) {
            return AgentToolResult(callId, ToolResultContent.Text("Invalid tool arguments. Check the required fields and coordinate ranges in the tool schema."), true)
        } catch (error: Exception) {
            return AgentToolResult(callId, ToolResultContent.Text(dev.chungjungsoo.gptmobile.data.marketplace.marketplaceFailureMessage(error)), true)
        }
    }
}
