package dev.melo.gptmobile.improved.data.dto.anthropic.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("content_block_stop")
data class ContentStopResponseChunk(

    @SerialName("index")
    val index: Int
) : MessageResponseChunk()
