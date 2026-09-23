package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.util.ChatToolUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolUtilsTest {

    @Test
    fun `buildAvailableChatTools maps configured external connections without inventing tools`() {
        val connections = listOf(
            ToolConnection(
                connectionUid = "mcp-github",
                alias = "github_mcp",
                name = "GitHub MCP",
                endpointUrl = "http://localhost:3000",
                type = ToolConnectionType.MCP,
                authType = ToolConnectionAuthType.NONE,
                secretRef = null,
                oauthClientId = null,
                allowCleartext = true
            ),
            ToolConnection(
                connectionUid = "search-perplexity",
                alias = "perplexity_search",
                name = "Perplexity",
                endpointUrl = "https://api.perplexity.ai",
                type = ToolConnectionType.PERPLEXITY,
                authType = ToolConnectionAuthType.BEARER,
                secretRef = "perplexity-secret",
                oauthClientId = null
            )
        )

        val available = ChatToolUtils.buildAvailableChatTools(connections)

        assertEquals(2, available.size)
        assertEquals(
            listOf("mcp-github", "search-perplexity"),
            available.map { it.id }
        )
        assertEquals(listOf("MCP", "PERPLEXITY"), available.map { it.source })
        assertTrue(available.all { it.isEnabled })
    }

    @Test
    fun `buildAvailableChatTools preserves connection display metadata`() {
        val connection = ToolConnection(
            connectionUid = "mcp-docs",
            alias = "docs_alias",
            name = "Docs",
            endpointUrl = "https://example.com/mcp",
            type = ToolConnectionType.MCP,
            authType = ToolConnectionAuthType.NONE,
            secretRef = null,
            oauthClientId = null
        )

        val available = ChatToolUtils.buildAvailableChatTools(listOf(connection)).single()

        assertEquals("mcp-docs", available.id)
        assertEquals("Docs", available.name)
        assertEquals("docs_alias", available.description)
        assertEquals("MCP", available.source)
    }

    @Test
    fun `ChatMcpToolConfig toggles built-in tool IDs`() {
        var config = ChatMcpToolConfig()
        assertTrue(config.isToolEnabled(BuiltInAgentTool.READ_URL))

        config = config.toggleTool(BuiltInAgentTool.READ_URL)
        assertFalse(config.isToolEnabled(BuiltInAgentTool.READ_URL))

        config = config.toggleTool(BuiltInAgentTool.READ_URL)
        assertTrue(config.isToolEnabled(BuiltInAgentTool.READ_URL))
    }
}
