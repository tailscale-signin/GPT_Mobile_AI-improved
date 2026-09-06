package dev.melo.gptmobile.improved.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMcpToolConfig(
    val serverName: String,
    val toolName: String,
    val enabled: Boolean = true,
)
