package dev.chungjungsoo.gptmobile.data.dto.groq.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
data class GroqErrorDetail(
    @SerialName("message")
    val message: String,

    @SerialName("type")
    val type: String? = null,

    @SerialName("code")
    val code: String? = null,

    @Transient
    val failureDetails: JsonObject? = null
)
