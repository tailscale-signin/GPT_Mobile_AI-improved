package dev.melo.gptmobile.improved.data.network

import dev.melo.gptmobile.improved.data.dto.groq.request.GroqChatCompletionRequest
import dev.melo.gptmobile.improved.data.dto.groq.response.GroqChatCompletionChunk
import kotlinx.coroutines.flow.Flow

interface GroqAPI {
    fun streamChatCompletion(
        request: GroqChatCompletionRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<GroqChatCompletionChunk>
}
