package dev.melo.gptmobile.improved.data.network

import dev.melo.gptmobile.improved.data.dto.openai.request.ChatCompletionRequest
import dev.melo.gptmobile.improved.data.dto.openai.request.ResponsesRequest
import dev.melo.gptmobile.improved.data.dto.openai.response.ChatCompletionChunk
import dev.melo.gptmobile.improved.data.dto.openai.response.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow

interface OpenAIAPI {
    fun streamChatCompletion(
        request: ChatCompletionRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<ChatCompletionChunk>

    fun streamResponses(
        request: ResponsesRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<ResponsesStreamEvent>

    suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        config: ProviderRequestConfig
    ): UploadedProviderFile

    suspend fun isFileAvailable(fileId: String, config: ProviderRequestConfig): Boolean
}
