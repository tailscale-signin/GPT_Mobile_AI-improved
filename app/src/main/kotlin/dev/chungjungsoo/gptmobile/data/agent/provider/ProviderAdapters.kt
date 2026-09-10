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
import dev.chungjungsoo.gptmobile.data.network.OpenRouterReasoning
import dev.chungjungsoo.gptmobile.data.network.ProviderAttachmentEncoder
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.throwIfToolDefinitionsRejected
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class OpenAICompatibleAdapter @Inject constructor(
    private val openAIAPI: OpenAIAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val messages = attachmentEncoder.openAiMessages(turns, platform.uid)
        val isOpenRouter = platform.compatibleType == ClientType.OPENROUTER.ordinal
        val isResponsesCompatible = platform.endpointType == 1
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val requestTools = tools.takeIf { it.isNotEmpty() }?.map { definition ->
                    ChatFunctionTool(
                        function = ChatFunction(
                            name = definition.name,
                            description = definition.description,
                            parameters = definition.inputSchema
                        )
                    )
                }

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val config = ProviderRequestConfig(platform.apiUrl, activeKey)
                    var roundFailed = false
                    var canRotate = false

                    if (isResponsesCompatible) {
                        val responseTools = tools.takeIf { it.isNotEmpty() }?.map { definition ->
                            ResponseFunctionTool(
                                name = definition.name,
                                description = definition.description,
                                parameters = definition.inputSchema
                            )
                        }
                        val request = ResponsesRequest(
                            model = platform.model,
                            input = messages + exchanges.flatMap { it.toChatMessages() },
                            tools = responseTools,
                            reasoning = if (platform.reasoning) {
                                ReasoningConfig(effort = platform.reasoningEffort ?: "medium")
                            } else {
                                null
                            }
                        )

                        val maxTransientRetries = if (attempts == 1) 2 else 1
                        var retryCount = 0
                        var streamSucceeded = false

                        while (retryCount <= maxTransientRetries && !streamSucceeded) {
                            roundFailed = false
                            canRotate = false
                            var chunkCount = 0

                            try {
                                openAIAPI.streamResponses(request, platform.timeout, config).collect { event ->
                                    chunkCount++
                                    when (event) {
                                        is OutputTextDeltaEvent -> emit(ProviderEvent.TextDelta(event.delta))
                                        is ResponseCreatedEvent -> {
                                            event.response?.error?.let { error ->
                                                roundFailed = true
                                                lastFailedMessage = error.message
                                                if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(error.message)) {
                                                    canRotate = true
                                                } else {
                                                    emit(ProviderEvent.Failed(error.message))
                                                }
                                            }
                                        }

                                        is ResponseInProgressEvent -> {
                                            event.response?.error?.let { error ->
                                                roundFailed = true
                                                lastFailedMessage = error.message
                                                if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(error.message)) {
                                                    canRotate = true
                                                } else {
                                                    emit(ProviderEvent.Failed(error.message))
                                                }
                                            }
                                        }

                                        is ResponseCompletedEvent -> {
                                            event.response?.error?.let { error ->
                                                roundFailed = true
                                                lastFailedMessage = error.message
                                                if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(error.message)) {
                                                    canRotate = true
                                                } else {
                                                    emit(ProviderEvent.Failed(error.message))
                                                }
                                            } ?: run {
                                                event.response?.let { response ->
                                                    val calls = response.output.orEmpty()
                                                        .filter { it.type == "function_call" }
                                                        .mapNotNull { item ->
                                                            val callId = item.callId ?: item.id ?: return@mapNotNull null
                                                            val name = item.name ?: return@mapNotNull null
                                                            AgentToolCall(
                                                                callId = callId,
                                                                name = name,
                                                                arguments = parseToolArguments(item.arguments)
                                                            )
                                                        }
                                                    if (calls.isNotEmpty()) {
                                                        emit(ProviderEvent.ToolCalls(calls))
                                                    }
                                                }
                                            }
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
                                    }
                                }
                            } catch (t: Throwable) {
                                if (t is CancellationException) throw t
                                roundFailed = true
                                lastFailedMessage = t.message
                                if (ApiCredentialRotator.isRotatableError(t)) {
                                    canRotate = true
                                } else {
                                    emit(ProviderEvent.Failed(t.message ?: "OpenAI stream request failed"))
                                    return@flow
                                }
                            }

                            if (!roundFailed) {
                                streamSucceeded = true
                            } else if (canRotate && chunkCount == 0 && retryCount < maxTransientRetries &&
                                ApiCredentialRotator.containsQuotaOrRateLimitMessage(lastFailedMessage)
                            ) {
                                retryCount++
                                val backoffMs = retryCount * 2000L
                                emit(ProviderEvent.Notice("Rate limited by provider. Retrying in ${backoffMs / 1000}s..."))
                                delay(backoffMs)
                                continue
                            } else {
                                break
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

                    val request = ChatCompletionRequest(
                        model = platform.model,
                        messages = messages,
                        stream = platform.stream,
                        temperature = platform.temperature,
                        topP = platform.topP,
                        tools = requestTools,
                        reasoning = if (isOpenRouter && platform.reasoning) OpenRouterReasoning(effort = "medium") else null
                    )
                    val assembler = ChatCompletionsEventAssembler()

                    val maxTransientRetries = if (attempts == 1) 2 else 1
                    var retryCount = 0
                    var streamSucceeded = false

                    while (retryCount <= maxTransientRetries && !streamSucceeded) {
                        roundFailed = false
                        canRotate = false
                        var chunkCount = 0

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
                                    chunkCount++
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
                            if (ApiCredentialRotator.isRotatableError(t)) {
                                canRotate = true
                            } else {
                                emit(ProviderEvent.Failed(t.message ?: "OpenAI stream request failed"))
                                return@flow
                            }
                        }

                        if (!roundFailed) {
                            streamSucceeded = true
                        } else if (canRotate && chunkCount == 0 && retryCount < maxTransientRetries &&
                            ApiCredentialRotator.containsQuotaOrRateLimitMessage(lastFailedMessage)
                        ) {
                            retryCount++
                            val backoffMs = retryCount * 2000L
                            emit(ProviderEvent.Notice("Rate limited by provider. Retrying in ${backoffMs / 1000}s..."))
                            delay(backoffMs)
                            continue
                        } else {
                            break
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

class GroqAdapter @Inject constructor(
    private val groqAPI: GroqAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val messages = attachmentEncoder.groqMessages(turns, platform.uid)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val requestTools = tools.takeIf { it.isNotEmpty() }?.map { definition ->
                    ChatFunctionTool(
                        function = ChatFunction(
                            name = definition.name,
                            description = definition.description,
                            parameters = definition.inputSchema
                        )
                    )
                }

                val request = GroqChatCompletionRequest(
                    model = platform.model,
                    messages = messages,
                    stream = platform.stream,
                    temperature = platform.temperature,
                    topP = platform.topP,
                    tools = requestTools
                )
                val assembler = ChatCompletionsEventAssembler()

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val config = ProviderRequestConfig(platform.apiUrl, activeKey)
                    var roundFailed = false
                    var canRotate = false

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
            }
        }
    }
}

class AnthropicAdapter @Inject constructor(
    private val anthropicAPI: AnthropicAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val messages = attachmentEncoder.anthropicMessages(turns, platform.uid)
        val thinkingPolicy = anthropicThinkingPolicy(
            model = platform.model,
            reasoningEnabled = platform.reasoning,
            hasTools = false
        )
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val roundThinkingPolicy = if (thinkingPolicy.config != null) {
                    anthropicThinkingPolicy(
                        model = platform.model,
                        reasoningEnabled = platform.reasoning,
                        hasTools = tools.isNotEmpty()
                    )
                } else {
                    thinkingPolicy
                }

                val toolRequest = tools.takeIf { it.isNotEmpty() }?.map { definition ->
                    AnthropicTool(
                        name = definition.name,
                        description = definition.description,
                        inputSchema = definition.inputSchema
                    )
                }

                val request = MessageRequest(
                    model = platform.model,
                    messages = messages + exchanges.flatMap { it.toAnthropicMessages() },
                    system = platform.systemPrompt?.takeIf { it.isNotBlank() },
                    stream = platform.stream,
                    temperature = platform.temperature,
                    topP = platform.topP,
                    tools = toolRequest,
                    thinking = roundThinkingPolicy.config
                )
                val assembler = AnthropicEventAssembler()

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val config = ProviderRequestConfig(
                        apiUrl = platform.apiUrl,
                        apiKey = activeKey,
                        betaFeatures = roundThinkingPolicy.betaFeatures
                    )
                    var roundFailed = false
                    var canRotate = false

                    try {
                        anthropicAPI.streamChatMessage(request, platform.timeout, config).collect { chunk ->
                            assembler.accept(chunk).forEach { mapped ->
                                if (mapped is ProviderEvent.Failed) {
                                    roundFailed = true
                                    lastFailedMessage = mapped.message
                                    if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(mapped.message)) {
                                        canRotate = true
                                    } else {
                                        emit(mapped)
                                    }
                                } else {
                                    emit(mapped)
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
    "\$schema",
    "\$id",
    "\$ref",
    "x-mcp-header",
    "x-mcp-param",
    "propertyNames",
    "patternProperties"
)

internal fun geminiToolParameters(schema: JsonObject): JsonObject {
    return buildJsonObject {
        schema.forEach { (key, value) ->
            if (key in GEMINI_DISALLOWED_SCHEMA_KEYWORDS) return@forEach
            put(
                key,
                if (key in SCHEMA_MAP_KEYWORDS) {
                    stripGeminiDisallowedKeywordsInSchemaMap(value)
                } else {
                    stripGeminiDisallowedKeywords(value)
                }
            )
        }
    }
}

// Keys under these keywords are caller-defined names, so a property literally named
// after a disallowed keyword must survive while the keyword itself is stripped everywhere else.
private val SCHEMA_MAP_KEYWORDS = setOf("properties", "definitions", "\$defs")

private fun stripGeminiDisallowedKeywordsInSchemaMap(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> buildJsonObject {
        value.forEach { (name, member) -> put(name, stripGeminiDisallowedKeywords(member)) }
    }

    else -> stripGeminiDisallowedKeywords(value)
}

private fun stripGeminiDisallowedKeywords(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> geminiToolParameters(value)
    is JsonArray -> JsonArray(value.map(::stripGeminiDisallowedKeywords))
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
                        thinkingConfig = if (platform.reasoning) GoogleThinkingConfig(includeThoughts = true) else null
                    ),
                    systemInstruction = platform.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                        Content(parts = listOf(Part.text(prompt)))
                    },
                    safetySettings = platform.googleSafetySettings(),
                    tools = tools.takeIf { it.isNotEmpty() }?.let { definitions ->
                        listOf(
                            GoogleTool(
                                definitions.map { definition ->
                                    FunctionDeclaration(
                                        definition.name,
                                        definition.description,
                                        geminiToolParameters(definition.inputSchema)
                                    )
                                }
                            )
                        )
                    },
                    toolConfig = tools.takeIf { it.isNotEmpty() }?.let {
                        GoogleToolConfig(GoogleFunctionCallingConfig(mode = "AUTO"))
                    }
                )

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val config = ProviderRequestConfig(platform.apiUrl, activeKey)
                    var roundFailed = false
                    var canRotate = false

                    try {
                        api.streamGenerateContent(request, platform.model, platform.timeout, config).collect { response ->
                            val parts = response.candidates.orEmpty().flatMap { it.content?.parts.orEmpty() }
                            if (parts.isNotEmpty()) {
                                modelPartsByRound[exchanges.size] = modelPartsByRound[exchanges.size].orEmpty() + parts
                            }
                            val safetyError = when {
                                response.promptFeedback?.blockReason != null ->
                                    "Gemini safety settings blocked the prompt: ${response.promptFeedback.blockReason}"

                                response.candidates.orEmpty().any { it.finishReason == "SAFETY" } ->
                                    "Gemini safety settings blocked the response."

                                else -> null
                            }
                            if (safetyError != null) {
                                roundFailed = true
                                lastFailedMessage = safetyError
                                emit(ProviderEvent.Failed(safetyError))
                            } else {
                                GeminiEventMapper.accept(response).forEach { mapped ->
                                    if (mapped is ProviderEvent.Failed) {
                                        roundFailed = true
                                        lastFailedMessage = mapped.message
                                        if (ApiCredentialRotator.containsQuotaOrRateLimitMessage(mapped.message)) {
                                            canRotate = true
                                        } else {
                                            emit(mapped)
                                        }
                                    } else {
                                        emit(mapped)
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

private fun AgentToolExchange.toChatMessages(): List<ChatMessage> = listOf(
    ChatMessage(
        role = OpenAIRole.ASSISTANT,
        toolCalls = calls.map { call ->
            ChatToolCall(call.callId, ChatFunction(call.name, call.arguments.toString()))
        }
    )
) + results.map { result ->
    ChatMessage(
        role = OpenAIRole.TOOL,
        toolCallId = result.callId,
        content = listOf(OpenAITextContent(result.content.asText()))
    )
}

private fun AgentToolExchange.toAnthropicMessages(): List<InputMessage> = listOf(
    InputMessage(
        role = MessageRole.ASSISTANT,
        content = calls.map { call ->
            ToolUseContent(id = call.callId, name = call.name, input = call.arguments)
        }
    ),
    InputMessage(
        role = MessageRole.USER,
        content = results.map { result ->
            AnthropicToolResultContent(
                toolUseId = result.callId,
                content = result.content.asText(),
                isError = result.isError
            )
        }
    )
)

private fun AgentToolExchange.toGeminiContents(assistantModelParts: List<Part>?): List<Content> {
    val modelParts = assistantModelParts.takeUnless { it.isNullOrEmpty() } ?: calls.map { call ->
        Part(
            functionCall = FunctionCall(
                name = call.name,
                args = call.arguments
            )
        )
    }
    return listOf(
        Content(
            role = GoogleRole.MODEL,
            parts = modelParts
        ),
        Content(
            role = GoogleRole.USER,
            parts = results.map { result ->
                Part(
                    functionResponse = FunctionResponse(
                        name = calls.firstOrNull { it.callId == result.callId }?.name.orEmpty(),
                        response = buildJsonObject {
                            put("result", result.content.asText())
                            put("is_error", result.isError)
                        }
                    )
                )
            }
        )
    )
}

private fun PlatformV2.googleSafetySettings(): List<SafetySetting>? = GeminiSafetySettings.from(safetySetting)
    ?.toApiSafetySettings()
