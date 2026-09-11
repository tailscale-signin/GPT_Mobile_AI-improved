package dev.chungjungsoo.gptmobile.data.ollama

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAICompatibleAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderAttachmentEncoder
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChatCompletionResponse
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.UploadedProviderFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    private class Gateway502ThenSuccessOpenAIAPI : OpenAIAPI {
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
                            message = "HTTP 502: Bad Gateway",
                            type = "http_error",
                            code = "502"
                        )
                    )
                )
            } else {
                emit(
                    ChatCompletionChunk(
                        choices = listOf(
                            Choice(0, Delta(content = "Recovered after 502 gateway error"), finishReason = "stop")
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

    private class DummyGroqAPI : GroqAPI {
        override fun streamChatCompletion(
            request: GroqChatCompletionRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<GroqChatCompletionResponse> = flow { }
    }

    private class MockAttachmentEncoder : ProviderAttachmentEncoder(null as android.content.Context?) {
        override suspend fun openAIChatMessages(
            turns: List<ConversationTurn>,
            systemPrompt: String?
        ): List<ChatMessage> = turns.map {
            ChatMessage(role = Role.USER, content = listOf(TextContent(it.userMessage.content.orEmpty())))
        }
    }

    @Test
    fun `ollama stream continues after timeout and succeeds on retry`() = runBlocking {
        val fakeApi = TimeoutOnceThenSuccessOpenAIAPI()
        val dummyGroqApi = DummyGroqAPI()
        val dummyEncoder = MockAttachmentEncoder()
        val adapter = OpenAICompatibleAdapter(fakeApi, dummyGroqApi, dummyEncoder)
        val platform = PlatformV2(
            id = 1,
            uid = "ollama-local",
            name = "Ollama",
            compatibleType = ClientType.OLLAMA,
            apiUrl = "http://127.0.0.1:11434/v1",
            model = "llama3:latest",
            timeout = 30
        )

        val turn = ConversationTurn(
            userMessage = MessageV2(id = 1, chatId = 1, content = "Hello", createdAt = 0L)
        )
        val session = adapter.openSession(listOf(turn), platform)
        val events = session.streamRound(emptyList(), emptyList()).toList()

        // Verify that retry notice was emitted, followed by recovered text and Completed
        assertTrue(events.any { it is ProviderEvent.Notice && it.message.contains("timed out. Retrying") })
        assertTrue(events.any { it is ProviderEvent.TextDelta && it.text == "Recovered after timeout" })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `ollama stream continues after HTTP 502 bad gateway and succeeds on retry`() = runBlocking {
        val fakeApi = Gateway502ThenSuccessOpenAIAPI()
        val dummyGroqApi = DummyGroqAPI()
        val dummyEncoder = MockAttachmentEncoder()
        val adapter = OpenAICompatibleAdapter(fakeApi, dummyGroqApi, dummyEncoder)
        val platform = PlatformV2(
            id = 2,
            uid = "ollama-proxy",
            name = "Ollama",
            compatibleType = ClientType.OLLAMA,
            apiUrl = "http://localhost:11434/v1",
            model = "llama3:latest",
            timeout = 30
        )

        val turn = ConversationTurn(
            userMessage = MessageV2(id = 1, chatId = 1, content = "Hello", createdAt = 0L)
        )
        val session = adapter.openSession(listOf(turn), platform)
        val events = session.streamRound(emptyList(), emptyList()).toList()

        // Verify that retry notice was emitted after HTTP 502, followed by recovered text and Completed
        assertTrue(events.any { it is ProviderEvent.Notice })
        assertTrue(events.any { it is ProviderEvent.TextDelta && it.text == "Recovered after 502 gateway error" })
        assertEquals(ProviderEvent.Completed, events.last())
    }
}
