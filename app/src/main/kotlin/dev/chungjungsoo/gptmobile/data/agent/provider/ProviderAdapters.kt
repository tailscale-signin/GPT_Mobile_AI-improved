package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.model.ExecutionNotice
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.openai.common.PlatformConfig
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatFunctionTool
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionResponse
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterReasoning
import dev.chungjungsoo.gptmobile.data.parser.ChatCompletionsEventAssembler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

interface ProviderStreamAdapter {
    fun supports(compatibleType: ClientType): Boolean

    fun stream(
        platform: PlatformV2,
        messages: List<ChatMessage>,
        tools: List<ProviderToolDefinition>
    ): Flow<ProviderEvent>
}

@Singleton
class OpenAICompatibleAdapter @Inject constructor(
    private val openAIAPI: OpenAIAPI
) : ProviderStreamAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(compatibleType: ClientType): Boolean = when (compatibleType) {
        ClientType.OPENAI,
        ClientType.OPENROUTER,
        ClientType.GROQ,
        ClientType.OLLAMA -> true
        ClientType.ANTHROPIC,
        ClientType.GOOGLE,
        ClientType.LITERT_LM -> false
    }

    override fun stream(
        platform: PlatformV2,
        messages: List<ChatMessage>,
        tools: List<ProviderToolDefinition>
    ): Flow<ProviderEvent> = flow {
        val keys = ApiCredentialRotator.parseKeys(platform.token)
        val attempts = maxOf(1, keys.size)
        var lastFailedMessage: String? = null

        val isOpenRouter = platform.compatibleType == ClientType.OPENROUTER
        val isOllama = platform.compatibleType == ClientType.OLLAMA

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
            val key = keys.getOrNull(attempt)
            val config = PlatformConfig(
                url = platform.apiUrl,
                token = key,
                compatibleType = platform.compatibleType
            )

            val requestTools = tools.takeIf { it.isNotEmpty() }?.map { tool ->
                ChatFunctionTool(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            }

            var roundFailed = false
            var canRotate = false

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
                val isHighDemand = ApiCredentialRotator.containsHighDemandMessage(lastFailedMessage)
                if (isHighDemand) {
                    emit(ProviderEvent.Notice(ExecutionNotice.ModelExperiencingHighDemandRetrying(attempt = attempt + 1)))
                    delay(3000L)
                } else {
                    emit(ProviderEvent.Notice(ExecutionNotice.ApiCredentialRotated(keyIndex = attempt + 1)))
                }
                continue
            } else {
                if (lastFailedMessage != null && canRotate) emit(ProviderEvent.Failed(lastFailedMessage!!))
                return@flow
            }
        }
    }
}
