package dev.melo.gptmobile.improved.data.network

data class UploadedProviderFile(
    val id: String,
    val mimeType: String,
    val name: String? = null,
    val uri: String? = null
)
