package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val autoContinueEnabled: Boolean = false,
    val autoContinueSettings: AutoContinueSettings = AutoContinueSettings(),
    val isTitleGenerated: Boolean = false
)

@Serializable
data class AutoContinueSettings(
    val maxTokens: Int = 2048,
    val maxToolCalls: Int = 10,
    val isAutoContinueEnabled: Boolean = false
)