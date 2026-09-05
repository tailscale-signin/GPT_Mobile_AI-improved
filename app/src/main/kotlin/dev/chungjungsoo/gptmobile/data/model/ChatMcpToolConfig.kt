package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

/**
 * Model representing a tool available for chat execution (built-in or MCP).
 */
@Serializable
data class AvailableChatTool(
    val id: String,
    val name: String,
    val description: String,
    val source: String = "MCP", // "Built-in" or "MCP"
    val isEnabled: Boolean = true
)

/**
 * Per-chat configuration for enabling or disabling specific tools.
 */
@Serializable
data class ChatMcpToolConfig(
    val enabledToolIds: Set<String> = emptySet(),
    val disabledToolIds: Set<String> = emptySet(),
    val allowAllByDefault: Boolean = true,
    val allToolsDisabled: Boolean = false
) {
    fun isToolEnabled(toolId: String): Boolean {
        if (allToolsDisabled) return false
        return if (allowAllByDefault) {
            !disabledToolIds.contains(toolId)
        } else {
            enabledToolIds.contains(toolId)
        }
    }

    fun withToolDisabled(toolId: String): ChatMcpToolConfig {
        return copy(
            disabledToolIds = disabledToolIds + toolId,
            enabledToolIds = enabledToolIds - toolId
        )
    }

    fun withToolEnabled(toolId: String): ChatMcpToolConfig {
        return copy(
            enabledToolIds = enabledToolIds + toolId,
            disabledToolIds = disabledToolIds - toolId,
            allToolsDisabled = false
        )
    }

    fun toggleTool(toolId: String): ChatMcpToolConfig {
        return if (isToolEnabled(toolId)) {
            withToolDisabled(toolId)
        } else {
            withToolEnabled(toolId)
        }
    }
}
