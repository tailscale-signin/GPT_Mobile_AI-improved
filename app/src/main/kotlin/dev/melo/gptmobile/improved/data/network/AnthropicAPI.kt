package dev.melo.gptmobile.improved.data.network

import dev.melo.gptmobile.improved.data.dto.anthropic.request.MessageRequest
import dev.melo.gptmobile.improved.data.dto.anthropic.response.MessageResponseChunk
import kotlinx.coroutines.flow.Flow

interface AnthropicAPI {
    fun streamChatMessage(
        messageRequest: MessageRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<MessageResponseChunk>

    suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        config: ProviderRequestConfig
    ): UploadedProviderFile

    suspend fun isFileAvailable(fileId: String, config: ProviderRequestConfig): Boolean
}
