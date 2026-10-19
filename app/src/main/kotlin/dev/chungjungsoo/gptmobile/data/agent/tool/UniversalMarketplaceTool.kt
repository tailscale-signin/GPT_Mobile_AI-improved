package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceContract
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceSearchResult
import dev.chungjungsoo.gptmobile.data.universalmarketplace.UniversalMarketplaceCoordinator
import dev.chungjungsoo.gptmobile.data.universalmarketplace.UniversalMarketplaceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class UniversalMarketplaceTool(
    private val settings: suspend () -> UniversalMarketplaceSettings,
    private val permitted: suspend () -> Boolean
) : AgentTool {
    override val definition = definition()

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = try {
        check(permitted()) { "Marketplace access is disabled." }
        val config = settings()
        val request = MarketplaceContract.decode(arguments, config)
        val result = UniversalMarketplaceCoordinator().search(request, config, { permitted() && settings() == config })
        AgentToolResult(callId, ToolResultContent.Json(MarketplaceContract.json.encodeToJsonElement(MarketplaceSearchResult.serializer(), result)), false)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalArgumentException) {
        AgentToolResult(callId, ToolResultContent.Text("Invalid Marketplace request: ${failure.message.orEmpty().take(250)}"), true)
    } catch (_: IllegalStateException) {
        AgentToolResult(callId, ToolResultContent.Text("Marketplace permission or installation changed. Enable the plugin and retry."), true)
    }

    companion object {
        fun definition() = AgentToolDefinition(
            MarketplaceContract.TOOL,
            "Prepare user-opened Facebook Marketplace, eBay, Craigslist and Kijiji searches locally. Returns search_actions and source statuses; no listings, prices or inventory are retrieved. Filters marked pending must be set on the site. Never claim that these actions are retrieved products. Omitted preferences use visible Marketplace settings.",
            MarketplaceContract.inputSchema
        )
    }
}
