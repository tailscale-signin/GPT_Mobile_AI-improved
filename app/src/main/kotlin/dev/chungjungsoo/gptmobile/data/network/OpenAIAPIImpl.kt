package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCreatedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseInProgressEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.UnknownEvent
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import dev.chungjungsoo.gptmobile.data.openrouter.account.recordOpenRouterReceipts
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

class OpenAIAPIImpl @Inject constructor(
    private val networkClient: NetworkClient,
    private val openRouterReceipts: dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptStore? = null
) : OpenAIAPI {
    private val requestPreparation = ModelRequestPreparation(networkClient)

    override suspend fun checkSavedHordeJob(runId: String, chatId: Int, config: ProviderRequestConfig): String = FreeQueuedTransport(networkClient(), requestPreparation).checkSavedHordeJob(runId, chatId, config)

    override suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        config: ProviderRequestConfig
    ): UploadedProviderFile {
        val endpoint = config.buildEndpoint("files")
        val responseBody = networkClient().preparePost(endpoint) {
            config.token?.let { bearerAuth(it) }
            config.extraHeaders.forEach { (key, value) -> header(key, value) }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "user_data")
                        append(
                            "file",
                            File(filePath).readBytes(),
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            }
                        )
                    }
                )
            )
        }.body<String>()

        val uploadResponse = NetworkClient.openAIJson.decodeFromString<OpenAIFileResponse>(responseBody)
        return UploadedProviderFile(
            id = uploadResponse.id,
            mimeType = uploadResponse.mimeType ?: mimeType,
            name = uploadResponse.filename
        )
    }

    override suspend fun isFileAvailable(fileId: String, config: ProviderRequestConfig): Boolean {
        val endpoint = config.buildEndpoint("files/$fileId")
        return try {
            networkClient().prepareGet(endpoint) {
                config.token?.let { bearerAuth(it) }
                config.extraHeaders.forEach { (key, value) -> header(key, value) }
            }.execute { response ->
                config.freeDiagnostics?.responded()
                response.status.isSuccess()
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun streamChatCompletion(
        request: ChatCompletionRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<ChatCompletionChunk> = flow {
        val config = if (config.freeProvider != null) config.copy(freeDiagnostics = dev.chungjungsoo.gptmobile.data.free.FreeRequestDiagnostics()) else config
        val workerBudget = kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]
        var receivedAssistantPayload = false
        var receivedGatewayToolActivity = false
        var receivedTerminal = false
        var resolvedFreeModel: String? = null
        var contextCorrected = false
        var recoveryCheck = false
        var transportRetries = 0
        val transportStartedNs = System.nanoTime()
        try {
            val free = config.freeProvider
            if (free != null) {
                FreeProviderPreflight.require(config, request.model)
                recoveryCheck = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.beginGeneration(free, request.model)
            }
            var freeModel = free?.let { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.requireModel(it, request.model) }
            if (free == FreeAiProvider.PORCHLM && request.model == "automatic") {
                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.refresh(free)
                freeModel = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.models(free).firstOrNull { it.id != "automatic" }
                check(freeModel != null) { "PorchLM model discovery is unavailable. Retry its status check later." }
            }
            if (free == FreeAiProvider.PORCHLM) emit(ChatCompletionChunk(applicationNotice = "PorchLM is an educational demonstration model. Responses can be incoherent or inaccurate; use another provider for factual answers."))
            var preparedRequest = if (free == null) {
                request.withEndpointSamplingPolicy(config.apiUrl)
            } else {
                request.copy(
                    model = requireNotNull(freeModel).id,
                    stream = request.stream && freeModel?.streaming == true,
                    maxTokens = free.outputAllowance(request.maxTokens),
                    maxCompletionTokens = null,
                    tools = request.tools.takeIf { freeModel?.tools == true },
                    toolChoice = request.toolChoice.takeIf { freeModel?.tools == true },
                    models = null,
                    provider = null,
                    plugins = null,
                    topK = null,
                    reasoningEffort = request.reasoningEffort.takeIf { free in setOf(FreeAiProvider.KILO, FreeAiProvider.BLOCKRUN) },
                    reasoning = request.reasoning.takeIf { free == FreeAiProvider.KILO },
                    options = null,
                    transforms = null,
                    sessionId = null
                )
            }
            if (free == FreeAiProvider.AI_HORDE || free == FreeAiProvider.HUGGING_FACE) {
                FreeQueuedTransport(networkClient(), requestPreparation).generate(preparedRequest, config, timeoutSeconds) { emit(it) }
                return@flow
            }
            if (free == FreeAiProvider.POLLINATIONS) {
                val prompt = legacyPollinationsPrompt(preparedRequest)
                val legacyResponse = pollinationsCompletion {
                    FreeAiRequestLimiter.shared.withRequest(free) {
                        val admittedUrl = requestPreparation.preparePollinationsGet(prompt, config)
                        config.freeDiagnostics?.attempted()
                        networkClient().prepareGet(admittedUrl) {
                            timeout { requestTimeoutMillis = timeoutSeconds.coerceIn(15, 120) * 1_000L }
                            accept(ContentType.Text.Plain)
                        }.execute { response ->
                            config.freeDiagnostics?.responded()
                            PollinationsResponse(response.status.value, response.body<String>(), response.headers[HttpHeaders.ContentType], response.headers[HttpHeaders.RetryAfter])
                        }
                    }
                }
                emit(ChatCompletionChunk(model = free.model, choices = listOf(Choice(delta = Delta(content = legacyResponse), finishReason = "stop"))))
                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordResponse(free, free.model)
                return@flow
            }
            if (workerBudget != null) LocalServiceHealth.probe(config.apiUrl)
            val endpoint = config.buildEndpoint("chat/completions")

            val executeRequest: suspend () -> Unit = {
                if (transportRetries > 0) emit(ChatCompletionChunk(retryDurationMs = (System.nanoTime() - transportStartedNs) / 1_000_000, retryAttempts = transportRetries))
                ProviderCooldown.await(config)
                config.freeDiagnostics?.stage = "preparation"
                val admittedPayload = requestPreparation.prepare(NetworkClient.openAIJson.encodeToString(preparedRequest), config, preparedRequest.model, null)
                if (config.captureGenerationReceipt || config.requestRole == "synthesis") config.freeDiagnostics?.admission?.outputTokens?.toInt()?.let { emit(ChatCompletionChunk(serializedOutputTokens = it)) }
                config.freeDiagnostics?.stage = "dispatch"
                config.freeDiagnostics?.attempted()
                networkClient().preparePost(endpoint) {
                    applyPlatformStreamingTimeout(timeoutSeconds, workerBudget)
                    contentType(ContentType.Application.Json)
                    setBody(admittedPayload)
                    accept(if (preparedRequest.stream) ContentType.Text.EventStream else ContentType.Application.Json)
                    config.token?.takeIf { it.isNotBlank() && (free == null || free.requiresUserToken) }?.let { bearerAuth(it) }
                    config.extraHeaders.forEach { (key, value) -> header(key, value) }
                    if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                }.execute { response ->
                    config.freeDiagnostics?.responded()
                    if (!response.status.isSuccess()) {
                        val errorBody = if (free != null) readFreeErrorBody(response) else response.body<String>()
                        if (free != null) {
                            val retryAt = retainFreeHttpFailure(config, preparedRequest.model, response.status.value, errorBody) { response.headers[it] }
                            val retrySeconds = retryAt?.let { ((it - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(1) }
                            val detail = providerErrorDetails(errorBody, dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(response.status.value, errorBody).message(), config.token)
                            dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordHttpFailure(free, response.status.value, detail, retryAt, preparedRequest.model, errorBody)
                            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, preparedRequest.model, detail, response.status.value, errorBody, { response.headers[it] }, retryAt)
                            if (retrySeconds != null) throw FreeAiRateLimitException(free, retrySeconds, cooldown = config.freeDiagnostics?.cooldown, localBlock = false)
                            val contextLimit = rejectedFreeContextLimit(response.status.value, errorBody)
                            if (contextLimit != null && !contextCorrected && !receivedAssistantPayload && !receivedGatewayToolActivity) {
                                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.learnContextLimit(free, preparedRequest.model, contextLimit)
                                contextCorrected = true
                                preparedRequest = preparedRequest.copy(maxTokens = minOf(preparedRequest.maxTokens ?: 1024, 1024, contextLimit / 2).coerceAtLeast(1))
                                throw FreeContextRetry()
                            }
                        }
                        val parameter = rejectedSamplingParameter(errorBody)
                        if (response.status.value == 400 && parameter != null) {
                            val sanitized = when (parameter) {
                                "temperature" -> preparedRequest.copy(temperature = null)
                                "top_p" -> preparedRequest.copy(topP = null)
                                else -> preparedRequest
                            }
                            if (sanitized != preparedRequest) {
                                preparedRequest = sanitized
                                throw java.io.IOException("Retrying without unsupported sampling parameter: $parameter")
                            }
                        }
                        if (response.status.value in listOf(429, 502, 503, 504)) {
                            throw ProviderHttpFailure(response.status.value, ProviderCooldown.defer(config, response.headers[HttpHeaders.RetryAfter]))
                        }
                        throwIfToolDefinitionsRejected(response.status.value, !request.tools.isNullOrEmpty(), errorBody)

                        val errorMessage = if (free != null) {
                            freeHttpErrorMessage(response.status.value, errorBody, config.token)
                        } else if (response.status.value in setOf(401, 402, 403)) {
                            dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(response.status.value, errorBody).message()
                        } else {
                            providerErrorDetails(errorBody, "HTTP ${response.status.value}", config.token)
                        }
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                            "Provider",
                            "HTTP_ERROR · status=${response.status.value} · model=${request.model} · detail=$errorMessage",
                            "E"
                        )

                        emit(
                            ChatCompletionChunk(
                                error = ErrorDetail(
                                    message = if (free?.requiresUserToken == true || response.status.value !in setOf(401, 402, 403)) config.readableProviderError(errorMessage, response.status.value.toString()) else errorMessage,
                                    type = config.freeDiagnostics?.category?.name ?: "http_error",
                                    code = response.status.value.toString()
                                )
                            )
                        )
                        return@execute
                    }

                    free?.let { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordAccepted(it) }
                    config.freeDiagnostics?.stage = "generation"
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                        "Provider",
                        "REQUEST_ACCEPTED · model=${request.model} · server=${response.headers["Server"].orEmpty().take(120)} · requestId=${providerRequestIdentity { response.headers[it] }} · requestedOutputCap=${preparedRequest.maxTokens} · requestedReasoning=${preparedRequest.reasoningEffort ?: preparedRequest.reasoning?.toString() ?: "provider-default"} · reasoningCapabilityVerified=false · templateThinking=${preparedRequest.chatTemplateKwargs?.get("enable_thinking")} · serverEffectiveSettings=unverified"
                    )
                    // Capture Gateway headers from response
                    val gatewayJobId = response.headers["X-Gateway-Job-ID"]
                    val gatewayRequestId = response.headers["X-Gateway-Request-ID"]
                    val gatewayVersion = response.headers["X-Gateway-Version"]
                    val gatewayProgressProtocol = response.headers["X-Gateway-Progress-Protocol"]
                    val gatewaySingleflight = response.headers["X-Gateway-Singleflight"]

                    val gatewayMetadata = if (gatewayJobId != null || gatewayRequestId != null || gatewayVersion != null) {
                        GatewayResponseMetadata(
                            jobId = gatewayJobId,
                            requestId = gatewayRequestId,
                            version = gatewayVersion,
                            progressProtocol = gatewayProgressProtocol,
                            singleflightRole = gatewaySingleflight
                        )
                    } else {
                        null
                    }

                    if (config.openRouterReceipt != null) {
                        emit(
                            ChatCompletionChunk(
                                id = response.headers["X-Generation-Id"],
                                model = request.model,
                                cacheStatus = response.headers["X-OpenRouter-Cache-Status"]
                            )
                        )
                    }

                    // llama.cpp and other compatible providers return choices[].message for stream=false.
                    val responseType = response.contentType()
                    if (responseType?.match(ContentType.Application.Json) == true ||
                        (!preparedRequest.stream && responseType?.match(ContentType.Text.EventStream) != true)
                    ) {
                        val decoded = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(response.body<String>())
                        val chunk = decoded.copy(
                            choices = decoded.choices?.map { choice ->
                                if (choice.message != null && choice.finishReason == null) choice.copy(finishReason = "stop") else choice
                            },
                            gatewayMetadata = gatewayMetadata ?: decoded.gatewayMetadata,
                            error = decoded.error?.let { error ->
                                error.copy(message = config.readableProviderError(error.message, error.code))
                            }
                        )
                        if (free != null) {
                            val usable = chunk.error == null && chunk.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() || !it.effectiveDelta.toolCalls.isNullOrEmpty() }
                            if (usable) {
                                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordResponse(free, chunk.model, response.headers["X-RateLimit-Remaining"] ?: response.headers["X-RateLimit-Remaining-Output"])
                            } else {
                                if (chunk.error == null) config.freeDiagnostics?.category = noVisibleAnswerCategory(chunk.choices.orEmpty().any { !it.effectiveDelta.effectiveReasoning.isNullOrBlank() }, chunk.choices.orEmpty().any { it.finishReason == "length" })
                                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordTerminalFailure(free, preparedRequest.model, config.freeDiagnostics?.category?.name ?: chunk.error?.message ?: "No visible answer received")
                                dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, preparedRequest.model, chunk.error?.message ?: "No visible answer received", status = 200, retryAt = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.value[free]?.retryAt, terminal = true)
                            }
                        }
                        if (free != null && !chunk.model.isNullOrBlank()) dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("FreeProvider", "MODEL_RESOLVED · profile=${config.profileUid} · requested=${request.model} · resolved=${chunk.model} · capabilityVerification=unverified")
                        emit(chunk)
                        if (free != null) {
                            freeResponseOutcome(
                                free,
                                chunk.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() },
                                chunk.choices.orEmpty().any { !it.effectiveDelta.toolCalls.isNullOrEmpty() },
                                chunk.error != null,
                                chunk.choices.orEmpty().any { it.finishReason == "length" },
                                chunk.choices.orEmpty().any { !it.effectiveDelta.effectiveReasoning.isNullOrBlank() }
                            )?.let { emit(it) }
                        }
                        return@execute
                    }

                    // If gateway metadata is present, emit an initial chunk carrying the metadata
                    var firstChunk = true
                    var receivedToolCalls = false
                    var receivedAnswer = false
                    var receivedReasoning = false
                    var receivedError = false
                    var reachedOutputLimit = false
                    var parseFailures = 0

                    // Success - read SSE stream
                    val channel = response.bodyAsChannel()
                    val terminalDrain = TerminalStreamDrain()
                    while (!channel.isClosedForRead) {
                        val remaining = terminalDrain.remainingMillis()
                        val line = if (remaining == null) {
                            channel.readLine()
                        } else {
                            withTimeoutOrNull(remaining) { channel.readLine() }
                        } ?: break
                        val data = SseUtils.extractSseData(line) ?: continue

                        // OpenAI sends "[DONE]" as final message
                        if (data == "[DONE]") {
                            receivedTerminal = true
                            break
                        }

                        val decoded = try {
                            NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(data)
                        } catch (_: kotlinx.serialization.SerializationException) {
                            parseFailures++
                            continue
                        }
                        if (!receivedAssistantPayload && !receivedGatewayToolActivity) {
                            decoded.error?.let { error ->
                                if (ResilientStreamingClient.isRetryable(IllegalStateException("${error.code.orEmpty()} ${error.message}"))) {
                                    throw java.io.IOException("Gateway/provider transient error: ${error.message}")
                                }
                            }
                        }
                        if (decoded.gatewayProgress?.event in setOf("tool_started", "tool_completed", "tool_failed", "remote_tool_handoff")) {
                            receivedGatewayToolActivity = true
                        }
                        val chunk = decoded.error?.let { error ->
                            decoded.copy(error = error.copy(message = config.readableProviderError(providerErrorDetails(data, error.message, config.token), error.code)))
                        } ?: decoded
                        if (free != null && !chunk.model.isNullOrBlank() && resolvedFreeModel != chunk.model) {
                            resolvedFreeModel = chunk.model
                            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("FreeProvider", "MODEL_RESOLVED · profile=${config.profileUid} · requested=${request.model} · resolved=${chunk.model} · capabilityVerification=unverified")
                        }
                        receivedTerminal = receivedTerminal || chunk.error != null || chunk.choices.orEmpty().any { it.finishReason != null }
                        if (receivedTerminal) terminalDrain.markTerminal()
                        receivedReasoning = receivedReasoning || chunk.choices.orEmpty().any { !it.effectiveDelta.effectiveReasoning.isNullOrBlank() }
                        receivedAnswer = receivedAnswer || chunk.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() }
                        receivedError = receivedError || chunk.error != null
                        if (free != null && chunk.error != null) dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, preparedRequest.model, chunk.error.message, status = 200, body = data, event = "error", terminal = true)
                        reachedOutputLimit = reachedOutputLimit || chunk.choices.orEmpty().any { it.finishReason == "length" }
                        receivedAssistantPayload = receivedAssistantPayload || chunk.hasAssistantStreamPayload()
                        receivedToolCalls = receivedToolCalls || chunk.choices.orEmpty().any { !it.effectiveDelta.toolCalls.isNullOrEmpty() }
                        if (firstChunk && gatewayMetadata != null) {
                            firstChunk = false
                            emit(chunk.copy(gatewayMetadata = gatewayMetadata))
                        } else {
                            emit(chunk)
                        }
                        if (receivedError) break
                    }
                    if (receivedTerminal && receivedToolCalls && !receivedError) emit(ChatCompletionChunk(streamFinished = true))

                    if (!receivedTerminal) throw java.io.EOFException("STREAM_INTERRUPTED: provider closed SSE before a completion marker")
                    if (parseFailures > 0 && !receivedAssistantPayload && !receivedError) {
                        emit(ChatCompletionChunk(error = ErrorDetail("STREAM_PARSE_FAILURE: $parseFailures response chunks could not be decoded", "stream_parse_error")))
                    }
                    if (free != null) {
                        if (!receivedError && (receivedAnswer || receivedToolCalls)) {
                            dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordResponse(free, resolvedFreeModel, response.headers["X-RateLimit-Remaining"] ?: response.headers["X-RateLimit-Remaining-Output"])
                        } else {
                            if (!receivedError) config.freeDiagnostics?.category = noVisibleAnswerCategory(receivedReasoning, reachedOutputLimit)
                            dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordTerminalFailure(free, preparedRequest.model, config.freeDiagnostics?.category?.name ?: "Provider returned an error")
                            if (config.freeDiagnostics?.recorded != true) dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, preparedRequest.model, if (receivedError) "Provider returned an error" else "No visible answer received", status = 200, retryAt = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.value[free]?.retryAt, terminal = true)
                        }
                        freeResponseOutcome(free, receivedAnswer, receivedToolCalls, receivedError, reachedOutputLimit, receivedReasoning)?.let { emit(it) }
                    }
                    // If no chunks were emitted but metadata was present, emit a metadata chunk
                    if (firstChunk && gatewayMetadata != null) {
                        emit(ChatCompletionChunk(gatewayMetadata = gatewayMetadata))
                    }
                }
            }
            if (free != null) {
                try {
                    FreeAiRequestLimiter.shared.withRequest(free, preparedRequest.model, freeCredentialIdentity(free, config.token), executeRequest)
                } catch (_: FreeContextRetry) {
                    config.freeDiagnostics?.recorded = false
                    emit(ChatCompletionChunk(applicationNotice = "${free.displayName}: adjusting output to the model's context window and retrying once."))
                    FreeAiRequestLimiter.shared.withRequest(free, preparedRequest.model, freeCredentialIdentity(free, config.token), executeRequest)
                }
            } else {
                ResilientStreamingClient.executeWithRetry(
                    config = ResilientStreamingClient.RetryConfig(maxAttempts = 3, initialDelayMs = 250, maxDelayMs = 1000),
                    shouldRetry = { !receivedAssistantPayload && !receivedGatewayToolActivity },
                    onRetry = { attempt, delay, failure ->
                        transportRetries = attempt
                        if (ResilientStreamingClient.isPrematureConnectionClose(failure)) LocalEndpointConnectionPolicy.peerClosed(endpoint)
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                            "ProviderRetry",
                            "CHAT_RETRY · attempt=$attempt delayMs=$delay · ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                            "W"
                        )
                    }
                ) { executeRequest() }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException) throw e
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> config.freeProvider?.let { "${it.displayName}: DNS could not resolve ${java.net.URI(it.apiUrl).host}. Check this provider's availability and your connection; no alternate host was contacted." } ?: "Network error: Unable to resolve host."
                is java.nio.channels.UnresolvedAddressException -> "Network error: Unable to resolve address. Check your internet connection."
                is java.net.ConnectException -> "Network error: Connection refused. Check the API URL."
                is HttpRequestTimeoutException -> "Request timed out."
                is java.net.SocketTimeoutException -> "Response timed out while waiting for the next chunk."
                is javax.net.ssl.SSLException -> "Network error: SSL/TLS connection failed."
                else -> if (ResilientStreamingClient.isPrematureConnectionClose(e)) {
                    if (receivedAssistantPayload) "STREAM_INTERRUPTED: connection closed during the response." else "Connection closed before the response started. Please retry."
                } else {
                    e.message ?: "Unknown network error"
                }
            }
            config.freeDiagnostics?.apply {
                if (category == null) category = freeFailureCategory(e)
                if (category == FreeFailureCategory.DNS_RESOLUTION_FAILED) dnsFailed(runCatching { java.net.URI(config.apiUrl).host }.getOrNull().orEmpty())
                if (e is FreeAiRateLimitException) cooldown = e.cooldown
            }
            config.freeProvider?.let { provider ->
                if (config.freeDiagnostics?.recorded != true) {
                    val networkFailure = e is java.io.IOException || e is java.nio.channels.UnresolvedAddressException || config.freeDiagnostics?.category == FreeFailureCategory.DNS_RESOLUTION_FAILED
                    val cooldown = FreeAiRequestLimiter.shared.cooldownUntilMillis(provider, request.model, freeCredentialIdentity(provider, config.token))
                    val readiness = when {
                        e is FreeAiRateLimitException -> dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness.RATE_LIMITED
                        networkFailure -> dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness.UNREACHABLE
                        config.freeDiagnostics?.stage == "preparation" -> dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness.INVALID_REQUEST
                        else -> dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness.GENERATION_FAILED
                    }
                    if (e !is FreeProviderException) dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordFailure(provider, errorMessage, readiness, if (networkFailure) false else null, cooldown, request.model)
                    dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, request.model, errorMessage, retryAt = cooldown, terminal = config.freeDiagnostics?.terminal ?: false.takeIf { config.freeDiagnostics?.jobId != null })
                }
            }
            emit(
                ChatCompletionChunk(
                    error = ErrorDetail(
                        message = errorMessage,
                        type = if (config.freeProvider != null) (config.freeDiagnostics?.category ?: freeFailureCategory(e)).name else "network_error"
                    )
                )
            )
        } finally {
            if (recoveryCheck) config.freeProvider?.let { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.endGeneration(it) }
        }
    }.normalizeFreeTextBlocks(config.freeProvider).recordOpenRouterReceipts(config.openRouterReceipt, openRouterReceipts).flowOn(Dispatchers.IO)

    override fun streamResponses(
        request: ResponsesRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<ResponsesStreamEvent> = flow {
        val workerBudget = kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]
        var receivedResponsePayload = false
        var receivedTerminal = false
        var resolvedFreeModel: String? = null
        var preparedRequest = request.withModelSamplingPolicy()
        try {
            if (workerBudget != null) LocalServiceHealth.probe(config.apiUrl)
            val endpoint = config.buildEndpoint("responses")

            ResilientStreamingClient.executeWithRetry(
                config = ResilientStreamingClient.RetryConfig(maxAttempts = 3, initialDelayMs = 250, maxDelayMs = 1000),
                shouldRetry = { !receivedResponsePayload },
                onRetry = { attempt, delay, failure ->
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                        "ProviderRetry",
                        "RESPONSES_RETRY · attempt=$attempt delayMs=$delay · ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                        "W"
                    )
                }
            ) {
                ProviderCooldown.await(config)
                config.freeDiagnostics?.stage = "preparation"
                val admittedPayload = requestPreparation.prepare(NetworkClient.openAIJson.encodeToString(preparedRequest), config, preparedRequest.model, null)
                config.freeDiagnostics?.stage = "dispatch"
                config.freeDiagnostics?.attempted()
                networkClient().preparePost(endpoint) {
                    applyPlatformStreamingTimeout(timeoutSeconds, workerBudget)
                    contentType(ContentType.Application.Json)
                    setBody(admittedPayload)
                    accept(ContentType.Text.EventStream)
                    config.token?.let { bearerAuth(it) }
                    config.extraHeaders.forEach { (key, value) -> header(key, value) }
                }.execute { response ->
                    config.freeDiagnostics?.responded()
                    if (!response.status.isSuccess()) {
                        val errorBody = response.body<String>()
                        val parameter = rejectedSamplingParameter(errorBody)
                        if (response.status.value == 400 && parameter != null) {
                            val sanitized = when (parameter) {
                                "temperature" -> preparedRequest.copy(temperature = null)
                                "top_p" -> preparedRequest.copy(topP = null)
                                else -> preparedRequest
                            }
                            if (sanitized != preparedRequest) {
                                preparedRequest = sanitized
                                throw java.io.IOException("Retrying without unsupported sampling parameter: $parameter")
                            }
                        }
                        if (response.status.value in listOf(429, 502, 503, 504)) {
                            throw ProviderHttpFailure(response.status.value, ProviderCooldown.defer(config, response.headers[HttpHeaders.RetryAfter]))
                        }
                        throwIfToolDefinitionsRejected(response.status.value, !request.tools.isNullOrEmpty(), errorBody)

                        val errorMessage = if (response.status.value in setOf(401, 402, 403)) dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(response.status.value, errorBody).message() else providerErrorDetails(errorBody, "HTTP ${response.status.value}", config.token)
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                            "Provider",
                            "HTTP_ERROR · status=${response.status.value} · model=${request.model} · detail=$errorMessage",
                            "E"
                        )

                        emit(ResponseErrorEvent(message = if (response.status.value in setOf(401, 402, 403)) errorMessage else config.readableProviderError(errorMessage, response.status.value.toString()), code = response.status.value.toString()))
                        return@execute
                    }

                    config.freeDiagnostics?.stage = "generation"
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                        "Provider",
                        "REQUEST_ACCEPTED · model=${request.model} · server=${response.headers["Server"].orEmpty().take(120)} · requestId=${providerRequestIdentity { response.headers[it] }} · requestedOutputCap=${preparedRequest.maxOutputTokens} · serverEffectiveSettings=unverified"
                    )
                    // Success - read SSE stream
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readLine() ?: break
                        val data = SseUtils.extractSseData(line) ?: continue

                        if (data == "[DONE]") {
                            receivedTerminal = true
                            break
                        }

                        val decoded = try {
                            NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(data)
                        } catch (_: kotlinx.serialization.SerializationException) {
                            continue
                        }
                        val streamEvent = if (decoded is ResponseErrorEvent) {
                            decoded.copy(message = config.readableProviderError(providerErrorDetails(data, decoded.message, config.token), decoded.code))
                        } else {
                            decoded
                        }
                        receivedTerminal = receivedTerminal ||
                            streamEvent is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCompletedEvent ||
                            streamEvent is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent ||
                            streamEvent is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseIncompleteEvent ||
                            streamEvent is ResponseErrorEvent
                        receivedResponsePayload = receivedResponsePayload || streamEvent.hasResponseStreamPayload()
                        emit(streamEvent)
                    }
                    if (!receivedTerminal) throw java.io.EOFException("STREAM_INTERRUPTED: provider closed SSE before response.completed")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException) throw e
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "Network error: Unable to resolve host."
                is java.nio.channels.UnresolvedAddressException -> "Network error: Unable to resolve address. Check your internet connection."
                is java.net.ConnectException -> "Network error: Connection refused. Check the API URL."
                is HttpRequestTimeoutException -> "Request timed out."
                is java.net.SocketTimeoutException -> "Response timed out while waiting for the next chunk."
                is javax.net.ssl.SSLException -> "Network error: SSL/TLS connection failed."
                else -> if (ResilientStreamingClient.isPrematureConnectionClose(e)) {
                    if (receivedResponsePayload) "STREAM_INTERRUPTED: connection closed during the response." else "Connection closed before the response started. Please retry."
                } else {
                    e.message ?: "Unknown network error"
                }
            }
            emit(
                ResponseErrorEvent(
                    message = errorMessage,
                    code = "network_error"
                )
            )
        }
    }.flowOn(Dispatchers.IO)
}

private fun ChatCompletionChunk.hasAssistantStreamPayload(): Boolean =
    choices.orEmpty().any { choice ->
        val delta = choice.effectiveDelta
        !delta.content.isNullOrEmpty() ||
            !delta.effectiveReasoning.isNullOrEmpty() ||
            !delta.toolCalls.isNullOrEmpty() ||
            choice.finishReason != null
    }

private fun ResponsesStreamEvent.hasResponseStreamPayload(): Boolean = when (this) {
    is ResponseCreatedEvent,
    is ResponseInProgressEvent,
    UnknownEvent -> false

    else -> true
}

@Serializable
private data class OpenAIErrorResponse(
    val error: OpenAIError
)

@Serializable
private data class OpenAIError(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null
)

@Serializable
private data class OpenAIFileResponse(
    val id: String,
    val filename: String? = null,
    @kotlinx.serialization.SerialName("mime_type")
    val mimeType: String? = null
)

internal fun noVisibleAnswerCategory(hasReasoning: Boolean, limited: Boolean): FreeFailureCategory =
    if (hasReasoning && limited) FreeFailureCategory.REASONING_BUDGET_EXHAUSTED else FreeFailureCategory.NO_VISIBLE_ANSWER

internal fun freeResponseOutcome(provider: FreeAiProvider, hasAnswer: Boolean, hasTools: Boolean, hasError: Boolean, limited: Boolean, hasReasoning: Boolean = false): ChatCompletionChunk? = when {
    hasError || hasTools -> null
    !hasAnswer -> {
        val category = noVisibleAnswerCategory(hasReasoning, limited)
        val retry = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.value[provider]?.retryAt?.takeIf { it > System.currentTimeMillis() }?.let { " Next eligible retry: ${java.time.Instant.ofEpochMilli(it)}." }.orEmpty()
        ChatCompletionChunk(error = ErrorDetail(message = "${category.name}: ${provider.displayName} returned ${if (hasReasoning) "reasoning without a visible answer" else "no visible answer"}. ${category.action}.$retry", type = category.name.lowercase()))
    }
    limited -> ChatCompletionChunk(model = provider.model, applicationNotice = "This model reached its response limit. Would you like me to continue?", choices = listOf(Choice(delta = Delta(content = null), finishReason = "length")))
    else -> null
}
