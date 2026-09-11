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
import dev.chungjungsoo.gptmobile.data.network.GeminiAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterReasoning
import dev.chungjungsoo.gptmobile.util.GROQ_OUTPUT_LIMIT_MESSAGE
import dev.chungjungsoo.gptmobile.util.GroqReasoningParser
import dev.chungjungsoo.gptmobile.util.anthropicThinkingPolicy
import dev.chungjungsoo.gptmobile.util.geminiThinkingPolicy
import dev.chungjungsoo.gptmobile.util.openAIReasoningConfig
import dev.chungjungsoo.gptmobile.util.sanitizeAssistantOutput
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class OpenAIResponsesAdapter @Inject constructor(
    private val api: OpenAIAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialInput = attachmentEncoder.responsesInput(turns, platform.uid)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val input = initialInput + exchanges.flatMap { it.toResponseInputs() }
                val requestTools = tools.takeIf { it.isNotEmpty() }?.map { definition ->
                    ResponseFunctionTool(definition.name, definition.description, definition.inputSchema)
                }

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val config = ProviderRequestConfig(
                        apiUrl = platform.apiUrl,
                        token = activeKey
                    )
                    var roundFailed = false
                    var canRotate = false

                    val reasoningConfig = openAIReasoningConfig(
                        model = platform.model,
                        reasoningEffort = platform.reasoningEffort,
                        reasoningEnabled = platform.reasoning
                    )
                    val request = ResponsesRequest(
                        model = platform.model,
                        input = input,
                        stream = platform.stream,
                        temperature = platform.temperature,
                        topP = platform.topP,
                        maxOutputTokens = platform.maxTokens,
                        reasoning = reasoningConfig,
                        tools = requestTools
                    )
                    val assembler = ResponsesEventAssembler()

                    try {
                        api.streamResponses(request, platform.timeout, config).collect { event ->
                            when (event) {
                                is ResponseCreatedEvent -> {}
                                is ResponseInProgressEvent -> {
                                    assembler.accept(event.response).forEach { emit(it) }
                                }
                                is ResponseCompletedEvent -> {
                                    assembler.accept(event.response).forEach { emit(it) }
                                }
                                is ResponseFailedEvent -> {
                                    roundFailed = true
                                    lastFailedMessage = event.error.message
                                    if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(event.error.message)) {
                                        canRotate = true
                                    } else {
                                        emit(ProviderEvent.Failed(event.error.message))
                                    }
                                }
                                else -> {}
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
                    } else {
                        if (lastFailedMessage != null && canRotate) emit(ProviderEvent.Failed(lastFailedMessage!!))
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
        val initialMessages = attachmentEncoder.openAIChatMessages(turns, platform.uid)
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

                    val request = ChatCompletionRequest(
                        model = platform.model,
                        messages = messages,
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

    private fun isLocalLoopbackUrl(url: String): Boolean {
        return runCatching {
            val uri = URI(if ("://" in url) url else "http://$url")
            val host = uri.host?.lowercase() ?: ""
            host == "localhost" || host == "127.0.0.1"
        }.getOrDefault(false)
    }

    private fun rewriteLoopbackForEmulator(url: String): String {
        return url.replace("://localhost", "://10.0.2.2")
            .replace("://127.0.0.1", "://10.0.2.2")
    }

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
                    messages = initialMessages + buildAnthropicExchanges(assistantContentByRound, exchanges),
                    system = platform.systemPrompt,
                    maxTokens = platform.maxTokens,
                    temperature = if (isThinkingActive) null else platform.temperature,
                    topP = if (isThinkingActive) null else platform.topP,
                    topK = if (isThinkingActive) null else platform.topK,
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
                    val config = ProviderRequestConfig(
                        apiUrl = platform.apiUrl,
                        token = activeKey,
                        anthropicBetaFeatures = thinkingPolicy.betaFeatures
                    )
                    var roundFailed = false
                    var canRotate = false
                    val roundAssistantContent = mutableListOf<MessageContent>()

                    try {
                        api.streamMessage(request, platform.timeout, config).collect { chunk ->
                            chunk.error?.let { error ->
                                roundFailed = true
                                lastFailedMessage = error.message
                                if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(error.message)) {
                                    canRotate = true
                                } else {
                                    emit(ProviderEvent.Failed(error.message))
                                }
                            } ?: run {
                                when (chunk.type) {
                                    "content_block_start" -> {
                                        val contentBlock = chunk.contentBlock
                                        when (contentBlock?.type) {
                                            "thinking" -> {
                                                emit(ProviderEvent.ReasoningStarted)
                                                contentBlock.thinking?.let { emit(ProviderEvent.ReasoningDelta(it)) }
                                            }
                                            "tool_use" -> {
                                                val toolUse = ToolUseContent(
                                                    id = contentBlock.id.orEmpty(),
                                                    name = contentBlock.name.orEmpty(),
                                                    input = contentBlock.input ?: emptyMap()
                                                )
                                                roundAssistantContent.add(toolUse)
                                            }
                                            "text" -> {
                                                contentBlock.text?.let { emit(ProviderEvent.TextDelta(it)) }
                                            }
                                        }
                                    }
                                    "content_block_delta" -> {
                                        val delta = chunk.delta
                                        when (delta?.type) {
                                            "thinking_delta" -> {
                                                delta.thinking?.let { emit(ProviderEvent.ReasoningDelta(it)) }
                                            }
                                            "text_delta" -> {
                                                delta.text?.let { emit(ProviderEvent.TextDelta(it)) }
                                            }
                                            "input_json_delta" -> {
                                                delta.partialJson?.let { partial ->
                                                    val lastIndex = roundAssistantContent.indexOfLast { it is ToolUseContent }
                                                    if (lastIndex >= 0) {
                                                        val existing = roundAssistantContent[lastIndex] as ToolUseContent
                                                        roundAssistantContent[lastIndex] = existing.copy(
                                                            input = mergePartialJson(existing.input, partial)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "content_block_stop" -> {
                                        val lastTool = roundAssistantContent.lastOrNull() as? ToolUseContent
                                        if (lastTool != null && chunk.index != null) {
                                            emit(
                                                ProviderEvent.ToolCallRequested(
                                                    callId = lastTool.id,
                                                    toolName = lastTool.name,
                                                    arguments = lastTool.input
                                                )
                                            )
                                        }
                                    }
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
                        val currentRoundIndex = exchanges.size
                        assistantContentByRound[currentRoundIndex] = roundAssistantContent
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

    private fun mergePartialJson(current: Map<String, Any?>, partialJson: String): Map<String, Any?> {
        return current + ("_raw_delta" to ((current["_raw_delta"] as? String ?: "") + partialJson))
    }

    private fun buildAnthropicExchanges(
        assistantContentByRound: Map<Int, List<MessageContent>>,
        exchanges: List<AgentToolExchange>
    ): List<InputMessage> {
        val messages = mutableListOf<InputMessage>()
        exchanges.forEachIndexed { index, exchange ->
            val assistantContents = assistantContentByRound[index].orEmpty()
            if (assistantContents.isNotEmpty()) {
                messages.add(InputMessage(role = MessageRole.ASSISTANT, content = assistantContents))
            }
            messages.add(
                InputMessage(
                    role = MessageRole.USER,
                    content = exchange.results.map { result ->
                        AnthropicToolResultContent(
                            toolUseId = result.callId,
                            content = result.content.toRawText(),
                            isError = result.isError
                        )
                    }
                )
            )
        }
        return messages
    }
}

class GeminiAdapter @Inject constructor(
    private val api: GeminiAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialContents = attachmentEncoder.geminiContents(turns, platform.uid)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val thinkingPolicy = geminiThinkingPolicy(
                    model = platform.model,
                    reasoningEnabled = platform.reasoning
                )
                val isThinkingActive = thinkingPolicy.config?.thinkingBudget?.let { it > 0 } == true
                val request = GenerateContentRequest(
                    contents = initialContents + exchanges.flatMap { it.toGeminiContents() },
                    systemInstruction = platform.systemPrompt?.let { Content(parts = listOf(Part(text = it))) },
                    generationConfig = GenerationConfig(
                        temperature = if (isThinkingActive) null else platform.temperature,
                        topP = if (isThinkingActive) null else platform.topP,
                        topK = if (isThinkingActive) null else platform.topK,
                        maxOutputTokens = platform.maxTokens,
                        thinkingConfig = thinkingPolicy.config
                    ),
                    safetySettings = GeminiSafetySettings.DEFAULT_SETTINGS.map {
                        SafetySetting(category = it.category, threshold = it.threshold)
                    },
                    tools = tools.takeIf { it.isNotEmpty() }?.let { toolDefs ->
                        listOf(
                            GoogleTool(
                                functionDeclarations = toolDefs.map {
                                    FunctionDeclaration(it.name, it.description, it.inputSchema)
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
                    val config = ProviderRequestConfig(
                        apiUrl = platform.apiUrl,
                        token = activeKey
                    )
                    var roundFailed = false
                    var canRotate = false

                    try {
                        api.streamGenerateContent(platform.model, request, platform.timeout, config).collect { chunk ->
                            chunk.error?.let { error ->
                                roundFailed = true
                                lastFailedMessage = error.message
                                if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(error.message)) {
                                    canRotate = true
                                } else {
                                    emit(ProviderEvent.Failed(error.message))
                                }
                            } ?: chunk.candidates.orEmpty().forEach { candidate ->
                                candidate.content?.parts.orEmpty().forEach { part ->
                                    if (part.thought == true && !part.text.isNullOrEmpty()) {
                                        emit(ProviderEvent.ReasoningDelta(part.text))
                                    } else if (!part.text.isNullOrEmpty()) {
                                        emit(ProviderEvent.TextDelta(part.text))
                                    }
                                    part.functionCall?.let { call ->
                                        emit(
                                            ProviderEvent.ToolCallRequested(
                                                callId = call.id ?: "${call.name}_${System.currentTimeMillis()}",
                                                toolName = call.name,
                                                arguments = call.args.orEmpty()
                                            )
                                        )
                                    }
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
    val messages = mutableListOf<ChatMessage>()
    val assistantToolCalls = calls.map {
        ChatToolCall(
            id = it.callId,
            type = "function",
            function = dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatFunctionCall(
                name = it.toolName,
                arguments = it.arguments.toString()
            )
        )
    }
    messages.add(ChatMessage(role = OpenAIRole.ASSISTANT.role, toolCalls = assistantToolCalls))
    results.forEach { result ->
        messages.add(
            ChatMessage(
                role = OpenAIRole.TOOL.role,
                content = result.content.toRawText(),
                toolCallId = result.callId
            )
        )
    }
    return messages
}

private fun AgentToolExchange.toResponseInputs(): List<dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputItem> {
    return results.map { result ->
        dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputItem(
            type = "function_call_output",
            callId = result.callId,
            output = result.content.toRawText()
        )
    }
}

private fun AgentToolExchange.toGeminiContents(): List<Content> {
    return listOf(
        Content(
            role = GoogleRole.MODEL.role,
            parts = calls.map {
                Part(functionCall = FunctionCall(name = it.toolName, args = it.arguments))
            }
        ),
        Content(
            role = GoogleRole.USER.role,
            parts = results.map {
                Part(functionResponse = FunctionResponse(name = it.callId, response = mapOf("content" to it.content.toRawText())))
            }
        )
    )
}

private fun ToolResultContent.toRawText(): String = when (this) {
    is ToolResultContent.Text -> text
    is ToolResultContent.Json -> json
    is ToolResultContent.Image -> "[Image attached]"
}

private fun createGroqChatCompletionRequest(
    messages: List<ChatMessage>,
    platform: PlatformV2
): GroqChatCompletionRequest {
    return GroqChatCompletionRequest(
        model = platform.model,
        messages = messages.map { msg ->
            dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatMessage(
                role = msg.role,
                content = msg.content,
                toolCalls = msg.toolCalls?.map { call ->
                    dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqToolCall(
                        id = call.id,
                        type = call.type,
                        function = dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqFunctionCall(
                            name = call.function.name,
                            arguments = call.function.arguments
                        )
                    )
                },
                toolCallId = msg.toolCallId
            )
        },
        stream = platform.stream,
        temperature = platform.temperature,
        topP = platform.topP,
        maxTokens = platform.maxTokens
    )
}

internal class ChatCompletionsEventAssembler {
    private var inReasoning = false

    fun accept(
        content: String?,
        reasoning: String?,
        toolCalls: List<ChatToolCall>?,
        finishReason: String?
    ): List<ProviderEvent> {
        val events = mutableListOf<ProviderEvent>()
        if (!reasoning.isNullOrEmpty()) {
            if (!inReasoning) {
                inReasoning = true
                events.add(ProviderEvent.ReasoningStarted)
            }
            events.add(ProviderEvent.ReasoningDelta(reasoning))
        }
        if (!content.isNullOrEmpty()) {
            events.add(ProviderEvent.TextDelta(content))
        }
        toolCalls.orEmpty().forEach { call ->
            val args = runCatching {
                Json.decodeFromString<Map<String, Any?>>(call.function.arguments)
            }.getOrDefault(emptyMap())
            events.add(
                ProviderEvent.ToolCallRequested(
                    callId = call.id,
                    toolName = call.function.name,
                    arguments = args
                )
            )
        }
        return events
    }
}

internal class ResponsesEventAssembler {
    fun accept(response: dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsePayload?): List<ProviderEvent> {
        if (response == null) return emptyList()
        val events = mutableListOf<ProviderEvent>()
        response.output.orEmpty().forEach { output ->
            when (output.type) {
                "message" -> {
                    output.content.orEmpty().forEach { c ->
                        if (c.type == "text" && !c.text.isNullOrEmpty()) {
                            events.add(ProviderEvent.TextDelta(c.text))
                        }
                    }
                }
                "function_call" -> {
                    val args = runCatching {
                        Json.decodeFromString<Map<String, Any?>>(output.arguments.orEmpty())
                    }.getOrDefault(emptyMap())
                    events.add(
                        ProviderEvent.ToolCallRequested(
                            callId = output.callId.orEmpty(),
                            toolName = output.name.orEmpty(),
                            arguments = args
                        )
                    )
                }
            }
        }
        return events
    }
}
