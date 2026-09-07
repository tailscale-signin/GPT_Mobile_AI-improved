package dev.melo.gptmobile.improved.data.model

import dev.melo.gptmobile.improved.data.database.entity.ToolConnectionType

data class AvailableChatTool(
    val id: String,
    val name: String,
    val description: String,
    val source: ToolConnectionType,
    val isEnabled: Boolean = true
)
