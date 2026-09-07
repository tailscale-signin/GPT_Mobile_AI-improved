package dev.chungjungsoo.gptmobile.data.mcp

import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpIntegratedSearchManagerTest {

    @Test
    fun testDefaultStateIsEnabledWithAllToolsActive() {
        val manager = McpIntegratedSearchManager()
        val config = manager.getSearchConfig()

        assertTrue("MCPSearch should be an integrated search option turned ON by default", config.isIntegratedSearchEnabled)
        val activeTools = manager.getActiveTools()
        assertEquals("All McpSearchToolSet tools should be active by default", McpSearchToolSet.tools.size, activeTools.size)
        assertTrue(activeTools.any { it.name == "search" })
        assertTrue(activeTools.any { it.name == "investigate" })
        assertTrue(activeTools.any { it.name == "compare" })
        assertTrue(activeTools.any { it.name == "trending" })
        assertTrue(activeTools.any { it.name == "get_crawl_stats" })
        assertNull("Default maxTools should be null (unbounded)", config.maxTools)
    }

    @Test
    fun testCanTurnOffIntegratedSearch() {
        val manager = McpIntegratedSearchManager()
        manager.setIntegratedSearchEnabled(false)

        assertFalse(manager.getSearchConfig().isIntegratedSearchEnabled)
        assertTrue("When integrated search is disabled, active tools should be empty", manager.getActiveTools().isEmpty())
        assertFalse(manager.getSearchConfig().isToolEnabled("search"))
    }

    @Test
    fun testCanToggleIndividualMcpTools() {
        val manager = McpIntegratedSearchManager()

        // Turn off investigate and compare
        manager.setToolEnabled("investigate", false)
        manager.setToolEnabled("compare", false)

        val activeTools = manager.getActiveTools()
        assertEquals(3, activeTools.size)
        assertTrue(manager.getSearchConfig().isToolEnabled("search"))
        assertFalse(manager.getSearchConfig().isToolEnabled("investigate"))
        assertFalse(manager.getSearchConfig().isToolEnabled("compare"))
        assertTrue(manager.getSearchConfig().isToolEnabled("trending"))
        assertTrue(manager.getSearchConfig().isToolEnabled("get_crawl_stats"))

        // Re-enable compare
        manager.setToolEnabled("compare", true)
        assertTrue(manager.getSearchConfig().isToolEnabled("compare"))
        assertEquals(4, manager.getActiveTools().size)
    }

    @Test
    fun testResetToDefaults() {
        val manager = McpIntegratedSearchManager()
        manager.setIntegratedSearchEnabled(false)
        manager.setToolEnabled("search", false)
        manager.setMaxTools(2)

        manager.resetToDefaults()

        val config = manager.getSearchConfig()
        assertTrue(config.isIntegratedSearchEnabled)
        assertTrue(config.isToolEnabled("search"))
        assertNull(config.maxTools)
        assertEquals(McpSearchToolSet.tools.size, manager.getActiveTools().size)
    }

    @Test
    fun testSetMaxToolsLimitsActiveTools() {
        val manager = McpIntegratedSearchManager()

        manager.setMaxTools(2)
        assertEquals(2, manager.getSearchConfig().maxTools)
        val activeTools = manager.getActiveTools()
        assertEquals(2, activeTools.size)
        assertEquals("search", activeTools[0].name)
        assertEquals("investigate", activeTools[1].name)

        // Clamping negative limit to 0
        manager.setMaxTools(-5)
        assertEquals(0, manager.getSearchConfig().maxTools)
        assertTrue(manager.getActiveTools().isEmpty())

        // Clearing maxTools limit
        manager.setMaxTools(null)
        assertNull(manager.getSearchConfig().maxTools)
        assertEquals(McpSearchToolSet.tools.size, manager.getActiveTools().size)
    }

    @Test
    fun testApplyToChatMcpToolConfig() {
        val manager = McpIntegratedSearchManager()
        manager.setMaxTools(3)
        manager.setToolEnabled("trending", false)

        val chatMcpToolConfig = manager.getSearchConfig().applyToChatMcpToolConfig(
            baseConfig = ChatMcpToolConfig(maxToolCalls = 5)
        )

        assertEquals(3, chatMcpToolConfig.maxTools)
        assertEquals(5, chatMcpToolConfig.maxToolCalls)
        assertFalse(chatMcpToolConfig.isToolEnabled("trending"))
        assertTrue(chatMcpToolConfig.isToolEnabled("search"))
    }
}
