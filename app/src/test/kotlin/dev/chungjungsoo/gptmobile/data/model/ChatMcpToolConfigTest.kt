package dev.chungjungsoo.gptmobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMcpToolConfigTest {

    @Test
    fun defaultEmptyConfig() {
        val config = ChatMcpToolConfig.empty()
        assertFalse(config.enabled)
        assertTrue(config.allowedTools.isEmpty())
        assertTrue(config.blockedTools.isEmpty())
    }

    @Test
    fun isToolEnabledWithExplicitBlocksAndAllows() {
        val config = ChatMcpToolConfig(
            enabled = true,
            allowedTools = setOf("search", "fetch"),
            blockedTools = setOf("blocked_tool")
        )

        assertFalse(config.isToolEnabled("blocked_tool"))
        assertTrue(config.isToolEnabled("search"))
        assertFalse(config.isToolEnabled("other_tool"))
    }

    @Test
    fun isToolEnabledWithNoAllowedListAllowsAnyNotBlocked() {
        val config = ChatMcpToolConfig(
            enabled = true,
            allowedTools = emptySet(),
            blockedTools = setOf("dangerous_tool")
        )

        assertFalse(config.isToolEnabled("dangerous_tool"))
        assertTrue(config.isToolEnabled("any_other_tool"))
    }

    @Test
    fun isToolEnabledReturnsFalseWhenGloballyDisabled() {
        val config = ChatMcpToolConfig(
            enabled = false,
            allowedTools = setOf("search"),
            blockedTools = emptySet()
        )

        assertFalse(config.isToolEnabled("search"))
    }

    @Test
    fun withToolEnabledAndDisabledMutations() {
        val initial = ChatMcpToolConfig.empty()
        val enabled = initial.withToolEnabled("calculator")
        assertTrue(enabled.allowedTools.contains("calculator"))
        assertFalse(enabled.blockedTools.contains("calculator"))

        val disabled = enabled.withToolDisabled("calculator")
        assertFalse(disabled.allowedTools.contains("calculator"))
        assertTrue(disabled.blockedTools.contains("calculator"))
    }
}
