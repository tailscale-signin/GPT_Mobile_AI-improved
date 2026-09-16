package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.ProviderContextPolicy
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.AnthropicRequest
import dev.chungjungsoo.gptmobile.data.dto.google.GeminiRequest
import dev.chungjungsoo.gptmobile.data.dto.google.GenerationConfig
import dev.chungjungsoo.gptmobile.data.dto.google.SafetySetting
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.OpenRouterReasoning
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.anthropicProviderConfig
import dev.chungjungsoo.gptmobile.data.network.geminiProviderConfig
import dev.chungjungsoo.gptmobile.data.network.groqProviderConfig
import dev.chungjungsoo.gptmobile.data.network.isLocalLoopbackUrl
import dev.chungjungsoo.gptmobile.data.network.isOllamaTimeoutOrNetworkGlitch
import dev.chungjungsoo.gptmobile.data.network.openAICompatibleProviderConfig
import dev.chungjungsoo.gptmobile.data.network.openAIProviderConfig
import dev.chungjungsoo.gptmobile.data.network.rewriteLoopbackForEmulator
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

private val jsonSerializer = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

class OpenAIResponsesAdapter(
    private val openAIAPI: OpenAIAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    fun createSession(
        platform: PlatformV2,
        turns: List<ConversationTurn>,
        tools: List<ResolvedAgentTool>,
        modelSnapshot: String,
        credentialRotator: ApiCredentialRotator? = null
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally: Boolean = false

        override fun streamRound(
            exposedTools: List<AgentToolDefinition>,
            exchanges: List<AgentToolExchange>
        ): Flow<ProviderEvent> = flow {
            var rotated = false
            while (true) {
                val currentToken = credentialRotator?.getCurrentToken() ?: platform.apiToken
                val config = openAIProviderConfig(platform.copy(apiToken = currentToken))
                val messages = ContextBuilder.buildOpenAIResponsesMessages(
                    turns = turns,
                    exchanges = exchanges,
                    attachmentEncoder = attachmentEncoder
                )
                val requestTools = ContextBuilder.buildOpenAITools(exposedTools)
                val request = ChatCompletionRequest(
                    model = platform.model,
                    messages = messages,
                    tools = requestTools,
                    temperature = platform.temperature,
                    topP = platform.topP,
                    maxTokens = platform.maxTokens
                )
                val assembler = OpenAIResponsesEventAssembler()
                var roundFailed = false
                var canRotate = false
                var lastFailedMessage: String? = null

                try {
                    openAIAPI.streamResponses(request, platform.timeout, config).collect { event ->
                        assembler.accept(event).forEach { emit(it) }
                    }
                    emit(ProviderEvent.Completed)
                    return@flow
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val message = t.message ?: "Request failed"
                    if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(message)) {
                        canRotate = true
                        lastFailedMessage = message
                    } else {
                        emit(ProviderEvent.Failed(message))
                        return@flow
                    }
                }

                if (canRotate && credentialRotator != null && credentialRotator.getTokens().size > 1) {
                    val next = credentialRotator.rotateToNextToken()
                    if (next != null) {
                        rotated = true
                        emit(ProviderEvent.Notice("Rate limited or quota exceeded. Rotated to next configured API key."))
                        continue
                    }
                }

                emit(ProviderEvent.Failed(lastFailedMessage ?: "Request failed"))
                return@flow
            }
        }
    }
}

class ChatCompletionsAdapter(
    private val openAIAPI: OpenAIAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    fun createSession(
        platform: PlatformV2,
        turns: List<ConversationTurn>,
        tools: List<ResolvedAgentTool>,
        modelSnapshot: String,
        credentialRotator: ApiCredentialRotator? = null
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally: Boolean = false

        override fun streamRound(
            exposedTools: List<AgentToolDefinition>,
            exchanges: List<AgentToolExchange>
        ): Flow<ProviderEvent> = flow {
            var rotated = false
            var autoContinueCount = 0

            val parsedOllamaOptions = if (platform.compatibleType == ClientType.OLLAMA && !platform.ollamaOptions.isNullOrBlank()) {
                runCatching { jsonSerializer.decodeFromString<OllamaOptions>(platform.ollamaOptions) }.getOrNull()
            } else {
                null
            }

            val maxAutoContinues = parsedOllamaOptions?.maxAutoContinues ?: OllamaOptions.DEFAULT_MAX_AUTO_CONTINUES
            val isAutoContinueEnabled = parsedOllamaOptions?.autoContinue == true

            while (true) {
                val currentToken = credentialRotator?.getCurrentToken() ?: platform.apiToken
                val isOllama = platform.compatibleType == ClientType.OLLAMA
                val isOpenRouter = platform.compatibleType == ClientType.OPENROUTER
                val config = when (platform.compatibleType) {
                    ClientType.OPENAI -> openAIProviderConfig(platform.copy(apiToken = currentToken))
                    ClientType.OPENROUTER -> openAICompatibleProviderConfig(platform.copy(apiToken = currentToken))
                    ClientType.OLLAMA -> openAICompatibleProviderConfig(platform.copy(apiToken = currentToken))
                    ClientType.CUSTOM -> openAICompatibleProviderConfig(platform.copy(apiToken = currentToken))
                    else -> openAIProviderConfig(platform.copy(apiToken = currentToken))
                }

                val messages = ContextBuilder.buildChatCompletionsMessages(
                    turns = turns,
                    exchanges = exchanges,
                    policy = when (platform.compatibleType) {
                        ClientType.OPENROUTER -> ProviderContextPolicy.OPENROUTER
                        ClientType.OLLAMA -> ProviderContextPolicy.OLLAMA
                        else -> ProviderContextPolicy.OPENAI_COMPATIBLE
                    },
                    attachmentEncoder = attachmentEncoder,
                    systemPrompt = platform.systemPrompt
                ).toMutableList()

                if (autoContinueCount > 0) {
                    messages.add(
                        dev.chungjungsoo.gptmobile.data.dto.openai.request.Message(
                            role = "user",
                            content = dev.chungjungsoo.gptmobile.data.dto.openai.request.StringContent("continue")
                        )
                    )
                }

                val requestTools = ContextBuilder.buildOpenAITools(exposedTools)
                val parsedRouting = if (isOpenRouter && !platform.openRouterRouting.isNullOrBlank()) {
                    runCatching { jsonSerializer.decodeFromString<OpenRouterProviderRouting>(platform.openRouterRouting) }.getOrNull()
                } else {
                    null
                }
                val parsedOpenRouterOptions = if (isOpenRouter && !platform.openRouterRouting.isNullOrBlank()) {
                    runCatching { jsonSerializer.decodeFromString<OpenRouterOptions>(platform.openRouterRouting) }.getOrNull()
                } else {
                    null
                }

                val effectiveTemperature = if (isOpenRouter) {
                    parsedOpenRouterOptions?.temperature ?: platform.temperature
                } else {
                    platform.temperature
                }

                val effectiveTopP = if (isOpenRouter) {
                    parsedOpenRouterOptions?.topP ?: platform.topP
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
                    var lastFinishReason: String? = null
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
                        // Check if generation finished due to length limit and auto-continue is enabled
                        if (isAutoContinueEnabled && lastFinishReason == "length" && autoContinueCount < maxAutoContinues) {
                            autoContinueCount++
                            emit(ProviderEvent.Notice("Auto-continuing response ($autoContinueCount/$maxAutoContinues)..."))
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

                var roundFailed = false
                var canRotate = false
                var lastFailedMessage: String? = null

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
                    if (!roundFailed) {
                        emit(ProviderEvent.Completed)
                        return@flow
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val message = t.message ?: "Request failed"
                    if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(message)) {
                        canRotate = true
                        lastFailedMessage = message
                    } else {
                        emit(ProviderEvent.Failed(message))
                        return@flow
                    }
                }

                if (canRotate && credentialRotator != null && credentialRotator.getTokens().size > 1) {
                    val next = credentialRotator.rotateToNextToken()
                    if (next != null) {
                        rotated = true
                        emit(ProviderEvent.Notice("Rate limited or quota exceeded. Rotated to next configured API key."))
                        continue
                    }
                }

                emit(ProviderEvent.Failed(lastFailedMessage ?: "Request failed"))
                return@flow
            }
        }
    }
}

class GroqAdapter(
    private val groqAPI: GroqAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    fun createSession(
        platform: PlatformV2,
        turns: List<ConversationTurn>,
        tools: List<ResolvedAgentTool>,
        modelSnapshot: String,
        credentialRotator: ApiCredentialRotator? = null
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally: Boolean = false

        override fun streamRound(
            exposedTools: List<AgentToolDefinition>,
            exchanges: List<AgentToolExchange>
        ): Flow<ProviderEvent> = flow {
            var rotated = false
            while (true) {
                val currentToken = credentialRotator?.getCurrentToken() ?: platform.apiToken
                val config = groqProviderConfig(platform.copy(apiToken = currentToken))
                val messages = ContextBuilder.buildChatCompletionsMessages(
                    turns = turns,
                    exchanges = exchanges,
                    policy = ProviderContextPolicy.GROQ,
                    attachmentEncoder = attachmentEncoder,
                    systemPrompt = platform.systemPrompt
                )
                val requestTools = ContextBuilder.buildOpenAITools(exposedTools)
                val request = ChatCompletionRequest(
                    model = platform.model,
                    messages = messages,
                    tools = requestTools,
                    temperature = platform.temperature,
                    topP = platform.topP,
                    maxTokens = platform.maxTokens
                )
                val assembler = ChatCompletionsEventAssembler()
                var roundFailed = false
                var canRotate = false
                var lastFailedMessage: String? = null

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
                            assembler.accept(
                                content = choice.delta.content,
                                reasoning = choice.delta.reasoning,
                                toolCalls = choice.delta.toolCalls,
                                finishReason = choice.finishReason
                            ).forEach { emit(it) }
                        }
                    }
                    if (!roundFailed) {
                        emit(ProviderEvent.Completed)
                        return@flow
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val message = t.message ?: "Request failed"
                    if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(message)) {
                        canRotate = true
                        lastFailedMessage = message
                    } else {
                        emit(ProviderEvent.Failed(message))
                        return@flow
                    }
                }

                if (canRotate && credentialRotator != null && credentialRotator.getTokens().size > 1) {
                    val next = credentialRotator.rotateToNextToken()
                    if (next != null) {
                        rotated = true
                        emit(ProviderEvent.Notice("Rate limited or quota exceeded. Rotated to next configured API key."))
                        continue
                    }
                }

                emit(ProviderEvent.Failed(lastFailedMessage ?: "Request failed"))
                return@flow
            }
        }
    }
}

class GeminiAdapter(
    private val googleAPI: GoogleAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    fun createSession(
        platform: PlatformV2,
        turns: List<ConversationTurn>,
        tools: List<ResolvedAgentTool>,
        modelSnapshot: String,
        credentialRotator: ApiCredentialRotator? = null
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally: Boolean = false

        override fun streamRound(
            exposedTools: List<AgentToolDefinition>,
            exchanges: List<AgentToolExchange>
        ): Flow<ProviderEvent> = flow {
            var rotated = false
            while (true) {
                val currentToken = credentialRotator?.getCurrentToken() ?: platform.apiToken
                val config = geminiProviderConfig(platform.copy(apiToken = currentToken))
                val contents = ContextBuilder.buildGeminiContents(
                    turns = turns,
                    exchanges = exchanges,
                    attachmentEncoder = attachmentEncoder
                )
                val geminiTools = ContextBuilder.buildGeminiTools(exposedTools)
                val request = GeminiRequest(
                    contents = contents,
                    systemInstruction = platform.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                        dev.chungjungsoo.gptmobile.data.dto.google.Content(
                            parts = listOf(dev.chungjungsoo.gptmobile.data.dto.google.Part.TextPart(text = it))
                        )
                    },
                    tools = geminiTools,
                    generationConfig = GenerationConfig(
                        temperature = platform.temperature,
                        topP = platform.topP,
                        topK = platform.topK,
                        maxOutputTokens = platform.maxTokens
                    ),
                    safetySettings = listOfNotNull(
                        platform.harassmentSafetyThreshold?.let { SafetySetting(category = "HARM_CATEGORY_HARASSMENT", threshold = it) },
                        platform.hateSpeechSafetyThreshold?.let { SafetySetting(category = "HARM_CATEGORY_HATE_SPEECH", threshold = it) },
                        platform.sexuallyExplicitSafetyThreshold?.let { SafetySetting(category = "HARM_CATEGORY_SEXUALLY_EXPLICIT", threshold = it) },
                        platform.dangerousContentSafetyThreshold?.let { SafetySetting(category = "HARM_CATEGORY_DANGEROUS_CONTENT", threshold = it) }
                    ).takeIf { it.isNotEmpty() }
                )
                val assembler = GeminiEventAssembler()
                var roundFailed = false
                var canRotate = false
                var lastFailedMessage: String? = null

                try {
                    googleAPI.streamChatCompletion(platform.model, request, platform.timeout, config).collect { response ->
                        assembler.accept(response).forEach { emit(it) }
                    }
                    emit(ProviderEvent.Completed)
                    return@flow
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val message = t.message ?: "Request failed"
                    if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(message)) {
                        canRotate = true
                        lastFailedMessage = message
                    } else {
                        emit(ProviderEvent.Failed(message))
                        return@flow
                    }
                }

                if (canRotate && credentialRotator != null && credentialRotator.getTokens().size > 1) {
                    val next = credentialRotator.rotateToNextToken()
                    if (next != null) {
                        rotated = true
                        emit(ProviderEvent.Notice("Rate limited or quota exceeded. Rotated to next configured API key."))
                        continue
                    }
                }

                emit(ProviderEvent.Failed(lastFailedMessage ?: "Request failed"))
                return@flow
            }
        }
    }
}

class AnthropicAdapter(
    private val openAIAPI: OpenAIAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    fun createSession(
        platform: PlatformV2,
        turns: List<ConversationTurn>,
        tools: List<ResolvedAgentTool>,
        modelSnapshot: String,
        credentialRotator: ApiCredentialRotator? = null
    ): AgentProviderSession = object : AgentProviderSession {
        override val handlesToolsInternally: Boolean = false

        override fun streamRound(
            exposedTools: List<AgentToolDefinition>,
            exchanges: List<AgentToolExchange>
        ): Flow<ProviderEvent> = flow {
            var rotated = false
            while (true) {
                val currentToken = credentialRotator?.getCurrentToken() ?: platform.apiToken
                val config = anthropicProviderConfig(platform.copy(apiToken = currentToken))
                val messages = ContextBuilder.buildAnthropicMessages(
                    turns = turns,
                    exchanges = exchanges,
                    attachmentEncoder = attachmentEncoder
                )
                val anthropicTools = ContextBuilder.buildAnthropicTools(exposedTools)
                val request = AnthropicRequest(
                    model = platform.model,
                    messages = messages,
                    system = platform.systemPrompt,
                    tools = anthropicTools,
                    temperature = platform.temperature,
                    topP = platform.topP,
                    topK = platform.topK,
                    maxTokens = platform.maxTokens ?: 4096
                )
                val assembler = AnthropicEventAssembler()
                var roundFailed = false
                var canRotate = false
                var lastFailedMessage: String? = null

                try {
                    openAIAPI.streamAnthropic(request, platform.timeout, config).collect { chunk ->
                        assembler.accept(chunk).forEach { emit(it) }
                    }
                    emit(ProviderEvent.Completed)
                    return@flow
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val message = t.message ?: "Request failed"
                    if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(message)) {
                        canRotate = true
                        lastFailedMessage = message
                    } else {
                        emit(ProviderEvent.Failed(message))
                        return@flow
                    }
                }

                if (canRotate && credentialRotator != null && credentialRotator.getTokens().size > 1) {
                    val next = credentialRotator.rotateToNextToken()
                    if (next != null) {
                        rotated = true
                        emit(ProviderEvent.Notice("Rate limited or quota exceeded. Rotated to next configured API key."))
                        continue
                    }
                }

                emit(ProviderEvent.Failed(lastFailedMessage ?: "Request failed"))
                return@flow
            }
        }
    }
}
