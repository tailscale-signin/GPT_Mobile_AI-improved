package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AvailableChatTool

object ChatToolUtils {
    fun buildAvailableChatTools(connections: List<ToolConnection>): List<AvailableChatTool> {
        return connections.map { conn ->
            AvailableChatTool(
                id = conn.connectionUid,
                name = conn.name,
                description = conn.description ?: "",
                source = conn.type.name,
                isEnabled = conn.isEnabled
            )
        }
    }
}
