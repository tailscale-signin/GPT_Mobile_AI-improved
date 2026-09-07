package dev.melo.gptmobile.improved.data.dto.anthropic.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("ping")
data object PingResponseChunk : MessageResponseChunk()
