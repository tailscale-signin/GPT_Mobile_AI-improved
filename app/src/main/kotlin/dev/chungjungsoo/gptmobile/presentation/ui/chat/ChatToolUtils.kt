package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AvailableChatTool

/**
 * Utility functions for chat tool resolution and management.
 */
object ChatToolUtils {

    /**
     * Return list of default built-in tools available across chats.
     */
    fun getBuiltInTools(): List<AvailableChatTool> = listOf(
        AvailableChatTool(
            id = BuiltInAgentTool.CURRENT_DATE,
            name = "Current Date & Time",
            description = "Provides the current local date, time, and timezone",
            source = "Built-in"
        ),
        AvailableChatTool(
            id = BuiltInAgentTool.READ_URL,
            name = "Web Content Reader",
            description = "Fetches and extracts clean plain-text readable content from URLs",
            source = "Built-in"
        ),
        AvailableChatTool(
            id = BuiltInAgentTool.DEVICE_LOCATION,
            name = "Device Location",
            description = "Accesses approximate or precise device location when permitted",
            source = "Built-in"
        )
    )

    /**
     * Combine built-in tools and configured tool connections into an unified list.
     */
    fun buildAvailableChatTools(connections: List<ToolConnection>): List<AvailableChatTool> {
        val builtIns = getBuiltInTools()
        val externalTools = connections.map { conn ->
            AvailableChatTool(
                id = conn.alias,
                name = conn.name,
                description = conn.endpointUrl,
                source = if (conn.isWebSearch) "Search" else "MCP"
            )
        }
        return builtIns + externalTools
    }
}
