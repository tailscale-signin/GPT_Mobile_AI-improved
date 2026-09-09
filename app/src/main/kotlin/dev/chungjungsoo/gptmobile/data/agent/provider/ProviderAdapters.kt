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
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterReasoning
import dev.chungjungsoo.gptmobile.data.repository.GroqReasoningParser
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

                    val request = ChatCompletionRequest(
                        model = platform.model,
                        messages = messages,
                        stream = platform.stream,
                        temperature = platform.temperature,
                        topP = platform.topP,
                        tools = requestTools,
                        provider = parsedRouting,
                        reasoning = if (isOpenRouter && platform.reasoning) OpenRouterReasoning(effort = "medium") else null
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
                    } else if (roundFailed) {
                        lastFailedMessage?.let { emit(ProviderEvent.Failed(it)) }
                        return@flow
                    }
                }
            }
        }
    }

    private fun createGroqChatCompletionRequest(
        messages: List<ChatMessage>,
        platform: PlatformV2
    ): GroqChatCompletionRequest = GroqChatCompletionRequest(
        model = platform.model,
        messages = messages,
        temperature = platform.temperature,
        topP = platform.topP,
        stream = platform.stream,
        reasoningFormat = if (platform.reasoning) "raw" else null
    )

    private fun AgentToolExchange.toChatMessages(): List<ChatMessage> {
        val callMessage = ChatMessage(
            role = OpenAIRole.ASSISTANT.value,
            content = null,
            toolCalls = calls.map { call ->
                ChatToolCall(
                    id = call.callId,
                    type = "function",
                    function = ChatFunction(
                        name = call.name,
                        arguments = call.arguments
                    )
                )
            }
        )
        val resultMessages = results.map { result ->
            ChatMessage(
                role = OpenAIRole.TOOL.value,
                content = result.modelText(),
                toolCallId = result.callId
            )
        }
        return listOf(callMessage) + resultMessages
    }
}

class AnthropicMessagesAdapter @Inject constructor(
    private val api: AnthropicAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialMessages = attachmentEncoder.anthropicMessages(turns)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val messages = initialMessages + exchanges.flatMap { it.toAnthropicMessages() }
                val request = MessageRequest(
                    model = platform.model,
                    messages = messages,
                    system = platform.systemPrompt?.takeIf { it.isNotBlank() },
                    maxTokens = platform.maxTokens ?: 4096,
                    temperature = if (platform.reasoning) null else platform.temperature,
                    topP = if (platform.reasoning) null else platform.topP,
                    stream = true,
                    thinking = if (platform.reasoning) AnthropicThinkingConfig(budgetTokens = 2048) else null,
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
                    val config = ProviderRequestConfig(platform.apiUrl, activeKey)
                    val assembler = AnthropicEventsAssembler()
                    var roundFailed = false
                    var canRotate = false

                    try {
                        api.streamMessage(request, platform.timeout, config).collect { event ->
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
                            emit(ProviderEvent.Failed(t.message ?: "Anthropic stream request failed"))
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

    private fun AgentToolExchange.toAnthropicMessages(): List<InputMessage> {
        val assistantContent = calls.map { call ->
            val inputJson = runCatching {
                NetworkClient.anthropicJson.parseToJsonElement(call.arguments)
            }.getOrDefault(buildJsonObject {})
            ToolUseContent(id = call.callId, name = call.name, input = inputJson)
        }
        val assistantMessage = InputMessage(role = MessageRole.ASSISTANT.value, content = assistantContent)

        val toolResultContent = results.map { result ->
            AnthropicToolResultContent(
                toolUseId = result.callId,
                content = result.modelText(),
                isError = result.isError
            )
        }
        val toolMessage = InputMessage(role = MessageRole.USER.value, content = toolResultContent)

        return listOf(assistantMessage, toolMessage)
    }
}

class GeminiAdapter @Inject constructor(
    private val api: GoogleAPI,
    private val attachmentEncoder: ProviderAttachmentEncoder
) {
    suspend fun openSession(turns: List<ConversationTurn>, platform: PlatformV2): AgentProviderSession {
        val initialContents = attachmentEncoder.geminiContents(turns)
        val candidateKeys = ApiCredentialRotator.parseKeys(platform.token).ifEmpty { listOf("") }
        val keyIndexCounter = AtomicInteger(0)
        return object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ): Flow<ProviderEvent> = flow {
                val contents = initialContents + exchanges.flatMap { it.toGeminiContents() }
                val safetySettings = listOf(
                    SafetySetting("HARM_CATEGORY_HARASSMENT", GeminiSafetySettings.normalizeThreshold(platform.harassmentSafetyThreshold)),
                    SafetySetting("HARM_CATEGORY_HATE_SPEECH", GeminiSafetySettings.normalizeThreshold(platform.hateSpeechSafetyThreshold)),
                    SafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", GeminiSafetySettings.normalizeThreshold(platform.sexuallyExplicitSafetyThreshold)),
                    SafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", GeminiSafetySettings.normalizeThreshold(platform.dangerousContentSafetyThreshold))
                )
                val request = GenerateContentRequest(
                    contents = contents,
                    systemInstruction = platform.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                        Content(role = GoogleRole.USER.value, parts = listOf(Part(text = it)))
                    },
                    generationConfig = GenerationConfig(
                        temperature = platform.temperature,
                        topP = platform.topP,
                        topK = platform.topK,
                        maxOutputTokens = platform.maxTokens,
                        thinkingConfig = if (platform.reasoning) GoogleThinkingConfig(thinkingBudget = 2048) else null
                    ),
                    safetySettings = safetySettings,
                    tools = tools.takeIf { it.isNotEmpty() }?.let { list ->
                        listOf(
                            GoogleTool(
                                functionDeclarations = list.map { definition ->
                                    FunctionDeclaration(
                                        name = definition.name,
                                        description = definition.description,
                                        parameters = sanitizeGeminiParameters(definition.inputSchema)
                                    )
                                }
                            )
                        )
                    },
                    toolConfig = tools.takeIf { it.isNotEmpty() }?.let {
                        GoogleToolConfig(
                            functionCallingConfig = GoogleFunctionCallingConfig(
                                mode = "AUTO"
                            )
                        )
                    }
                )

                val attempts = candidateKeys.size
                val startIndex = keyIndexCounter.getAndIncrement()
                var lastFailedMessage: String? = null

                for (attempt in 0 until attempts) {
                    val keyIndex = ((startIndex + attempt) % candidateKeys.size + candidateKeys.size) % candidateKeys.size
                    val activeKey = candidateKeys[keyIndex]
                    val config = ProviderRequestConfig(platform.apiUrl, activeKey)
                    val assembler = GeminiEventsAssembler()
                    var roundFailed = false
                    var canRotate = false

                    try {
                        api.streamGenerateContent(platform.model, request, platform.timeout, config).collect { response ->
                            assembler.accept(response).forEach { mapped ->
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

    private fun AgentToolExchange.toGeminiContents(): List<Content> {
        val modelParts = calls.map { call ->
            val argsJson = runCatching {
                NetworkClient.googleJson.decodeFromString<JsonObject>(call.arguments)
            }.getOrDefault(buildJsonObject {})
            Part(functionCall = FunctionCall(name = call.name, args = argsJson))
        }
        val modelContent = Content(role = GoogleRole.MODEL.value, parts = modelParts)

        val responseParts = results.map { result ->
            val responseMap: Map<String, JsonElement> = when (val c = result.content) {
                is ToolResultContent.Text -> mapOf("output" to JsonPrimitive(c.text))
                is ToolResultContent.Json -> mapOf("output" to c.value)
                is ToolResultContent.ResourceLinks -> mapOf(
                    "links" to JsonArray(
                        c.links.map { link ->
                            buildJsonObject {
                                put("uri", link.uri)
                                link.name?.let { put("name", it) }
                                link.description?.let { put("description", it) }
                                link.mimeType?.let { put("mimeType", it) }
                            }
                        }
                    )
                )
            }
            Part(
                functionResponse = FunctionResponse(
                    name = result.callId.substringBeforeLast(':').ifBlank { result.callId },
                    response = JsonObject(responseMap)
                )
            )
        }
        val userContent = Content(role = GoogleRole.USER.value, parts = responseParts)

        return listOf(modelContent, userContent)
    }
}

private const val GROQ_OUTPUT_LIMIT_MESSAGE = "Groq output limit reached. Please reduce the prompt size or request a shorter response."
