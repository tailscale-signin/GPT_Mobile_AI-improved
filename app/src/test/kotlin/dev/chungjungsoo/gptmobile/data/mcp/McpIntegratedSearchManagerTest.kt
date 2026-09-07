package dev.chungjungsoo.gptmobile.data.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        manager.resetToDefaults()

        val config = manager.getSearchConfig()
        assertTrue(config.isIntegratedSearchEnabled)
        assertTrue(config.isToolEnabled("search"))
        assertEquals(McpSearchToolSet.tools.size, manager.getActiveTools().size)
    }
}
