package dev.melo.gptmobile.improved.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatAttachment(
    val uri: String,
    val mimeType: String,
    val fileName: String? = null,
    val fileSize: Long? = null,
)
