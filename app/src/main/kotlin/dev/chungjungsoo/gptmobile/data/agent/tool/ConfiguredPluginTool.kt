package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal class ConfiguredPluginTool(private val delegate: AgentTool, settings: PluginExecutionSettings) : AgentTool {
    private val settings = settings.normalized()
    override val definition = delegate.definition
    override val managesExecutionBudget = delegate.managesExecutionBudget

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val properties = definition.inputSchema["properties"] as? JsonObject
        val countKey = if (isNamedWebSearch(definition.name, definition.description)) {
            listOf("maxResults", "max_results", "count", "numResults").firstOrNull { properties?.containsKey(it) == true }
        } else {
            null
        }
        var bounded = if (countKey != null) JsonObject(arguments + (countKey to JsonPrimitive(minOf((arguments[countKey] as? JsonPrimitive)?.intOrNull ?: settings.searchResults, settings.searchResults)))) else arguments
        if (definition.name == "read_url") {
            bounded = JsonObject(bounded + ("includeLinks" to JsonPrimitive(settings.includePageLinks)))
        }
        if (definition.name == "read_file_slice") {
            val start = (bounded["start_line"] as? JsonPrimitive)?.intOrNull ?: 1
            val end = (bounded["end_line"] as? JsonPrimitive)?.intOrNull ?: start + settings.fileExcerptLines - 1
            bounded = JsonObject(bounded + ("end_line" to JsonPrimitive(minOf(end.toLong(), start.toLong() + settings.fileExcerptLines - 1))))
        }
        if (definition.name == "device_location") {
            bounded = if (!settings.nearbyPlaces) {
                JsonObject(bounded - setOf("nearby", "place_name", "radius_meters"))
            } else {
                JsonObject(bounded + ("radius_meters" to JsonPrimitive(minOf((bounded["radius_meters"] as? JsonPrimitive)?.intOrNull ?: settings.nearbyRadiusMeters, settings.nearbyRadiusMeters))))
            }
        }
        val result = withTimeoutOrNull(settings.timeoutSeconds * 1000L) { delegate.execute(callId, bounded) }
            ?: return AgentToolResult(callId, ToolResultContent.Text("Plugin timed out after ${settings.timeoutSeconds} seconds."), true)
        val retail = (result.content as? ToolResultContent.Json)?.value as? JsonObject
        if (retail != null && AmazonProducts.text(retail, "schema") == AmazonProducts.SCHEMA) {
            val content = ToolResultContent.Json(AmazonProducts.limitResult(retail, settings.maxOutputCharacters))
            return result.copy(content = content, traceContent = content)
        }
        val text = when (val content = result.content) {
            is ToolResultContent.Text -> content.text
            is ToolResultContent.Json -> content.value.toString()
            is ToolResultContent.ResourceLinks -> return result
        }
        return if (text.length <= settings.maxOutputCharacters) {
            result
        } else {
            result.copy(
                content = ToolResultContent.Text(text.take(settings.maxOutputCharacters) + "\n[Output limited by this plugin's settings. Narrow the request for remaining content.]")
            )
        }
    }
}
