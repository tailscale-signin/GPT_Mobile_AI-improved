package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolResultContent as AnthropicToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolUseContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.AnthropicTool
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.ThinkingConfig as AnthropicThinkingConfig
import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.FunctionCall
import dev.chungjungsoo.gptmobile.data.dto.google.common.FunctionResponse
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.common.Role as GoogleRole
import dev.chungjungsoo.gptmobile.data.dto.google.request.FunctionDeclaration
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerationConfig
import dev.chungjungsoo.gptmobile.data.dto.google.request.GoogleFunctionCallingConfig
import dev.chungjungsoo.gptmobile.data.dto.google.request.GoogleTool
import dev.chungjungsoo.gptmobile.data.dto.google.request.GoogleToolConfig
import dev.chungjungsoo.gptmobile.data.dto.google.request.SafetySetting
import dev.chungjungsoo.gptmobile.data.dto.google.request.ThinkingConfig as GoogleThinkingConfig
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role as OpenAIRole
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent as OpenAITextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatFunction
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatFunctionTool
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatToolCall
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ReasoningConfig
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionCallOutput
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionTool
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCompletedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCreatedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseInProgressEvent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.GeminiSafetySettings
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterReasoning
import dev.chungjungsoo.gptmobile.data.repository.GroqReasoningParser
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OpenAIResponsesAdapter @Inject constructor(
    private val api: OpenAIAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialInput = attachmentEncoder.responsesInput(turns, platform.uid)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        var previousResponseId: String? = null
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val request = ResponsesRequest(
                    model = platform.model,
                    input = if (exchanges.isEmpty()) {
                        initialInput
                    } else {
                        exchanges.last().results.map { result ->
                            ResponseFunctionCallOutput(result.callId, result.modelText())
                        }
                    },
                    stream = true,
                    instructions = platform.systemPrompt?.takeIf { it.isNotBlank() },
                    temperature = if (platform.reasoning) null else platform.temperature,
                    topP = if (platform.reasoning) null else platform.topP,
                    reasoning = if (platform.reasoning) ReasoningConfig(effort = "medium", summary = "auto") else null,
                    previousResponseId = previousResponseId,
                    tools = tools.takeIf { it.isNotEmpty() }?.map { definition ->
                        ResponseFunctionTool(definition.name, definition.description, definition.inputSchema)
                    }
                )

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val config = ProviderRequestConfig(platform.apiUrl, activeKey)
                    val assembler = OpenAIResponsesEventAssembler()
                    var roundFailed = false
                    var canRotate = false

                    try {
                        api.streamResponses(request, platform.timeout, config).collect { event ->
                            when (event) {
                                is ResponseCreatedEvent -> previousResponseId = event.response.id
                                is ResponseInProgressEvent -> previousResponseId = event.response.id
                                is ResponseCompletedEvent -> previousResponseId = event.response.id
                                is ResponseFailedEvent -> previousResponseId = event.response.id
                                else -> Unit
                            }
                            assembler.accept(event).forEach { mapped ->
                                when (mapped) {
                                    ProviderEvent.Completed -> Unit

                                    is ProviderEvent.Failed -> {
                                        roundFailed = true
                                        lastFailedMessage = mapped.message
                                        if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(mapped.message)) {
                                            canRotate = true
                                        } else {
                                            emit(mapped)
                                        }
                                    }

                                    else -> emit(mapped)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        roundFailed = true
                        lastFailedMessage = t.message
                        if (ApiCredentialRotator.isRotatableError(t) && attempt < attempts - 1) {
                            canRotate = true
                        } else {
                            emit(ProviderEvent.Failed(t.message ?: "OpenAI stream request failed"))
                            return@flow
                        }
                    }

                    if (!roundFailed) {
                        emit(ProviderEvent.Completed)
                        return@flow
                    } else if (canRotate && attempt < attempts - 1) {
                        continue
                    } else if (roundFailed) {
                        lastFailedMessage?.let { emit(ProviderEvent.Failed(it)) }
                        return@flow
                    }
                }
            }
        }
    }
}

class OpenAICompatibleAdapter @Inject constructor(
    private val openAIAPI: OpenAIAPI,
    private val groqAPI: GroqAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialMessages = attachmentEncoder.openAIChatMessages(turns, platform.systemPrompt)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val messages = (initialMessages + exchanges.flatMap { it.toChatMessages() }).toMutableList()
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

                // Parse OpenRouter options or fallback to routing if legacy
                val (parsedOpenRouterOptions, parsedRouting) = if (isOpenRouter) {
                    if (!platform.openRouterRouting.isNullOrBlank()) {
                        val asOptions = runCatching { json.decodeFromString<OpenRouterOptions>(platform.openRouterRouting) }.getOrNull()
                        if (asOptions != null && (asOptions.provider != null || asOptions.maxTokens != null || asOptions.stream != null || asOptions.repetitionPenalty != null || asOptions.seed != null)) {
                            asOptions to asOptions.provider
                        } else {
                            val routing = runCatching { json.decodeFromString<OpenRouterProviderRouting>(platform.openRouterRouting) }.getOrNull()
                            null to routing
                        }
                    } else {
                        val defaultOpts = OpenRouterOptions.createDefault()
                        defaultOpts to defaultOpts.provider
                    }
                } else {
                    null to null
                }

                val parsedOllamaOptions = if (isOllama && !platform.ollamaOptions.isNullOrBlank()) {
                    runCatching { json.decodeFromString<OllamaOptions>(platform.ollamaOptions) }.getOrNull()
                } else if (isOllama) {
                    OllamaOptions.createDefault()
                } else {
                    null
                }

                val maxAutoContinues = parsedOllamaOptions?.maxAutoContinues ?: OllamaOptions.DEFAULT_MAX_AUTO_CONTINUES
                val isAutoContinueEnabled = parsedOllamaOptions?.autoContinue == true
                var autoContinueCount = 0

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

                    val effectiveTemperature = if (isOpenRouter && parsedOpenRouterOptions?.temperature != null) {
                        parsedOpenRouterOptions.temperature
                    } else if (isOllama && parsedOllamaOptions != null) {
                        parsedOllamaOptions.temperature ?: platform.temperature
                    } else {
                        platform.temperature
                    }

                    val effectiveTopP = if (isOpenRouter && parsedOpenRouterOptions?.topP != null) {
                        parsedOpenRouterOptions.topP
                    } else if (isOllama && parsedOllamaOptions != null) {
                        parsedOllamaOptions.topP ?: platform.topP
                    } else {
                        platform.topP
                    }

                    val effectiveTopK = if (isOpenRouter) {
                        parsedOpenRouterOptions?.topK ?: platform.topK
                    } else {
                        platform.topK
                    }

                    val effectiveMaxTokens = if (isOpenRouter && parsedOpenRouterOptions?.maxTokens != null) {
                        parsedOpenRouterOptions.maxTokens
                    } else {
                        platform.maxTokens
                    }

                    val effectiveStream = if (isOpenRouter && parsedOpenRouterOptions?.stream != null) {
                        parsedOpenRouterOptions.stream
                    } else {
                        platform.stream
                    }

                    val effectiveFrequencyPenalty = if (isOpenRouter) {
                        parsedOpenRouterOptions?.frequencyPenalty
                    } else {
                        null
                    }

                    val effectivePresencePenalty = if (isOpenRouter) {
                        parsedOpenRouterOptions?.presencePenalty
                    } else {
                        null
                    }

                    val effectiveRepetitionPenalty = if (isOpenRouter) {
                        parsedOpenRouterOptions?.repetitionPenalty
                    } else {
                        null
                    }

                    val effectiveSeed = if (isOpenRouter) {
                        parsedOpenRouterOptions?.seed
                    } else {
                        null
                    }

                    val effectiveStop = if (isOllama && parsedOllamaOptions != null) {
                        parsedOllamaOptions.stop
                    } else {
                        null
                    }

                    var currentRequestMessages = messages.toList()

                    while (true) {
                        val request = ChatCompletionRequest(
                            model = platform.model,
                            messages = currentRequestMessages,
                            stream = effectiveStream,
                            temperature = effectiveTemperature,
                            topP = effectiveTopP,
                            topK = effectiveTopK,
                            maxTokens = effectiveMaxTokens,
                            frequencyPenalty = effectiveFrequencyPenalty,
                            presencePenalty = effectivePresencePenalty,
                            repetitionPenalty = effectiveRepetitionPenalty,
                            seed = effectiveSeed,
                            stop = effectiveStop,
                            tools = requestTools,
                            provider = parsedRouting,
                            reasoning = if (isOpenRouter && platform.reasoning) OpenRouterReasoning(effort = "medium") else null,
                            options = parsedOllamaOptions
                        )
                        val assembler = ChatCompletionsEventAssembler()
                        var lastFinishReason: String? = null

                        if (isOllama) {
                            // Ollama platform timeout resilience:
                            // Never fail because of timeout. Continue retrying over and over for up to 5 minutes.
                            // If still nothing after 5 minutes, wrap up and emit an incomplete AI response.
                            val maxRetryDurationMs = 5 * 60 * 1000L
                            val startTime = System.currentTimeMillis()
                            var hasReceivedTokens = false
                            var ollamaSucceeded = false
                            var emulatorFallbackTried = false
                            var currentConfig = config

                            while (System.currentTimeMillis() - startTime < maxRetryDurationMs) {
                                var chunkError: String? = null
                                var caughtThrowable: Throwable? = null

                                // For Ollama streaming during the resilience loop, provide an extended per-chunk timeout (180s)
                                // if the platform timeout is configured lower (e.g. 0 or 30s), so prompt evaluation on larger models has room.
                                val effectiveOllamaTimeout = maxOf(platform.timeout, 180)

                                try {
                                    openAIAPI.streamChatCompletion(request, effectiveOllamaTimeout, currentConfig).collect { chunk ->
                                        chunk.error?.let { err ->
                                            chunkError = err.message
                                        } ?: chunk.choices.orEmpty().forEach { choice ->
                                            hasReceivedTokens = true
                                            choice.finishReason?.let { lastFinishReason = it }
                                            assembler.accept(
                                                content = choice.delta?.content ?: choice.message?.content,
                                                reasoning = choice.delta?.reasoning ?: choice.message?.reasoning,
                                                toolCalls = choice.delta?.toolCalls ?: choice.message?.toolCalls,
                                                finishReason = choice.finishReason
                                            ).forEach { emit(it) }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    if (t is CancellationException) throw t
                                    caughtThrowable = t
                                }

                                val rawError = chunkError ?: caughtThrowable?.message
                                val isTimeoutOrConnection = isOllamaTimeoutOrNetworkGlitch(rawError, caughtThrowable)

                                if (chunkError == null && caughtThrowable == null) {
                                    ollamaSucceeded = true
                                    break
                                } else if (isTimeoutOrConnection) {
                                    // If connecting to localhost or 127.0.0.1 fails immediately, try switching to 10.0.2.2 for Android emulator
                                    if (!emulatorFallbackTried && isLocalLoopbackUrl(currentConfig.apiUrl)) {
                                        val fallbackUrl = rewriteLoopbackForEmulator(currentConfig.apiUrl)
                                        if (fallbackUrl != currentConfig.apiUrl) {
                                            emulatorFallbackTried = true
                                            currentConfig = currentConfig.copy(apiUrl = fallbackUrl)
                                            emit(ProviderEvent.Notice("Ollama localhost connection failed. Retrying with emulator alias ($fallbackUrl)..."))
                                            delay(1000L)
                                            continue
                                        }
                                    }

                                    val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                                    val remainingMs = maxRetryDurationMs - (System.currentTimeMillis() - startTime)
                                    if (remainingMs > 0) {
                                        emit(ProviderEvent.Notice("Ollama response timed out. Retrying (elapsed: ${elapsedSeconds}s)..."))
                                        delay(minOf(2000L, remainingMs))
                                        continue
                                    } else {
                                        break
                                    }
                                } else {
                                    // Non-timeout error (e.g. invalid model, bad JSON, unauthorized)
                                    emit(ProviderEvent.Failed(rawError ?: "Ollama request failed"))
                                    return@flow
                                }
                            }

                            if (ollamaSucceeded) {
                                if (isAutoContinueEnabled && lastFinishReason == "length" && autoContinueCount < maxAutoContinues) {
                                    autoContinueCount++
                                    emit(ProviderEvent.Notice("Auto-continuing response ($autoContinueCount/$maxAutoContinues)..."))
                                    currentRequestMessages = currentRequestMessages + ChatMessage(
                                        role = OpenAIRole.USER,
                                        content = OpenAITextContent("continue")
                                    )
                                    continue
                                }
                                emit(ProviderEvent.Completed)
                                return@flow
                            } else {
                                // 5 minutes elapsed with timeouts / network glitches:
                                // Cleanly wrap up with incomplete response notice and completed event
                                val wrapUpNotice = if (hasReceivedTokens) {
                                    "\n\n[Response incomplete: Ollama server timed out after 5 minutes]"
                                } else {
                                    "[Response incomplete: Ollama server timed out after 5 minutes with no response]"
                                }
                                emit(ProviderEvent.TextDelta(wrapUpNotice))
                                emit(ProviderEvent.Completed)
                                return@flow
                            }
                        }

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
                                        content = choice.delta?.content ?: choice.message?.content,
                                        reasoning = choice.delta?.reasoning ?: choice.message?.reasoning,
                                        toolCalls = choice.delta?.toolCalls ?: choice.message?.toolCalls,
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
                            break
                        } else {
                            if (lastFailedMessage != null && canRotate) emit(ProviderEvent.Failed(lastFailedMessage!!))
                            return@flow
                        }
                    }
                }
            }
        }
    }

    private fun isLocalLoopbackUrl(url: String): Boolean = runCatching {
        val uri = URI(if ("://" in url) url else "http://$url")
        val host = uri.host?.lowercase() ?: ""
        host == "localhost" || host == "127.0.0.1"
    }.getOrDefault(false)

    private fun rewriteLoopbackForEmulator(url: String): String =
        url.replace("://localhost", "://10.0.2.2")
            .replace("://127.0.0.1", "://10.0.2.2")

    private fun isOllamaTimeoutOrNetworkGlitch(errorMsg: String?, throwable: Throwable?): Boolean {
        if (throwable != null) {
            when (throwable) {
                is io.ktor.client.plugins.HttpRequestTimeoutException,
                is java.net.SocketTimeoutException,
                is java.net.ConnectException,
                is java.net.SocketException,
                is java.io.InterruptedIOException,
                is java.net.UnknownHostException,
                is java.nio.channels.UnresolvedAddressException,
                is javax.net.ssl.SSLException -> return true
            }
        }
        if (errorMsg != null) {
            val lower = errorMsg.lowercase()
            if (lower.contains("timed out") ||
                lower.contains("timeout") ||
                lower.contains("connection refused") ||
                lower.contains("failed to connect") ||
                lower.contains("network error") ||
                lower.contains("reset by peer") ||
                lower.contains("socket closed") ||
                lower.contains("broken pipe") ||
                lower.contains("unable to resolve") ||
                lower.contains("unresolved address") ||
                lower.contains("ssl/tls connection failed") ||
                lower.contains("http 408") ||
                lower.contains("http 502") ||
                lower.contains("http 503") ||
                lower.contains("http 504") ||
                lower.contains("bad gateway") ||
                lower.contains("gateway timeout") ||
                lower.contains("service unavailable") ||
                lower.contains("loading model")
            ) {
                return true
            }
        }
        return false
    }
}

class AnthropicMessagesAdapter @Inject constructor(
    private val api: AnthropicAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialMessages = attachmentEncoder.anthropicMessages(turns, platform.uid)
        val assistantContentByRound = mutableMapOf<Int, List<MessageContent>>()
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val thinkingPolicy = anthropicThinkingPolicy(
                    model = platform.model,
                    reasoningEnabled = platform.reasoning,
                    hasTools = tools.isNotEmpty()
                )
                val isThinkingActive = thinkingPolicy.config?.type?.let { it != "disabled" } == true
                val request = MessageRequest(
                    model = platform.model,
                    messages = initialMessages + exchanges.flatMapIndexed { index, exchange ->
                        exchange.toAnthropicMessages(assistantContentByRound[index])
                    },
                    maxTokens = if (isThinkingActive) 16000 else 4096,
                    stream = platform.stream,
                    systemPrompt = platform.systemPrompt,
                    temperature = if (isThinkingActive) null else platform.temperature,
                    topP = if (isThinkingActive) null else platform.topP,
                    thinking = thinkingPolicy.config,
                    tools = tools.takeIf { it.isNotEmpty() }?.map { definition ->
                        AnthropicTool(definition.name, definition.description, definition.inputSchema)
                    }
                )

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val assembler = AnthropicEventAssembler()
                    var roundFailed = false
                    var canRotate = false

                    try {
                        api.streamChatMessage(
                            request,
                            platform.timeout,
                            ProviderRequestConfig(
                                apiUrl = platform.apiUrl,
                                token = activeKey,
                                anthropicBetaFeatures = thinkingPolicy.betaFeatures
                            )
                        ).collect { chunk ->
                            assembler.accept(chunk).forEach { mapped ->
                                when (mapped) {
                                    ProviderEvent.Completed -> Unit

                                    is ProviderEvent.Failed -> {
                                        roundFailed = true
                                        lastFailedMessage = mapped.message
                                        if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(mapped.message)) {
                                            canRotate = true
                                        } else {
                                            emit(mapped)
                                        }
                                    }

                                    else -> emit(mapped)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        roundFailed = true
                        lastFailedMessage = t.message
                        if (ApiCredentialRotator.isRotatableError(t) && attempt < attempts - 1) {
                            canRotate = true
                        } else {
                            emit(ProviderEvent.Failed(t.message ?: "Anthropic stream request failed"))
                            return@flow
                        }
                    }

                    if (!roundFailed) {
                        assembler.replayContent().takeIf { it.isNotEmpty() }?.let { assistantContentByRound[exchanges.size] = it }
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

internal data class AnthropicThinkingPolicy(
    val config: AnthropicThinkingConfig?,
    val betaFeatures: Set<String>
)

internal fun anthropicThinkingPolicy(
    model: String,
    reasoningEnabled: Boolean,
    hasTools: Boolean
): AnthropicThinkingPolicy {
    val normalizedModel = model.lowercase()
    if (!reasoningEnabled) {
        val config = AnthropicThinkingConfig(type = "disabled")
            .takeIf { DEFAULT_ON_DISABLEABLE_ANTHROPIC_MODEL_PATTERN.containsMatchIn(normalizedModel) }
        return AnthropicThinkingPolicy(config = config, betaFeatures = emptySet())
    }

    val usesAdaptiveThinking = ADAPTIVE_ANTHROPIC_MODEL_PATTERN.containsMatchIn(normalizedModel) ||
        normalizedModel.contains("mythos") ||
        normalizedModel.contains("fable")
    if (usesAdaptiveThinking) {
        return AnthropicThinkingPolicy(
            config = AnthropicThinkingConfig(type = "adaptive", display = "summarized"),
            betaFeatures = emptySet()
        )
    }
    if (!MANUAL_THINKING_ANTHROPIC_MODEL_PATTERN.containsMatchIn(normalizedModel)) {
        return AnthropicThinkingPolicy(config = null, betaFeatures = emptySet())
    }

    val supportsManualInterleaving = hasTools &&
        (normalizedModel.contains("opus") || normalizedModel.contains("sonnet")) &&
        MANUAL_INTERLEAVED_ANTHROPIC_MODEL_PATTERN.containsMatchIn(normalizedModel)
    return AnthropicThinkingPolicy(
        config = AnthropicThinkingConfig(type = "enabled", budgetTokens = 10_000, display = "summarized"),
        betaFeatures = if (supportsManualInterleaving) setOf(ANTHROPIC_INTERLEAVED_THINKING_BETA) else emptySet()
    )
}

internal const val ANTHROPIC_INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14"
private val ADAPTIVE_ANTHROPIC_MODEL_PATTERN = Regex(
    "(?:^|-)4-(?:6|7|8)(?:-|$)|claude-(?:opus|sonnet|haiku)-5(?:-|$)|claude-5-(?:opus|sonnet|haiku)(?:-|$)"
)
private val DEFAULT_ON_DISABLEABLE_ANTHROPIC_MODEL_PATTERN =
    Regex("claude-(?:opus|sonnet)-5(?:-|$)|claude-5-(?:opus|sonnet)(?:-|$)")
private val MANUAL_THINKING_ANTHROPIC_MODEL_PATTERN = Regex("(?:^|-)3-7(?:-|$)|(?:^|-)4(?:-|$)")
private val MANUAL_INTERLEAVED_ANTHROPIC_MODEL_PATTERN = Regex("(?:^|-)4(?:-|$)")

private val GEMINI_DISALLOWED_SCHEMA_KEYWORDS = setOf(
    "additionalProperties",
    "x-mcp-header",
    "x-mcp-param",
    "\$schema",
    "\$id",
    "\$ref",
    "propertyNames",
    "patternProperties"
)

internal fun geminiToolParameters(schema: JsonObject): JsonObject {
    val hasDisallowedKeyword = schema.keys.any { it in GEMINI_DISALLOWED_SCHEMA_KEYWORDS }
    if (!hasDisallowedKeyword && schema.values.none { it is JsonObject || it is JsonArray }) {
        return schema
    }
    return buildJsonObject {
        schema.forEach { (key, value) ->
            if (key in GEMINI_DISALLOWED_SCHEMA_KEYWORDS) return@forEach
            put(key, if (key in SCHEMA_MAP_KEYWORDS) sanitizeGeminiSchemaMap(value) else sanitizeGeminiSchemaElement(value))
        }
    }
}

// Keys under these keywords are caller-defined names, so a property literally named
// after a disallowed keyword must survive while the keyword itself is stripped everywhere else.
private val SCHEMA_MAP_KEYWORDS = setOf("properties", "definitions", "\$defs")

private fun sanitizeGeminiSchemaMap(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> buildJsonObject {
        value.forEach { (name, member) -> put(name, sanitizeGeminiSchemaElement(member)) }
    }

    else -> sanitizeGeminiSchemaElement(value)
}

private fun sanitizeGeminiSchemaElement(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> geminiToolParameters(value)
    is JsonArray -> JsonArray(value.map(::sanitizeGeminiSchemaElement))
    else -> value
}

class GeminiAdapter @Inject constructor(
    private val api: GoogleAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialContents = attachmentEncoder.googleContents(turns, platform.uid)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        val modelPartsByRound = mutableMapOf<Int, List<Part>>()
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val request = GenerateContentRequest(
                    contents = initialContents + exchanges.flatMapIndexed { index, exchange ->
                        exchange.toGeminiContents(modelPartsByRound[index])
                    },
                    generationConfig = GenerationConfig(
                        temperature = platform.temperature,
                        topP = platform.topP,
                        topK = platform.topK,
                        thinkingConfig = if (platform.reasoning) GoogleThinkingConfig(thinkingBudget = 8_192) else null
                    ),
                    safetySettings = platform.googleSafetySettings(),
                    systemInstruction = platform.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                        Content(parts = listOf(Part.text(prompt)))
                    },
                    tools = tools.takeIf { it.isNotEmpty() }?.let { defs ->
                        listOf(
                            GoogleTool(
                                functionDeclarations = defs.map { def ->
                                    FunctionDeclaration(def.name, def.description, geminiToolParameters(def.inputSchema))
                                }
                            )
                        )
                    },
                    toolConfig = tools.takeIf { it.isNotEmpty() }?.let {
                        GoogleToolConfig(functionCallingConfig = GoogleFunctionCallingConfig(mode = "AUTO"))
                    }
                )

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val assembler = GeminiEventAssembler()
                    var roundFailed = false
                    var canRotate = false

                    try {
                        api.streamGenerateContent(request, platform.timeout, ProviderRequestConfig(platform.apiUrl, activeKey)).collect { chunk ->
                            assembler.accept(chunk).forEach { mapped ->
                                when (mapped) {
                                    ProviderEvent.Completed -> Unit

                                    is ProviderEvent.Failed -> {
                                        roundFailed = true
                                        lastFailedMessage = mapped.message
                                        if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(mapped.message)) {
                                            canRotate = true
                                        } else {
                                            emit(mapped)
                                        }
                                    }

                                    else -> emit(mapped)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        roundFailed = true
                        lastFailedMessage = t.message
                        if (ApiCredentialRotator.isRotatableError(t) && attempt < attempts - 1) {
                            canRotate = true
                        } else {
                            emit(ProviderEvent.Failed(t.message ?: "Gemini stream request failed"))
                            return@flow
                        }
                    }

                    if (!roundFailed) {
                        assembler.replayParts().takeIf { it.isNotEmpty() }?.let { modelPartsByRound[exchanges.size] = it }
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

private fun AgentToolExchange.toChatMessages(): List<ChatMessage> {
    val assistantCalls = calls.map { call ->
        ChatToolCall(
            id = call.id,
            function = ChatFunction(call.name, call.arguments.toString())
        )
    }
    return listOf(
        ChatMessage(
            role = OpenAIRole.ASSISTANT,
            content = OpenAITextContent(""),
            toolCalls = assistantCalls
        )
    ) + results.map { result ->
        ChatMessage(
            role = OpenAIRole.TOOL,
            toolCallId = result.callId,
            content = OpenAITextContent(result.modelText())
        )
    }
}

private fun AgentToolExchange.toAnthropicMessages(assistantContent: List<MessageContent>?): List<InputMessage> {
    val assistant = InputMessage(
        role = MessageRole.ASSISTANT,
        content = assistantContent.orEmpty() + calls.map { call ->
            ToolUseContent(
                id = call.id,
                name = call.name,
                input = call.arguments
            )
        }
    )
    val userResults = InputMessage(
        role = MessageRole.USER,
        content = results.map { result ->
            AnthropicToolResultContent(
                toolUseId = result.callId,
                content = result.modelText(),
                isError = result.isError
            )
        }
    )
    return listOf(assistant, userResults)
}

private fun AgentToolExchange.toGeminiContents(modelParts: List<Part>?): List<Content> {
    val model = Content(
        role = GoogleRole.MODEL,
        parts = modelParts.orEmpty() + calls.map { call ->
            Part(functionCall = FunctionCall(name = call.name, args = call.arguments))
        }
    )
    val user = Content(
        role = GoogleRole.USER,
        parts = results.map { result ->
            Part(
                functionResponse = FunctionResponse(
                    name = result.name,
                    response = result.modelJson()
                )
            )
        }
    )
    return listOf(model, user)
}

private fun dev.chungjungsoo.gptmobile.data.agent.AgentToolResult.modelText(): String = when (val value = content) {
    is ToolResultContent.Text -> value.text
    is ToolResultContent.Json -> value.value.toString()
    is ToolResultContent.ResourceLinks -> value.links.joinToString("\n") { link -> link.uri }
}

private fun dev.chungjungsoo.gptmobile.data.agent.AgentToolResult.modelJson(): JsonObject = when (val value = content) {
    is ToolResultContent.Json -> value.value.asResponseObject()

    is ToolResultContent.Text -> buildJsonObject {
        put("result", value.text)
        if (isError) put("isError", true)
    }

    is ToolResultContent.ResourceLinks -> buildJsonObject {
        put("resources", JsonArray(value.links.map { link -> JsonPrimitive(link.uri) }))
        if (isError) put("isError", true)
    }
}

private fun JsonElement.asResponseObject(): JsonObject = this as? JsonObject ?: buildJsonObject { put("result", this@asResponseObject) }

private fun PlatformV2.googleSafetySettings(): List<SafetySetting> = listOf(
    SafetySetting(
        GeminiSafetySettings.HARM_CATEGORY_HARASSMENT,
        GeminiSafetySettings.normalizeThreshold(harassmentSafetyThreshold)
    ),
    SafetySetting(
        GeminiSafetySettings.HARM_CATEGORY_HATE_SPEECH,
        GeminiSafetySettings.normalizeThreshold(hateSpeechSafetyThreshold)
    ),
    SafetySetting(
        GeminiSafetySettings.HARM_CATEGORY_SEXUALLY_EXPLICIT,
        GeminiSafetySettings.normalizeThreshold(sexuallyExplicitSafetyThreshold)
    ),
    SafetySetting(
        GeminiSafetySettings.HARM_CATEGORY_DANGEROUS_CONTENT,
        GeminiSafetySettings.normalizeThreshold(dangerousContentSafetyThreshold)
    )
)

private fun createGroqChatCompletionRequest(
    messages: List<ChatMessage>,
    platform: PlatformV2
): GroqChatCompletionRequest {
    val isGptOssModel = platform.model.contains("gpt-oss", ignoreCase = true)
    return GroqChatCompletionRequest(
        model = platform.model,
        messages = messages,
        stream = platform.stream,
        temperature = platform.temperature,
        topP = platform.topP,
        maxCompletionTokens = if (platform.reasoning) 8_192 else null,
        reasoningEffort = if (platform.reasoning && isGptOssModel) "medium" else null,
        reasoningFormat = when {
            platform.reasoning && !isGptOssModel -> "parsed"
            !platform.reasoning && !isGptOssModel -> "hidden"
            else -> null
        },
        includeReasoning = when {
            platform.reasoning && isGptOssModel -> true
            !platform.reasoning && isGptOssModel -> false
            else -> null
        }
    )
}

private const val GROQ_OUTPUT_LIMIT_MESSAGE =
    "Groq reached the model output limit before producing a final answer."
