package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolUtilsTest {

    @Test
    fun `getBuiltInTools contains expected standard agent tools`() {
        val tools = ChatToolUtils.getBuiltInTools()
        val ids = tools.map { it.id }.toSet()

        assertTrue(ids.contains(BuiltInAgentTool.CURRENT_DATE))
        assertTrue(ids.contains(BuiltInAgentTool.READ_URL))
        assertTrue(ids.contains(BuiltInAgentTool.DEVICE_LOCATION))
        assertTrue(tools.all { it.source == "Built-in" })
    }

    @Test
    fun `buildAvailableChatTools merges built-ins with external connections`() {
        val connections = listOf(
            ToolConnection(
                alias = "github_mcp",
                name = "GitHub MCP",
                endpointUrl = "http://localhost:3000",
                type = ToolConnectionType.MCP
            ),
            ToolConnection(
                alias = "perplexity_search",
                name = "Perplexity",
                endpointUrl = "https://api.perplexity.ai",
                type = ToolConnectionType.PERPLEXITY
            )
        )

        val available = ChatToolUtils.buildAvailableChatTools(connections)

        assertEquals(5, available.size)
        val sources = available.associate { it.id to it.source }
        assertEquals("Built-in", sources[BuiltInAgentTool.CURRENT_DATE])
        assertEquals("Built-in", sources[BuiltInAgentTool.READ_URL])
        assertEquals("Built-in", sources[BuiltInAgentTool.DEVICE_LOCATION])
        assertEquals("MCP", sources["github_mcp"])
        assertEquals("Search", sources["perplexity_search"])
    }

    @Test
    fun `ChatMcpToolConfig correctly toggles built-in tools`() {
        var config = ChatMcpToolConfig()
        assertTrue(config.isToolEnabled(BuiltInAgentTool.READ_URL))

        // Toggle off
        config = config.toggleTool(BuiltInAgentTool.READ_URL)
        assertFalse(config.isToolEnabled(BuiltInAgentTool.READ_URL))

        // Toggle back on
        config = config.toggleTool(BuiltInAgentTool.READ_URL)
        assertTrue(config.isToolEnabled(BuiltInAgentTool.READ_URL))
    }
}
