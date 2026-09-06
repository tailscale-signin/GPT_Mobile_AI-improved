package dev.melo.gptmobile.improved.util

import dev.melo.gptmobile.improved.data.database.entity.ToolConnection
import dev.melo.gptmobile.improved.data.model.AvailableChatTool

object ChatToolUtils {
    fun buildAvailableChatTools(connections: List<ToolConnection>): List<AvailableChatTool> {
        return connections.map { conn ->
            AvailableChatTool(
                id = conn.connectionUid,
                name = conn.name,
                description = conn.alias,
                source = conn.type,
                isEnabled = true
            )
        }
    }
}
