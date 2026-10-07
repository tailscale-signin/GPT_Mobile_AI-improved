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
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request

class NativeMarketplaceTool(
    private val entry: GitHubMarketplacePackage,
    override val definition: AgentToolDefinition,
    private val registry: NativeMarketplaceRegistry,
    private val fetch: suspend (Request) -> JsonElement
) : AgentTool {
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        try {
            val operation = definition.name.substringAfterLast("__")
            NativeMarketplaceCatalog.validate(entry.provider, operation, arguments)
            val configuration = registry.configuration(entry)
            val request = NativeMarketplaceRequests.build(entry, operation, arguments, configuration)
            registry.reserve(entry)
            val data = fetch(request)
            if (configuration.apiKey.isNotEmpty() && data.toString().contains(configuration.apiKey)) throw NativeMarketplaceFailure("Provider response contained credential data and was withheld.")
            val result = buildJsonObject {
                put("source", entry.preset.websiteUrl)
                put("provider", entry.preset.name)
                put("retrievedAt", Instant.now().toString())
                put("coverage", "Bounded first response; missing access, fees, opening and accessibility attributes remain unknown.")
                put("data", data)
            }
            if (result.toString().length > 100_000) throw NativeMarketplaceFailure("Result exceeds the output limit. Narrow the request.")
            return AgentToolResult(callId, ToolResultContent.Json(result), false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NativeMarketplaceFailure) {
            if (error.status == 429) registry.rateLimited(entry)
            return AgentToolResult(callId, ToolResultContent.Text(error.message.orEmpty()), true)
        } catch (_: Exception) {
            return AgentToolResult(callId, ToolResultContent.Text("Tool unavailable. Check arguments, installation, enable state, required fields and daily allowance in Marketplace."), true)
        }
    }
}
