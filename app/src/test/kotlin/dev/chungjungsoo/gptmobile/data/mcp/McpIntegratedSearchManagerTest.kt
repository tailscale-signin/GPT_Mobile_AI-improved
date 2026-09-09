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

        assertTrue("Online Search should be an integrated search option turned ON by default", config.isIntegratedSearchEnabled)
        val activeTools = manager.getActiveTools()
        assertEquals("All McpSearchToolSet tools should be active by default", McpSearchToolSet.tools.size, activeTools.size)
        assertTrue(activeTools.any { it.name == "web_search" })
        assertTrue(activeTools.any { it.name == "fetch_webpage" })
        assertNull("Default maxTools should be null (unbounded)", config.maxTools)
    }

    @Test
    fun testCanTurnOffIntegratedSearch() {
        val manager = McpIntegratedSearchManager()
        manager.setIntegratedSearchEnabled(false)

        assertFalse(manager.getSearchConfig().isIntegratedSearchEnabled)
        assertTrue("When integrated search is disabled, active tools should be empty", manager.getActiveTools().isEmpty())
        assertFalse(manager.getSearchConfig().isToolEnabled("web_search"))
    }

    @Test
    fun testCanToggleIndividualMcpTools() {
        val manager = McpIntegratedSearchManager()

        // Turn off fetch_webpage
        manager.setToolEnabled("fetch_webpage", false)

        val activeTools = manager.getActiveTools()
        assertEquals(1, activeTools.size)
        assertTrue(manager.getSearchConfig().isToolEnabled("web_search"))
        assertFalse(manager.getSearchConfig().isToolEnabled("fetch_webpage"))

        // Re-enable fetch_webpage
        manager.setToolEnabled("fetch_webpage", true)
        assertTrue(manager.getSearchConfig().isToolEnabled("fetch_webpage"))
        assertEquals(2, manager.getActiveTools().size)
    }

    @Test
    fun testResetToDefaults() {
        val manager = McpIntegratedSearchManager()
        manager.setIntegratedSearchEnabled(false)
        manager.setToolEnabled("web_search", false)
        manager.setMaxTools(1)

        manager.resetToDefaults()

        val config = manager.getSearchConfig()
        assertTrue(config.isIntegratedSearchEnabled)
        assertTrue(config.isToolEnabled("web_search"))
        assertNull(config.maxTools)
        assertEquals(McpSearchToolSet.tools.size, manager.getActiveTools().size)
    }

    @Test
    fun testSetMaxToolsLimitsActiveTools() {
        val manager = McpIntegratedSearchManager()

        manager.setMaxTools(1)
        assertEquals(1, manager.getSearchConfig().maxTools)
        val activeTools = manager.getActiveTools()
        assertEquals(1, activeTools.size)
        assertEquals("web_search", activeTools[0].name)

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
        manager.setMaxTools(1)
        manager.setToolEnabled("fetch_webpage", false)

        val chatMcpToolConfig = manager.getSearchConfig().applyToChatMcpToolConfig(
            baseConfig = ChatMcpToolConfig(maxToolCalls = 5)
        )

        assertEquals(1, chatMcpToolConfig.maxTools)
        assertEquals(5, chatMcpToolConfig.maxToolCalls)
        assertFalse(chatMcpToolConfig.isToolEnabled("fetch_webpage"))
        assertTrue(chatMcpToolConfig.isToolEnabled("web_search"))
    }
}
