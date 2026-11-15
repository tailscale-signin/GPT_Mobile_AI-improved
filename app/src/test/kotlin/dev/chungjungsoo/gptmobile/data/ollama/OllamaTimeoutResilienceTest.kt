package dev.chungjungsoo.gptmobile.data.ollama

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAICompatibleAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderAttachmentEncoder
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChatCompletionChunk
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
import dev.chungjungsoo.gptmobile.data.network.ProviderDispatchRetryGuard
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.UploadedProviderFile
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import dev.chungjungsoo.gptmobile.data.network.providerTransportFailureDetails
import dev.chungjungsoo.gptmobile.data.network.userMessage
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaTimeoutResilienceTest {

    private class TimeoutOnceThenSuccessOpenAIAPI(private val error: ErrorDetail) : OpenAIAPI {
        var callCount = 0
            private set

        override fun streamChatCompletion(
            request: ChatCompletionRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<ChatCompletionChunk> = flow {
            callCount++
            if (callCount == 1) {
                emit(ChatCompletionChunk(error = error))
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
        var callCount = 0
            private set

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
        ): Flow<GroqChatCompletionChunk> = flow { }
    }

    private class MockAttachmentEncoder : ProviderAttachmentEncoder(null as android.content.Context?) {
        override suspend fun openAIChatMessages(
            turns: List<ConversationTurn>,
            systemPrompt: String?
        ): List<ChatMessage> = turns.map {
            ChatMessage(role = Role.USER, content = listOf(TextContent(it.userMessage.content.orEmpty())))
        }
    }

    private fun replayableTimeout(): ErrorDetail {
        val error = SocketTimeoutException("Synthetic timeout before output")
        val kind = networkFailureKind(error)
        val guard = ProviderDispatchRetryGuard(ProviderRequestConfig("http://127.0.0.1:11434/v1", null))
        guard.dispatched()
        val replayAllowed = guard.mayRetry(hasPayload = false) && kind.retryable
        return ErrorDetail(
            message = kind.userMessage(partialOutput = false),
            type = "network_error",
            code = kind.code,
            retryable = replayAllowed,
            failureDetails = providerTransportFailureDetails(
                details = buildJsonObject {
                    put("failed_stage", kind.failedStage)
                    put("retryable", replayAllowed)
                },
                error = error,
                timing = null,
                dispatched = true,
                receivedPayload = false,
                receivedVisibleText = false,
                generationCompleted = false,
                replayAllowed = replayAllowed,
                requiresReconciliation = guard.remoteOutcomeUnknown
            )
        )
    }

    @Test
    fun `ollama stream continues after authorized transport timeout and succeeds on retry`() = runTest {
        val fakeApi = TimeoutOnceThenSuccessOpenAIAPI(replayableTimeout())
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
            userMessage = MessageV2(id = 1, chatId = 1, content = "Hello", platformType = null, createdAt = 0L),
            assistantMessage = null,
            isCurrentTurn = true
        )
        val session = adapter.openSession(listOf(turn), platform)
        val events = session.streamRound(emptyList(), emptyList()).toList()

        // Verify that retry notice was emitted, followed by recovered text and Completed
        assertTrue(events.any { it is ProviderEvent.Notice && it.message.contains("timed out. Retrying") })
        assertTrue(events.any { it is ProviderEvent.TextDelta && it.text == "Recovered after timeout" })
        assertEquals(2, fakeApi.callCount)
        assertEquals(2_000L, testScheduler.currentTime)
        assertFalse(events.any { it is ProviderEvent.Failed })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `ollama does not restart HTTP 502 recovery without transport replay authority`() = runTest {
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
            userMessage = MessageV2(id = 1, chatId = 1, content = "Hello", platformType = null, createdAt = 0L),
            assistantMessage = null,
            isCurrentTurn = true
        )
        val session = adapter.openSession(listOf(turn), platform)
        val events = session.streamRound(emptyList(), emptyList()).toList()

        // A bare HTTP error has no producer replay grant; the adapter must not
        // infer permission to restart from its code or message.
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(1, fakeApi.callCount)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals("502", failure.errorCode)
        assertEquals("HTTP 502: Bad Gateway", failure.message)
        assertNull(failure.failureDetails)
        assertFalse(events.any { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        assertEquals(failure, events.last())
    }

    @Test
    fun `ollama message only timeout does not invent transport replay authority`() = runTest {
        val fakeApi = TimeoutOnceThenSuccessOpenAIAPI(ErrorDetail(message = "Request timed out.", type = "network_error"))
        val adapter = OpenAICompatibleAdapter(fakeApi, DummyGroqAPI(), MockAttachmentEncoder())
        val platform = PlatformV2(
            uid = "ollama-message-only",
            name = "Ollama",
            compatibleType = ClientType.OLLAMA,
            apiUrl = "http://127.0.0.1:11434/v1",
            model = "llama3:latest"
        )
        val turn = ConversationTurn(
            userMessage = MessageV2(id = 1, chatId = 1, content = "Hello", platformType = null),
            assistantMessage = null,
            isCurrentTurn = true
        )
        val events = adapter.openSession(listOf(turn), platform).streamRound(emptyList(), emptyList()).toList()
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(1, fakeApi.callCount)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals("Request timed out.", failure.message)
        assertNull(failure.errorCode)
        assertNull(failure.failureDetails)
        assertFalse(events.any { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
    }
}
