package dev.melo.gptmobile.improved.data.network

import dev.melo.gptmobile.improved.data.dto.google.request.GenerateContentRequest
import dev.melo.gptmobile.improved.data.dto.google.response.GenerateContentResponse
import kotlinx.coroutines.flow.Flow

interface GoogleAPI {
    fun streamGenerateContent(
        request: GenerateContentRequest,
        model: String,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<GenerateContentResponse>

    suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        config: ProviderRequestConfig
    ): UploadedProviderFile

    suspend fun isFileAvailable(fileName: String, config: ProviderRequestConfig): Boolean
}
