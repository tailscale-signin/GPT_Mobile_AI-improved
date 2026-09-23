package dev.chungjungsoo.gptmobile.data.mcp

import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMcpToolConfigTest {

    @Test
    fun `default config allows all tools and has no execution limits`() {
        val config = ChatMcpToolConfig()

        assertTrue(config.allowAllByDefault)
        assertFalse(config.allToolsDisabled)
        assertNull(config.maxToolCalls)
        assertNull(config.maxTools)
        assertTrue(config.isToolEnabled("toolA"))
        assertTrue(config.isToolEnabled("toolB"))
    }

    @Test
    fun `max tools and max calls clamp negative values and can be cleared`() {
        val toolsConfig = ChatMcpToolConfig().withMaxTools(5)
        assertEquals(5, toolsConfig.maxTools)
        assertEquals(0, ChatMcpToolConfig().withMaxTools(-3).maxTools)
        assertNull(toolsConfig.withMaxTools(null).maxTools)

        val callsConfig = ChatMcpToolConfig().withMaxToolCalls(10)
        assertEquals(10, callsConfig.maxToolCalls)
        assertEquals(0, ChatMcpToolConfig().withMaxToolCalls(-1).maxToolCalls)
        assertNull(callsConfig.withMaxToolCalls(null).maxToolCalls)
    }

    @Test
    fun `limit tools applies configured cap`() {
        val tools = (1..8).map { "tool$it" }

        assertEquals(tools, ChatMcpToolConfig().limitTools(tools))
        assertEquals(tools.take(5), ChatMcpToolConfig(maxTools = 5).limitTools(tools))
        assertTrue(ChatMcpToolConfig(maxTools = 0).limitTools(tools).isEmpty())
        assertEquals(tools, ChatMcpToolConfig(maxTools = 100).limitTools(tools))
    }

    @Test
    fun `default allow list can disable and reenable an individual tool`() {
        val disabled = ChatMcpToolConfig().withToolDisabled("toolB")
        assertTrue(disabled.isToolEnabled("toolA"))
        assertFalse(disabled.isToolEnabled("toolB"))

        val enabledAgain = disabled.withToolEnabled("toolB")
        assertTrue(enabledAgain.isToolEnabled("toolB"))
        assertFalse("toolB" in enabledAgain.disabledToolIds)
    }

    @Test
    fun `explicit allow list denies tools not selected`() {
        val config = ChatMcpToolConfig(
            enabledToolIds = setOf("search", "fetch"),
            allowAllByDefault = false
        )

        assertTrue(config.isToolEnabled("search"))
        assertTrue(config.isToolEnabled("fetch"))
        assertFalse(config.isToolEnabled("other"))
    }

    @Test
    fun `all tools disabled overrides individual enablement`() {
        val config = ChatMcpToolConfig(
            enabledToolIds = setOf("toolA"),
            allowAllByDefault = false,
            allToolsDisabled = true
        )

        assertFalse(config.isToolEnabled("toolA"))
        assertFalse(config.isToolEnabled("toolB"))
        assertTrue(config.withToolEnabled("toolA").isToolEnabled("toolA"))
    }
}
