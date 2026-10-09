package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListing
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirbnbMcpResultToolTest {
    private fun tool(result: AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition("airbnb_search", "Search stays", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject) = result.copy(callId = callId)
    }

    @Test fun stdioTextResultsBecomeTypedCardsWithTheRequestedTravelContext() = runBlocking {
        val raw = """{"searchResults":[{"id":"123","name":"Lake cottage","photos":["https://a0.muscache.com/im/pictures/a.jpg"],"price":"CAD 100 nightly"}]}"""
        val adapter = AirbnbMcpResultTool(tool(AgentToolResult("call", ToolResultContent.Text(raw), false)))
        val result = adapter.execute(
            "search",
            buildJsonObject {
                put("checkin", "2026-11-01")
                put("checkout", "2026-11-05")
                put("adults", 2)
                put("children", 1)
            }
        )
        assertFalse(result.isError)
        val payload = (result.content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive(AirbnbListings.SCHEMA), payload["schema"])
        val listing = AirbnbListings.normalize(payload).single()
        assertEquals(4, listing.nights)
        assertEquals(1, listing.children)
        assertEquals(1, listing.photos.size)
    }

    @Test fun boundedModelOutputPreservesFullCardsForChatAndDoesNotTruncateJson() = runBlocking {
        val full = AirbnbListings.json((1..5).map { AirbnbListing(it.toString(), "Cottage $it", "https://www.airbnb.com/rooms/$it", description = "Long detail ".repeat(500)) })
        val configured = ConfiguredPluginTool(tool(AgentToolResult("call", ToolResultContent.Json(full), false)), PluginExecutionSettings(maxOutputCharacters = 1000))
        val result = configured.execute("limited", JsonObject(emptyMap()))
        val content = (result.content as ToolResultContent.Json).value as JsonObject
        assertTrue(content.toString().length <= 1000)
        assertEquals(JsonPrimitive(AirbnbListings.SCHEMA), content["schema"])
        assertEquals(JsonPrimitive(true), content["outputLimited"])
        assertEquals(full, (result.retainedContent as ToolResultContent.Json).value)
    }

    @Test fun providerFailureIsPreservedRatherThanInventingListings() = runBlocking {
        val original = AgentToolResult("call", ToolResultContent.Text("Robots policy blocked this request"), true)
        assertEquals(original, AirbnbMcpResultTool(tool(original)).execute("call", JsonObject(emptyMap())))
    }
}
