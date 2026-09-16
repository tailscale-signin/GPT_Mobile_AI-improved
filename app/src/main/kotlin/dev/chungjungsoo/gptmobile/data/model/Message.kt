package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val role: String, // "USER" or "MODEL"
    val content: String,
    val createdAt: Long,
    val isTitleGenerated: Boolean = false
)