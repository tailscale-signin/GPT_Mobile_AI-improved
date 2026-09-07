package dev.melo.gptmobile.improved.data.dto.groq.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroqChatCompletionChunk(
    @SerialName("id")
    val id: String? = null,

    @SerialName("object")
    val objectType: String? = null,

    @SerialName("created")
    val created: Long? = null,

    @SerialName("model")
    val model: String? = null,

    @SerialName("choices")
    val choices: List<GroqChoice>? = null,

    @SerialName("error")
    val error: GroqErrorDetail? = null
)
