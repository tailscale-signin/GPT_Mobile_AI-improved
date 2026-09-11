package dev.chungjungsoo.gptmobile.data.ollama

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAICompatibleAdapter
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.UploadedProviderFile
import java.util.ArrayDeque
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaTimeoutResilienceTest {

    private class TimeoutOnceThenSuccessOpenAIAPI : OpenAIAPI {
        private var callCount = 0

        override fun streamChatCompletion(
            request: ChatCompletionRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<ChatCompletionChunk> = flow {
            callCount++
            if (callCount == 1) {
                emit(
                    ChatCompletionChunk(
                        error = ErrorDetail(
                            message = "Request timed out.",
                            type = "network_error"
                        )
                    )
                )
            } else {
                emit(
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(0, Delta(content = "Recovered after timeout"), finishReason = "stop")
                        )
                    )
                )
            }
        }

        override fun streamResponses(
            request: ResponsesRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<ResponsesStreamEvent> = flow { }

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String,
            config: ProviderRequestConfig
        ) = UploadedProviderFile("file", mimeType)

        override suspend fun isFileAvailable(fileId: String, config: ProviderRequestConfig) = false
    }

    private class ContinuousTimeoutOpenAIAPI : OpenAIAPI {
        override fun streamChatCompletion(
            request: ChatCompletionRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<ChatCompletionChunk> = flow {
            emit(
                ChatCompletionChunk(
                    error = ErrorDetail(
                        message = "Response timed out while waiting for the next chunk.",
                        type = "network_error"
                    )
                )
            )
        }

        override fun streamResponses(
            request: ResponsesRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<ResponsesStreamEvent> = flow { }

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String,
            config: ProviderRequestConfig
        ) = UploadedProviderFile("file", mimeType)

        override suspend fun isFileAvailable(fileId: String, config: ProviderRequestConfig) = false
    }

    @Test
    fun `ollama stream continues after timeout and succeeds on retry`() = runBlocking {
        val fakeApi = TimeoutOnceThenSuccessOpenAIAPI()
        val adapter = OpenAICompatibleAdapter(fakeApi)
        val platform = PlatformV2(
            id = 1,
            uid = "ollama-local",
            name = "Ollama",
            compatibleType = ClientType.OLLAMA,
            apiUrl = "http://127.0.0.1:11434/v1",
            model = "llama3:latest",
            timeout = 30
        )

        val events = adapter.stream(
            platform = platform,
            messages = listOf(ChatMessage(role = "user", content = "Hello")),
            tools = emptyList()
        ).toList()

        // Verify that retry notice was emitted, followed by recovered text and Completed
        assertTrue(events.any { it is ProviderEvent.Notice && it.message.contains("timed out. Retrying") })
        assertTrue(events.any { it is ProviderEvent.TextDelta && it.text == "Recovered after timeout" })
        assertEquals(ProviderEvent.Completed, events.last())
    }
}
