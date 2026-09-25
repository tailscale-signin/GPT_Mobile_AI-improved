package dev.chungjungsoo.gptmobile.data.dto.anthropic.common

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("document")
data class DocumentContent(
    @SerialName("source")
    val source: DocumentSource
) : MessageContent()

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DocumentSource(
    @SerialName("type")
    val type: String,

    @SerialName("file_id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fileId: String? = null,

    @SerialName("media_type")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mediaType: String? = null,

    @SerialName("data")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val data: String? = null
) {
    companion object {
        fun file(fileId: String) = DocumentSource(type = "file", fileId = fileId)
        fun base64(mediaType: String, data: String) =
            DocumentSource(type = "base64", mediaType = mediaType, data = data)
    }
}
