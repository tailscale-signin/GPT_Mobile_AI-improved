package dev.chungjungsoo.gptmobile.data.dto.groq.response

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    val error: GroqErrorDetail? = null,

    @SerialName("usage")
    val usage: ChatCompletionUsage? = null,

    @SerialName("x_groq")
    val groqMetadata: GroqStreamMetadata? = null,

    // Transport-owned signal: socket EOF alone is never proof of completion.
    @Transient val streamFinished: Boolean = false
)

@Serializable
data class GroqStreamMetadata(
    @SerialName("usage") val usage: ChatCompletionUsage? = null
)
