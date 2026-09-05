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
    val allowAllByDefault: Boolean = true
) {
    fun isToolEnabled(toolId: String): Boolean {
        return if (allowAllByDefault) {
            !disabledToolIds.contains(toolId)
        } else {
            enabledToolIds.contains(toolId)
        }
    }

    fun toggleTool(toolId: String): ChatMcpToolConfig {
        return if (allowAllByDefault) {
            if (disabledToolIds.contains(toolId)) {
                copy(disabledToolIds = disabledToolIds - toolId)
            } else {
                copy(disabledToolIds = disabledToolIds + toolId)
            }
        } else {
            if (enabledToolIds.contains(toolId)) {
                copy(enabledToolIds = enabledToolIds - toolId)
            } else {
                copy(enabledToolIds = enabledToolIds + toolId)
            }
        }
    }
}
