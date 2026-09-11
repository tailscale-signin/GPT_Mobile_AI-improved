package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.groq.request.createGroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatFunctionTool
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterReasoning
import dev.chungjungsoo.gptmobile.data.parser.GroqReasoningParser
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

private const val GROQ_OUTPUT_LIMIT_MESSAGE = "Output limit reached. Please reduce the length of your input or tool responses."

class OpenAICompatibleAdapter @Inject constructor(
    private val openAIAPI: OpenAIAPI,
    private val groqAPI: GroqAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    private val routingJson = Json { ignoreUnknownKeys = true }

    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialMessages = attachmentEncoder.openAIChatMessages(turns, platform.systemPrompt)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val messages = initialMessages + exchanges.flatMap { it.toChatMessages() }
                val requestTools = tools.takeIf { it.isNotEmpty() }?.map { definition ->
                    ChatFunctionTool(definition.name, definition.description, definition.inputSchema)
                }

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                val isOpenRouter = platform.compatibleType == ClientType.OPENROUTER
                val isOllama = platform.compatibleType == ClientType.OLLAMA
                val openRouterHeaders = if (isOpenRouter) {
                    mapOf(
                        "HTTP-Referer" to "https://github.com/tailscale-signin/GPT_Mobile_AI-improved",
                        "X-Title" to "GPT Mobile AI Improved"
                    )
                } else {
                    emptyMap()
                }

                val parsedRouting: OpenRouterProviderRouting? = if (isOpenRouter && !platform.openRouterRouting.isNullOrBlank()) {
                    runCatching {
                        routingJson.decodeFromString<OpenRouterProviderRouting>(platform.openRouterRouting)
                    }.getOrNull()
                } else {
                    null
                }

                val parsedOllamaOptions: OllamaOptions? = if (isOllama) {
                    if (!platform.ollamaOptions.isNullOrBlank()) {
                        runCatching {
                            routingJson.decodeFromString<OllamaOptions>(platform.ollamaOptions)
                        }.getOrElse { OllamaOptions.createDefault() }
                    } else {
                        OllamaOptions.createDefault()
                    }
                } else {
                    null
                }

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val config = ProviderRequestConfig(
                        apiUrl = platform.apiUrl,
                        token = activeKey,
                        extraHeaders = openRouterHeaders
                    )
                    var roundFailed = false
                    var canRotate = false

                    if (platform.compatibleType == ClientType.GROQ) {
                        val request = createGroqChatCompletionRequest(messages, platform).copy(tools = requestTools)
                        val assembler = ChatCompletionsEventAssembler()
                        val reasoningParser = GroqReasoningParser()

                        try {
                            groqAPI.streamChatCompletion(request, platform.timeout, config).collect { chunk ->
                                chunk.error?.let { error ->
                                    roundFailed = true
                                    lastFailedMessage = error.message
                                    if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(error.message)) {
                                        canRotate = true
                                    } else {
                                        emit(ProviderEvent.Failed(error.message))
                                    }
                                } ?: chunk.choices.orEmpty().forEach { choice ->
                                    reasoningParser.append(
                                        contentChunk = choice.delta?.content ?: choice.message?.content,
                                        reasoningChunk = choice.delta?.reasoning ?: choice.message?.reasoning
                                    ).forEach { state ->
                                        state.toProviderEvent()?.let { emit(it) }
                                    }
                                    if (choice.finishReason == "length") {
                                        roundFailed = true
                                        emit(ProviderEvent.Failed(GROQ_OUTPUT_LIMIT_MESSAGE))
                                    } else {
                                        assembler.accept(
                                            content = null,
                                            reasoning = null,
                                            toolCalls = choice.delta?.toolCalls,
                                            finishReason = choice.finishReason
                                        ).forEach { emit(it) }
                                    }
                                }
                            }
                            reasoningParser.flush().forEach { state ->
                                state.toProviderEvent()?.let { emit(it) }
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            roundFailed = true
                            lastFailedMessage = t.message
                            if (ApiCredentialRotator.isRotatableError(t) && attempt < attempts - 1) {
                                canRotate = true
                            } else {
                                emit(ProviderEvent.Failed(t.message ?: "Groq stream request failed"))
                                return@flow
                            }
                        }

                        if (!roundFailed) {
                            emit(ProviderEvent.Completed)
                            return@flow
                        } else if (canRotate && attempt < attempts - 1) {
                            continue
                        } else {
                            if (lastFailedMessage != null && canRotate) emit(ProviderEvent.Failed(lastFailedMessage!!))
                            return@flow
                        }
                    }

                    val effectiveTemperature = if (isOllama && parsedOllamaOptions != null) {
                        parsedOllamaOptions.temperature ?: platform.temperature
                    } else {
                        platform.temperature
                    }
                    val effectiveTopP = if (isOllama && parsedOllamaOptions != null) {
                        parsedOllamaOptions.topP ?: platform.topP
                    } else {
                        platform.topP
                    }
                    val effectiveStop = if (isOllama && parsedOllamaOptions != null) {
                        parsedOllamaOptions.stop
                    } else {
                        null
                    }

                    val request = ChatCompletionRequest(
                        model = platform.model,
                        messages = messages,
                        stream = platform.stream,
                        temperature = effectiveTemperature,
                        topP = effectiveTopP,
                        stop = effectiveStop,
                        tools = requestTools,
                        provider = parsedRouting,
                        reasoning = if (isOpenRouter && platform.reasoning) OpenRouterReasoning(effort = "medium") else null,
                        options = parsedOllamaOptions
                    )
                    val assembler = ChatCompletionsEventAssembler()

                    try {
                        openAIAPI.streamChatCompletion(request, platform.timeout, config).collect { chunk ->
                            chunk.error?.let { error ->
                                roundFailed = true
                                lastFailedMessage = error.message
                                if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(error.message)) {
                                    canRotate = true
                                } else {
                                    emit(ProviderEvent.Failed(error.message))
                                }
                            } ?: chunk.choices.orEmpty().forEach { choice ->
                                assembler.accept(
                                    content = choice.delta.content,
                                    reasoning = choice.delta.reasoning,
                                    toolCalls = choice.delta.toolCalls,
                                    finishReason = choice.finishReason
                                ).forEach { emit(it) }
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        roundFailed = true
                        lastFailedMessage = t.message
                        if (ApiCredentialRotator.isRotatableError(t) && attempt < attempts - 1) {
                            canRotate = true
                        } else {
                            emit(ProviderEvent.Failed(t.message ?: "OpenAI-compatible stream request failed"))
                            return@flow
                        }
                    }

                    if (!roundFailed) {
                        emit(ProviderEvent.Completed)
                        return@flow
                    } else if (canRotate && attempt < attempts - 1) {
                        continue
                    } else {
                        if (lastFailedMessage != null && canRotate) emit(ProviderEvent.Failed(lastFailedMessage!!))
                        return@flow
                    }
                }
            }
        }
    }
}
