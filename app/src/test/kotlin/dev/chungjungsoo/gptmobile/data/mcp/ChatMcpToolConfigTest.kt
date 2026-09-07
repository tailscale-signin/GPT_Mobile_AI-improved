package dev.chungjungsoo.gptmobile.data.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMcpToolConfigTest {

    @Test
    fun testDefaultConfigAllowsAllToolsAndNoLimit() {
        val config = ChatMcpToolConfig()
        assertTrue(config.allowAllByDefault)
        assertFalse(config.allToolsDisabled)
        assertNull(config.maxToolCalls)
        assertNull(config.maxTools)
        assertTrue(config.isToolEnabled("toolA"))
        assertTrue(config.isToolEnabled("toolB"))
    }

    @Test
    fun testWithMaxToolsEnforcesBoundaries() {
        val config = ChatMcpToolConfig().withMaxTools(5)
        assertEquals(5, config.maxTools)

        val negativeClamped = ChatMcpToolConfig().withMaxTools(-3)
        assertEquals(0, negativeClamped.maxTools)

        val cleared = config.withMaxTools(null)
        assertNull(cleared.maxTools)
    }

    @Test
    fun testLimitToolsAppliesMaxToolsSlice() {
        val tools = listOf("tool1", "tool2", "tool3", "tool4", "tool5", "tool6", "tool7", "tool8")

        // No limit
        val noLimitConfig = ChatMcpToolConfig()
        assertEquals(tools, noLimitConfig.limitTools(tools))

        // Capped at 5
        val limit5Config = ChatMcpToolConfig().withMaxTools(5)
        assertEquals(listOf("tool1", "tool2", "tool3", "tool4", "tool5"), limit5Config.limitTools(tools))

        // Capped at 7
        val limit7Config = ChatMcpToolConfig().withMaxTools(7)
        assertEquals(7, limit7Config.limitTools(tools).size)

        // Capped at 0
        val limit0Config = ChatMcpToolConfig().withMaxTools(0)
        assertTrue(limit0Config.limitTools(tools).isEmpty())

        // Limit larger than list size returns all tools
        val limit100Config = ChatMcpToolConfig().withMaxTools(100)
        assertEquals(tools, limit100Config.limitTools(tools))
    }

    @Test
    fun testWithMaxToolCallsEnforcesBoundaries() {
        val config = ChatMcpToolConfig().withMaxToolCalls(10)
        assertEquals(10, config.maxToolCalls)

        val negativeClamped = ChatMcpToolConfig().withMaxToolCalls(-1)
        assertEquals(0, negativeClamped.maxToolCalls)

        val cleared = config.withMaxToolCalls(null)
        assertNull(cleared.maxToolCalls)
    }

    @Test
    fun testDisablingTools() {
        val config = ChatMcpToolConfig()
            .withToolDisabled("toolB")

        assertTrue(config.isToolEnabled("toolA"))
        assertFalse(config.isToolEnabled("toolB"))
    }

    @Test
    fun testAllToolsDisabled() {
        val config = ChatMcpToolConfig().withAllToolsDisabled(true)
        assertFalse(config.isToolEnabled("toolA"))
        assertFalse(config.isToolEnabled("toolB"))
    }
}
