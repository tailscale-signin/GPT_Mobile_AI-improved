package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class AirbnbMcpResultTool(private val delegate: AgentTool) : AgentTool {
    override val definition = delegate.definition.copy(description = delegate.definition.description + " Listings create interactive Airbnb cards. Supply travel dates and guests for relevant pricing. Never invent fees, reviews or availability; use listing details for additional photos and facts.")
    override val managesExecutionBudget = delegate.managesExecutionBudget

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val result = delegate.execute(callId, arguments)
        if (result.isError) return result
        val payload = when (val content = result.content) {
            is ToolResultContent.Json -> content.value
            is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) }.getOrNull()
            else -> null
        } ?: return result
        val listings = AirbnbListings.normalize(payload, arguments)
        if (listings.isEmpty()) return result
        val content = ToolResultContent.Json(AirbnbListings.json(listings))
        return result.copy(content = content, traceContent = content, retainedContent = null)
    }
}
