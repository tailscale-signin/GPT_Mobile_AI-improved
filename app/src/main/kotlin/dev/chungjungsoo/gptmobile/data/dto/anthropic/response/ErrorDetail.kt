package dev.chungjungsoo.gptmobile.data.dto.anthropic.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ErrorDetail(

    @SerialName("type")
    val type: String,

    @SerialName("message")
    val message: String,

    @kotlinx.serialization.Transient
    val code: String? = null,

    // Only the client may attach dispatch and response observations.
    @kotlinx.serialization.Transient
    val failureDetails: kotlinx.serialization.json.JsonObject? = null
)
