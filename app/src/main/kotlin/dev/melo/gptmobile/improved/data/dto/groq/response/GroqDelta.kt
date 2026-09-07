package dev.melo.gptmobile.improved.data.dto.groq.response

import dev.melo.gptmobile.improved.data.dto.openai.response.ChatToolCallDelta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroqDelta(
    @SerialName("role")
    val role: String? = null,

    @SerialName("content")
    val content: String? = null,

    @SerialName("reasoning")
    val reasoning: String? = null,

    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCallDelta>? = null
)
