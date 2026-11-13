package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCreatedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseGatewayMetadataEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseInProgressEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.UnknownEvent
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayResponseMetadata
import dev.chungjungsoo.gptmobile.data.openrouter.account.recordOpenRouterReceipts
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

class OpenAIAPIImpl @Inject constructor(
    private val networkClient: NetworkClient,
    private val openRouterReceipts: dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptStore? = null
) : OpenAIAPI {
    private val requestPreparation = ModelRequestPreparation(networkClient)
    private val gatewayApi = dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPIImpl(networkClient)
    private val gatewayControls = dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlNegotiation(gatewayApi)
    private val gatewayRecovery = dev.chungjungsoo.gptmobile.data.network.gateway.GatewayTerminalRecovery(gatewayApi)

    override suspend fun checkSavedHordeJob(runId: String, chatId: Int, config: ProviderRequestConfig): String = FreeQueuedTransport(networkClient(), requestPreparation).checkSavedHordeJob(runId, chatId, config)

    override suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        config: ProviderRequestConfig
    ): UploadedProviderFile {
        val endpoint = config.buildEndpoint("files")
        val responseBody = dev.chungjungsoo.gptmobile.data.network.gateway.gatewayClientExchange(config, "other", safeRead = false, method = "POST", knownGateway = false) { headers ->
            networkClient().preparePost(endpoint) {
                config.token?.let { bearerAuth(it) }
                headers.forEach { (key, value) -> header(key, value) }
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
            }.execute { response ->
                GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                response.body<String>()
            }
        }

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
            dev.chungjungsoo.gptmobile.data.network.gateway.gatewayClientExchange(config, "other", safeRead = true, knownGateway = false) { headers ->
                networkClient().prepareGet(endpoint) {
                    config.token?.let { bearerAuth(it) }
                    headers.forEach { (key, value) -> header(key, value) }
                }.execute { response ->
                    GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                    config.freeDiagnostics?.responded()
                    response.status.isSuccess()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override fun streamChatCompletion(
        request: ChatCompletionRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<ChatCompletionChunk> = flow {
        var config = if (config.freeProvider != null) config.copy(freeDiagnostics = dev.chungjungsoo.gptmobile.data.free.FreeRequestDiagnostics()) else config
        val workerBudget = kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]
        val connectionTimeout = kotlinx.coroutines.currentCoroutineContext()[ProfileConnectionTimeout]?.millis
        var receivedAssistantPayload = false
        var receivedVisibleText = false
        var requestDispatched = false
        var llamaActivity: AutoCloseable? = null
        var receivedGatewayToolActivity = false
        val dispatchRetryGuard = ProviderDispatchRetryGuard(config)
        var receivedTerminal = false
        var resolvedFreeModel: String? = null
        var contextCorrected = false
        var recoveryCheck = false
        var requestedHealthModel = request.model
        var transportRetries = 0
        val transportStartedNs = System.nanoTime()
        var activeControlReceipt: ReasoningControlReceipt? = null
        var activeResponseObserver: ProviderResponseObserver? = null
        try {
            val free = config.freeProvider
            if (free != null) {
                FreeProviderPreflight.require(config, request.model)
            }
            var freeModel = free?.let {
                if (it == FreeAiProvider.LLM7) Llm7FreeModelSelection.resolve(networkClient(), config, request.model) else dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.requireModel(it, request.model)
            }
            if (free == FreeAiProvider.PORCHLM && request.model == "automatic") {
                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.refresh(free)
                freeModel = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.models(free).firstOrNull { it.id != "automatic" }
                check(freeModel != null) { "PorchLM model discovery is unavailable. Retry its status check later." }
            }
            if (free != null) {
                config = config.copy(freeModelSnapshot = requireNotNull(freeModel))
                requestedHealthModel = freeModel.id
                recoveryCheck = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.beginGeneration(free, freeModel.id, freeCredentialIdentity(free, config.token))
            }
            if (free == FreeAiProvider.POLLINATIONS) PollinationsClassic.requireText(request)
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
                    reasoningEffort = if (free == FreeAiProvider.POLLINATIONS) request.reasoningEffort ?: "low" else request.reasoningEffort.takeIf { free in setOf(FreeAiProvider.KILO, FreeAiProvider.BLOCKRUN) },
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
            config = ProviderConnectivityPreflight.withAvailability(config.apiUrl) {
                gatewayControls.prepare(GatewayClientDiagnostics.withHeaders(config, request.model))
            }
            val endpoint = config.buildEndpoint("chat/completions")

            val executeRequest: suspend () -> Unit = {
                LocalConnectionPreflight.requireReady(config.apiUrl)
                config.freeDiagnostics?.permitAcquiredAt = ProviderElapsedClock.now()
                if (transportRetries > 0) emit(ChatCompletionChunk(retryDurationMs = (System.nanoTime() - transportStartedNs) / 1_000_000, retryAttempts = transportRetries))
                config.freeDiagnostics?.stage = "preparation"
                var admittedOutputTokens: Int? = null
                val gatewayRequest = dev.chungjungsoo.gptmobile.data.network.gateway.gatewayConversationRequest(NetworkClient.openAIJson.encodeToString(preparedRequest), config)
                val admittedPayload = requestPreparation.prepare(gatewayRequest.payload, config.copy(onSerializedOutputTokens = { admittedOutputTokens = it }), preparedRequest.model, null)
                // The serializer bridge retains intent even when no response arrives. A
                // request observation must not masquerade as a provider response chunk.
                val reasoningControlReceipt = ReasoningControlReceipt.fromPayload(admittedPayload, config).also { activeControlReceipt = it }
                // Continuation needs the admitted physical cap in every chat mode,
                // including ordinary Free chats whose profile allows a larger output.
                if (free != null || config.captureGenerationReceipt || config.requestRole == "synthesis") admittedOutputTokens?.let { emit(ChatCompletionChunk(serializedOutputTokens = it)) }
                val quotaReservation = free?.let { provider ->
                    val receipt = config.freeDiagnostics?.admission
                    val estimate = dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimate(admittedPayload)
                    FreeAiRequestLimiter.shared.reserveAttempt(provider, preparedRequest.model, freeCredentialIdentity(provider, config.token), receipt?.promptTokens ?: estimate.promptTokens, receipt?.outputTokens ?: preparedRequest.maxTokens?.toLong() ?: provider.defaultOutputTokens.toLong())
                }
                val usageReceipt = FreeTokenUsageReceipt()
                var dispatchStarted = false
                var definitelyNotTransmitted = false
                var diagnosticHeaders = emptyMap<String, String>()
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    gatewayRequest.beforeDispatch(config)
                    gatewayRequest.armDispatch(config)
                    config.freeDiagnostics?.stage = "dispatch"
                    config.freeDiagnostics?.attempted()
                    dispatchStarted = true
                    val responseObserver = ProviderResponseObserver().also { activeResponseObserver = it }
                    dispatchRetryGuard.dispatched()
                    requestDispatched = true
                    diagnosticHeaders = GatewayClientDiagnostics.requestHeaders(config, "chat", "POST")
                    dev.chungjungsoo.gptmobile.data.network.gateway.GatewayJobPollCoordinator.continuing(config)
                    networkClient().preparePost(endpoint) {
                        applyPlatformStreamingTimeout(timeoutSeconds, workerBudget, connectionTimeout)
                        contentType(ContentType.Application.Json)
                        setBody(admittedPayload)
                        accept(if (preparedRequest.stream) ContentType.Text.EventStream else ContentType.Application.Json)
                        config.token?.takeIf { it.isNotBlank() && (free == null || free.acceptsUserToken) }?.let { bearerAuth(it) }
                        diagnosticHeaders.forEach { (key, value) -> header(key, value) }
                        if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                    }.execute { response ->
                        responseObserver.headersReceived(response.status.value) { response.headers[it] }
                        if (response.status.isSuccess()) {
                            dispatchRetryGuard.accepted()
                            ProviderCooldown.observeQuota(config) { response.headers[it] }
                        }
                        // Retain acceptance before parsing: even a reasoning-only interrupted stream has a request identity.
                        emit(ChatCompletionChunk(providerResponseTiming = responseObserver.snapshot(), reasoningControlReceipt = reasoningControlReceipt))
                        ProviderConnectivityPreflight.responseReceived(config.apiUrl)
                        GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                        config.freeDiagnostics?.responded()
                        if (!response.status.isSuccess()) {
                            val errorBody = if (free != null) readFreeErrorBody(response) else response.body<String>()
                            val failureDetails = JsonObject(
                                kotlinx.serialization.json.buildJsonObject {
                                    put("failed_stage", "model_invocation")
                                    put("http_status", response.status.value)
                                    responseObserver.snapshot().serverRequestId?.let { put("server_request_id", it) }
                                } + ProviderRejectionDiagnostics.response(response.status.value, errorBody, ProviderRejectionDiagnostics.Contract.CHAT_COMPLETIONS) +
                                    structuredProviderFailure(errorBody, response.status.value).orEmpty()
                            )
                            val retainedGatewayAttempts = hasRetainedGatewayAttempts(failureDetails)
                            // Error responses also own durable job identity. Publish trusted headers
                            // before any saved-result lookup which may itself lose connectivity.
                            gatewayResponseMetadata { response.headers[it] }?.let { metadata ->
                                emit(ChatCompletionChunk(gatewayMetadata = metadata, providerResponseTiming = responseObserver.snapshot(), reasoningControlReceipt = reasoningControlReceipt))
                            }
                            if (free == null) {
                                gatewayRecovery.read(response.status.value, failureDetails, config, preparedRequest.model, response.headers["X-Gateway-Job-ID"])?.let { saved ->
                                    receivedAssistantPayload = receivedAssistantPayload || saved.hasAssistantStreamPayload()
                                    receivedTerminal = true
                                    emit(saved.copy(providerResponseTiming = responseObserver.snapshot(), reasoningControlReceipt = reasoningControlReceipt))
                                    return@execute
                                }
                            }
                            if (free != null && !retainedGatewayAttempts) {
                                val retryAt = retainFreeHttpFailure(config, preparedRequest.model, response.status.value, errorBody) { response.headers[it] }
                                val retrySeconds = retryAt?.let { ((it - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(1) }
                                val detail = providerErrorDetails(errorBody, dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(response.status.value, errorBody).message(), config.token)
                                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordHttpFailure(free, response.status.value, detail, retryAt, preparedRequest.model, errorBody, freeCredentialIdentity(free, config.token))
                                dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, preparedRequest.model, detail, response.status.value, errorBody, { response.headers[it] }, retryAt)
                                if (retrySeconds != null) throw FreeAiRateLimitException(free, retrySeconds, cooldown = config.freeDiagnostics?.cooldown, localBlock = false)
                                val contextLimit = rejectedFreeContextLimit(response.status.value, errorBody)
                                if (contextLimit != null && !contextCorrected && !receivedAssistantPayload && !receivedGatewayToolActivity) {
                                    dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.learnContextLimit(free, preparedRequest.model, contextLimit)
                                    config = config.copy(freeModelSnapshot = config.freeModelSnapshot?.let { it.copy(contextTokens = minOf(it.contextTokens ?: contextLimit, contextLimit)) })
                                    // A fixed-ceiling route cannot repair context overflow by
                                    // pretending to request less output than its server allows.
                                    if (free.fixedOutputTokens == null) {
                                        contextCorrected = true
                                        preparedRequest = preparedRequest.copy(maxTokens = minOf(preparedRequest.maxTokens ?: 1024, 1024, contextLimit / 2).coerceAtLeast(1))
                                        throw FreeContextRetry()
                                    }
                                }
                            }
                            val parameter = rejectedSamplingParameter(errorBody)
                            if (response.status.value == 400 && parameter != null && !dispatchRetryGuard.remoteOutcomeUnknown && !retainedGatewayAttempts) {
                                val sanitized = when (parameter) {
                                    "temperature" -> preparedRequest.copy(temperature = null)
                                    "top_p" -> preparedRequest.copy(topP = null)
                                    else -> preparedRequest
                                }
                                if (sanitized != preparedRequest) {
                                    preparedRequest = sanitized
                                    dispatchRetryGuard.rejectedBeforeExecution()
                                    throw java.io.IOException("Retrying without unsupported sampling parameter: $parameter")
                                }
                            }
                            if (!retainedGatewayAttempts && response.status.value in listOf(429, 502, 503, 504) && ResilientStreamingClient.providerAllowsRetry(errorBody)) {
                                throw ProviderCooldown.rejected(
                                    config,
                                    response.status.value,
                                    response.headers[HttpHeaders.RetryAfter],
                                    knownNotAccepted = response.status.value == 429 &&
                                        !dispatchRetryGuard.remoteOutcomeUnknown &&
                                        !receivedAssistantPayload &&
                                        !receivedGatewayToolActivity &&
                                        !config.requireGatewayConversation &&
                                        config.gatewayConversationId == null &&
                                        config.gatewayConversationKey == null &&
                                        response.headers["X-Gateway-Job-ID"].isNullOrBlank(),
                                    rateReset = response.headers["X-RateLimit-Reset"],
                                    errorOrigin = response.headers["X-OpenRouter-Error-Origin"],
                                    errorType = rateLimitErrorType(errorBody)
                                )
                            }
                            if (!retainedGatewayAttempts) throwIfToolDefinitionsRejected(response.status.value, !request.tools.isNullOrEmpty(), errorBody)

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

                            val contract = providerResponseContractFailure(errorBody)
                            emit(
                                ChatCompletionChunk(
                                    providerResponseTiming = responseObserver.snapshot(),
                                    reasoningControlReceipt = reasoningControlReceipt,
                                    error = ErrorDetail(
                                        message = contract?.message ?: if (free?.acceptsUserToken == true || response.status.value !in setOf(401, 402, 403)) config.readableProviderError(errorMessage, response.status.value.toString()) else errorMessage,
                                        type = if (contract != null) "provider_contract_error" else config.freeDiagnostics?.category?.name ?: "http_error",
                                        code = contract?.code ?: (failureDetails?.get("code") as? JsonPrimitive)?.content ?: response.status.value.toString(),
                                        retryable = (failureDetails?.get("retryable") as? JsonPrimitive)?.booleanOrNull,
                                        failureDetails = failureDetails,
                                        providerLimit = providerLimitDecision(config, request.model, receivedAssistantPayload || receivedGatewayToolActivity, FreeProviderException(config.freeDiagnostics?.category ?: FreeFailureCategory.REQUEST_INVALID, "HTTP ${response.status.value}"))
                                    )
                                )
                            )
                            return@execute
                        }

                        free?.let { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordAccepted(it) }
                        LocalServiceHealth.recordLocalHandshakeSuccess(config.apiUrl)
                        config.freeDiagnostics?.stage = "generation"
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                            "Provider",
                            "REQUEST_ACCEPTED · model=${request.model} · server=${response.headers["Server"].orEmpty().take(120)} · requestId=${providerRequestIdentity { response.headers[it] }} · requestedOutputCap=${preparedRequest.maxTokens} · requestedReasoning=${preparedRequest.reasoningEffort ?: preparedRequest.reasoning?.toString() ?: "provider-default"} · reasoningCapabilityVerified=false · templateThinking=${preparedRequest.chatTemplateKwargs?.get("enable_thinking")} · serverEffectiveSettings=unverified"
                        )
                        // Absence is also observed: later responses must not inherit an earlier gateway route.
                        val gatewayMetadata = gatewayResponseMetadata { response.headers[it] } ?: GatewayResponseMetadata()

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
                            val responseBody = response.body<String>()
                            val decoded = decodeOpenAICompletion(NetworkClient.openAIJson, responseBody)
                            config.freeDiagnostics?.response = FreeResponseObservation.fromHeaders("json") { response.headers[it] }.withChunk(decoded)
                            usageReceipt.observe(decoded.usage, decoded.hasAssistantStreamPayload())
                            usageReceipt.completed(decoded.error == null)
                            if (decoded.error == null && decoded.hasAssistantStreamPayload()) ProviderCooldown.accepted(config)
                            val chunk = decoded.copy(
                                choices = decoded.choices?.map { choice ->
                                    if (choice.message != null && choice.finishReason == null) choice.copy(finishReason = "stop") else choice
                                },
                                gatewayMetadata = gatewayMetadata,
                                gatewayProvenance = decoded.gatewayProvenance?.takeIf { gatewayMetadata.transportVerified },
                                gatewayRecovery = decoded.gatewayRecovery?.takeIf { gatewayMetadata.transportVerified },
                                gatewayTiming = decoded.gatewayTiming?.takeIf { gatewayMetadata.transportVerified },
                                gatewayDeliveryTiming = decoded.gatewayDeliveryTiming?.takeIf { gatewayMetadata.transportVerified },
                                providerResponseTiming = responseObserver.parsed(decoded.hasAssistantGeneratedOutput()),
                                reasoningControlReceipt = reasoningControlReceipt,
                                error = decoded.error?.let { error ->
                                    val category = providerPayloadFailure(error.code, error.message, preparedRequest.toolChoice, !preparedRequest.tools.isNullOrEmpty())
                                    config.freeDiagnostics?.category = category
                                    val contract = dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.find(error.code, error.message)
                                    val uncertainToolWork = dispatchRetryGuard.remoteOutcomeUnknown || decoded.gatewayProgress?.event in setOf("tool_started", "tool_completed", "tool_failed", "remote_tool_handoff")
                                    val reportedMessage = contract?.message ?: "${category.name}: " + config.readableProviderError(providerErrorDetails(responseBody, error.message, config.token), error.code)
                                    val details = structuredProviderFailure(responseBody, response.status.value)
                                    error.copy(
                                        message = if (uncertainToolWork) uncertainGatewayFailureMessage(reportedMessage) else reportedMessage,
                                        code = if (uncertainToolWork) "GATEWAY_OUTCOME_UNKNOWN" else contract?.code ?: error.code,
                                        type = if (uncertainToolWork) {
                                            "gateway_outcome_unknown"
                                        } else if (contract != null) {
                                            "provider_contract_error"
                                        } else {
                                            category.name.lowercase()
                                        },
                                        retryable = if (uncertainToolWork || contract != null || hasRetainedGatewayAttempts(details)) false else error.retryable,
                                        failureDetails = if (uncertainToolWork) uncertainGatewayFailureDetails(details, responseObserver.snapshot(), error.code) else details
                                    )
                                }
                            )
                            if (free != null) {
                                val usable = chunk.error == null && chunk.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() || !it.effectiveDelta.toolCalls.isNullOrEmpty() }
                                if (free == FreeAiProvider.POLLINATIONS) {
                                    config.freeDiagnostics?.let { diagnostics ->
                                        diagnostics.response?.let { observation ->
                                            val key = responseDigest("${config.profileUid}|${freeCredentialIdentity(free, config.token)}|$admittedPayload")
                                            diagnostics.response = UnusableResponseCache.shared.observe(key, responseBody, observation, usable)
                                        }
                                    }
                                }
                                if (usable) {
                                    dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordResponse(free, chunk.model, response.headers["X-RateLimit-Remaining"] ?: response.headers["X-RateLimit-Remaining-Output"], freeCredentialIdentity(free, config.token), preparedRequest.model)
                                } else {
                                    if (chunk.error == null) config.freeDiagnostics?.category = if (config.freeDiagnostics?.response?.repeatedUnusableCache == true) FreeFailureCategory.CACHED_UNUSABLE_RESPONSE else noVisibleAnswerCategory(chunk.choices.orEmpty().any { !it.effectiveDelta.effectiveReasoning.isNullOrBlank() }, chunk.choices.orEmpty().any { it.finishReason == "length" })
                                    dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordTerminalFailure(free, preparedRequest.model, config.freeDiagnostics?.category?.name ?: chunk.error?.message ?: "No visible answer received", credentialIdentity = freeCredentialIdentity(free, config.token))
                                    dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, preparedRequest.model, chunk.error?.message ?: "No visible answer received", status = 200, header = { response.headers[it] }, retryAt = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.value[free]?.retryAt, terminal = true)
                                }
                            }
                            if (free != null && !chunk.model.isNullOrBlank()) dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("FreeProvider", "MODEL_RESOLVED · profile=${config.profileUid} · requested=${request.model} · resolved=${chunk.model} · capabilityVerification=unverified")
                            chunk.gatewayTiming?.takeIf { gatewayMetadata.transportVerified }?.record(providerRequestIdentity { response.headers[it] })
                            emit(chunk)
                            if (free != null) {
                                freeResponseOutcome(
                                    free,
                                    chunk.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() },
                                    chunk.choices.orEmpty().any { !it.effectiveDelta.toolCalls.isNullOrEmpty() },
                                    chunk.error != null,
                                    chunk.choices.orEmpty().any { it.finishReason == "length" },
                                    chunk.choices.orEmpty().any { !it.effectiveDelta.effectiveReasoning.isNullOrBlank() },
                                    config.freeDiagnostics?.category
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
                        config.freeDiagnostics?.response = FreeResponseObservation.fromHeaders("event-stream") { response.headers[it] }

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
                                decodeOpenAICompletion(NetworkClient.openAIJson, data)
                            } catch (_: kotlinx.serialization.SerializationException) {
                                parseFailures++
                                config.freeDiagnostics?.response = config.freeDiagnostics?.response?.copy(discardedChunks = parseFailures)
                                continue
                            }
                            val streamError = decoded.error
                            val streamFailureDetails = streamError?.let { structuredProviderFailure(data, response.status.value) }
                            val retainedGatewayAttempts = hasRetainedGatewayAttempts(streamFailureDetails)
                            if (streamError != null &&
                                !retainedGatewayAttempts &&
                                (streamError.code == "429" || rateLimitErrorType(data) == "rate_limit_exceeded") &&
                                currentCoroutineContext()[ProviderWaitContext]?.durableRateLimits == true &&
                                !dispatchRetryGuard.remoteOutcomeUnknown &&
                                !receivedGatewayToolActivity &&
                                !config.requireGatewayConversation &&
                                config.gatewayConversationId == null &&
                                config.gatewayConversationKey == null &&
                                response.headers["X-Gateway-Job-ID"].isNullOrBlank()
                            ) {
                                throw ProviderCooldown.rejected(
                                    config,
                                    429,
                                    response.headers[HttpHeaders.RetryAfter],
                                    knownNotAccepted = !receivedAssistantPayload,
                                    rateReset = response.headers["X-RateLimit-Reset"],
                                    errorOrigin = response.headers["X-OpenRouter-Error-Origin"],
                                    errorType = rateLimitErrorType(data),
                                    checkpointContinuationAllowed = true
                                )
                            }
                            val parsingTiming = responseObserver.parsed(decoded.hasAssistantGeneratedOutput())
                            config.freeDiagnostics?.response = config.freeDiagnostics?.response?.withChunk(decoded)
                            usageReceipt.observe(decoded.usage, decoded.hasAssistantStreamPayload())
                            if (decoded.gatewayProgress?.event in setOf("tool_started", "tool_completed", "tool_failed", "remote_tool_handoff")) {
                                receivedGatewayToolActivity = true
                            }
                            if (!retainedGatewayAttempts && !receivedAssistantPayload && !receivedGatewayToolActivity && !dispatchRetryGuard.remoteOutcomeUnknown) {
                                decoded.error?.let { error ->
                                    if (error.retryable != false && ResilientStreamingClient.isRetryable(IllegalStateException("${error.code.orEmpty()} ${error.message}"))) {
                                        throw java.io.IOException("Gateway/provider transient error: ${error.message}")
                                    }
                                }
                            }
                            val chunk = decoded.error?.let { error ->
                                val category = providerPayloadFailure(error.code, error.message, preparedRequest.toolChoice, !preparedRequest.tools.isNullOrEmpty())
                                config.freeDiagnostics?.category = category
                                val details = streamFailureDetails
                                val contract = dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.find(error.code, error.message)
                                val uncertainToolWork = receivedGatewayToolActivity || dispatchRetryGuard.remoteOutcomeUnknown
                                val reportedMessage = contract?.message ?: "${category.name}: " + config.readableProviderError(providerErrorDetails(data, error.message, config.token), error.code)
                                decoded.copy(
                                    error = error.copy(
                                        message = if (uncertainToolWork) uncertainGatewayFailureMessage(reportedMessage) else reportedMessage,
                                        code = if (uncertainToolWork) "GATEWAY_OUTCOME_UNKNOWN" else contract?.code ?: error.code,
                                        type = if (uncertainToolWork) {
                                            "gateway_outcome_unknown"
                                        } else if (contract != null) {
                                            "provider_contract_error"
                                        } else {
                                            category.name.lowercase()
                                        },
                                        retryable = if (uncertainToolWork || contract != null || hasRetainedGatewayAttempts(details)) false else error.retryable,
                                        failureDetails = if (uncertainToolWork) uncertainGatewayFailureDetails(details, responseObserver.snapshot(), error.code) else details
                                    )
                                )
                            } ?: decoded
                            chunk.gatewayTiming?.takeIf { gatewayMetadata.transportVerified }?.record(providerRequestIdentity { response.headers[it] })
                            chunk.timings?.let { timings ->
                                fun measured(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }?.toString() ?: "Not measured"
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Provider", "SERVER_TIMINGS · requestId=${providerRequestIdentity { response.headers[it] }} · queueMs=${measured(timings.queueMs)} · prefillMs=${measured(timings.promptMs)} · generationMs=${measured(timings.predictedMs)} · promptTokens=${timings.promptTokens ?: "Not measured"} · generatedTokens=${timings.predictedTokens ?: "Not measured"}")
                            }
                            val observedChunk = chunk.copy(gatewayProvenance = chunk.gatewayProvenance?.takeIf { gatewayMetadata.transportVerified }, gatewayRecovery = chunk.gatewayRecovery?.takeIf { gatewayMetadata.transportVerified }, gatewayTiming = chunk.gatewayTiming?.takeIf { gatewayMetadata.transportVerified }, gatewayDeliveryTiming = chunk.gatewayDeliveryTiming?.takeIf { gatewayMetadata.transportVerified }, providerResponseTiming = parsingTiming, reasoningControlReceipt = reasoningControlReceipt, observedFinishReason = chunk.choices.orEmpty().firstNotNullOfOrNull { it.finishReason })
                            if (free != null && !chunk.model.isNullOrBlank() && resolvedFreeModel != chunk.model) {
                                resolvedFreeModel = chunk.model
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("FreeProvider", "MODEL_RESOLVED · profile=${config.profileUid} · requested=${request.model} · resolved=${chunk.model} · capabilityVerification=unverified")
                            }
                            receivedTerminal = receivedTerminal || chunk.error != null || chunk.choices.orEmpty().any { it.finishReason != null }
                            if (receivedTerminal) terminalDrain.markTerminal()
                            receivedReasoning = receivedReasoning || chunk.choices.orEmpty().any { !it.effectiveDelta.effectiveReasoning.isNullOrBlank() }
                            receivedAnswer = receivedAnswer || chunk.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() }
                            receivedVisibleText = receivedVisibleText || receivedAnswer
                            receivedError = receivedError || chunk.error != null
                            if (free != null && chunk.error != null) dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, preparedRequest.model, chunk.error.message, status = 200, body = data, event = "error", terminal = true)
                            reachedOutputLimit = reachedOutputLimit || chunk.choices.orEmpty().any { it.finishReason == "length" }
                            if (llamaActivity == null && chunk.hasAssistantStreamPayload()) {
                                llamaActivity = dev.chungjungsoo.gptmobile.data.llama.LlamaGenerationActivity.started(config, request.model, providerRequestIdentity { response.headers[it] })
                            }
                            receivedAssistantPayload = receivedAssistantPayload || chunk.hasAssistantStreamPayload()
                            receivedToolCalls = receivedToolCalls || chunk.choices.orEmpty().any { !it.effectiveDelta.toolCalls.isNullOrEmpty() }
                            // Tool assembly commits only after the entire stream is validated.
                            // A malformed chunk can contain missing arguments even when a later
                            // finish marker looks valid, so retain the text but never execute it.
                            val publishedChunk = if (receivedToolCalls) observedChunk.copy(choices = chunk.choices?.map { if (it.finishReason in setOf("tool_calls", "function_call", "stop")) it.copy(finishReason = null) else it }) else observedChunk
                            if (firstChunk) {
                                firstChunk = false
                                emit(publishedChunk.copy(gatewayMetadata = gatewayMetadata))
                            } else {
                                emit(publishedChunk)
                            }
                            if (receivedError) break
                        }
                        // Observe an empty response before its terminal error or retry decision.
                        if (firstChunk) {
                            emit(ChatCompletionChunk(gatewayMetadata = gatewayMetadata, providerResponseTiming = responseObserver.snapshot(), reasoningControlReceipt = reasoningControlReceipt))
                        }
                        if (!receivedTerminal) throw java.io.EOFException("STREAM_INTERRUPTED: provider closed SSE before a completion marker")
                        usageReceipt.completed(!receivedError && parseFailures == 0)
                        if (!receivedError && parseFailures == 0 && receivedAssistantPayload) ProviderCooldown.accepted(config)
                        if (parseFailures > 0 && !receivedError) {
                            receivedError = true
                            config.freeDiagnostics?.category = FreeFailureCategory.STREAM_DEGRADED
                            emit(ChatCompletionChunk(error = ErrorDetail("STREAM_DEGRADED: $parseFailures response chunks could not be decoded. Usable text was retained, but completeness and tool arguments cannot be verified.", "stream_degraded", code = "STREAM_DEGRADED"), providerResponseTiming = responseObserver.snapshot(), reasoningControlReceipt = reasoningControlReceipt))
                        }
                        if (receivedTerminal && receivedToolCalls && !receivedError) emit(ChatCompletionChunk(streamFinished = true))
                        if (free != null) {
                            if (!receivedError && (receivedAnswer || receivedToolCalls)) {
                                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordResponse(free, resolvedFreeModel, response.headers["X-RateLimit-Remaining"] ?: response.headers["X-RateLimit-Remaining-Output"], freeCredentialIdentity(free, config.token), preparedRequest.model)
                            } else {
                                if (!receivedError) config.freeDiagnostics?.category = noVisibleAnswerCategory(receivedReasoning, reachedOutputLimit)
                                dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordTerminalFailure(free, preparedRequest.model, config.freeDiagnostics?.category?.name ?: "Provider returned an error", credentialIdentity = freeCredentialIdentity(free, config.token))
                                if (config.freeDiagnostics?.recorded != true) dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, preparedRequest.model, if (receivedError) "Provider returned an error" else "No visible answer received", status = 200, retryAt = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.value[free]?.retryAt, terminal = true)
                            }
                            freeResponseOutcome(free, receivedAnswer, receivedToolCalls, receivedError, reachedOutputLimit, receivedReasoning)?.let { emit(it) }
                        }
                    }
                } catch (error: Exception) {
                    GatewayClientDiagnostics.failed(config, diagnosticHeaders, error, "chat")
                    definitelyNotTransmitted = !dispatchStarted || error is java.net.UnknownHostException || error is java.nio.channels.UnresolvedAddressException
                    throw error
                } finally {
                    if (dispatchStarted) dev.chungjungsoo.gptmobile.data.network.gateway.GatewayJobPollCoordinator.continuing(config)
                    GatewayClientDiagnostics.finished(diagnosticHeaders)
                    FreeAiRequestLimiter.shared.settle(quotaReservation, usageReceipt.settledTokens(), definitelyNotTransmitted)
                }
            }
            val guardedRequest: suspend () -> Unit = {
                // Preflight may reject this attempt before executeRequest runs.
                // Its receipt must not inherit a prior attempt's headers or dispatch.
                activeControlReceipt = null
                activeResponseObserver = null
                requestDispatched = false
                ProviderConnectivityPreflight.withAvailability(config.apiUrl) {
                    ProviderCooldown.withPermit(config, executeRequest)
                }
            }
            if (free != null) {
                config.freeDiagnostics?.permitWaitStartedAt = ProviderElapsedClock.now()
                try {
                    FreeAiRequestLimiter.shared.withPermit(free, preparedRequest.model, freeCredentialIdentity(free, config.token), guardedRequest)
                } catch (_: FreeContextRetry) {
                    config.freeDiagnostics?.recorded = false
                    emit(ChatCompletionChunk(applicationNotice = "${free.displayName}: adjusting output to the model's context window and retrying once."))
                    FreeAiRequestLimiter.shared.withPermit(free, preparedRequest.model, freeCredentialIdentity(free, config.token), guardedRequest)
                }
            } else {
                ResilientStreamingClient.executeWithRetry(
                    config = ResilientStreamingClient.RetryConfig(maxAttempts = 3, initialDelayMs = 250, maxDelayMs = 1000),
                    shouldRetry = { dispatchRetryGuard.mayRetry(receivedAssistantPayload || receivedGatewayToolActivity) },
                    onRetry = { attempt, delay, failure ->
                        ProviderConnectivityPreflight.failed(config.apiUrl, failure)
                        LocalServiceHealth.recordLocalFailure(config.apiUrl, failure)
                        transportRetries = attempt
                        if (ResilientStreamingClient.isPrematureConnectionClose(failure)) LocalEndpointConnectionPolicy.peerClosed(endpoint)
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                            "ProviderRetry",
                            "CHAT_RETRY · attempt=$attempt delayMs=$delay · ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                            "W"
                        )
                    }
                ) { guardedRequest() }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException) throw e
            ProviderConnectivityPreflight.failed(config.apiUrl, e)
            LocalServiceHealth.recordLocalFailure(config.apiUrl, e)
            val gatewayOutcomeUnknown = dispatchRetryGuard.remoteOutcomeUnknown || receivedGatewayToolActivity
            val transportFailure = networkFailureKind(e)
            val providerFailure = providerExceptionDetails(e, receivedAssistantPayload)
            val failureCode = providerFailure?.code ?: if (transportFailure == NetworkFailureKind.UNKNOWN) "PROVIDER_INVOCATION_FAILED" else transportFailure.code
            val failureRetryable = !gatewayOutcomeUnknown && e !is LocalServiceUnavailableException && e !is ProviderConnectivityPendingException && dispatchRetryGuard.mayRetry(receivedAssistantPayload || receivedGatewayToolActivity) && (providerFailure?.retryable ?: transportFailure.retryable)
            val errorMessage = if (gatewayOutcomeUnknown) {
                "GATEWAY_OUTCOME_UNKNOWN: The connection ended after a request that allows server tools was dispatched. Check the saved gateway job before resuming; the request was not replayed."
            } else if (providerFailure != null) {
                providerFailure.message
            } else if (e.message.orEmpty().startsWith("LOCAL_SERVICE_UNREACHABLE:")) {
                e.message.orEmpty() + ". Local server offline or unreachable; check the server listen address, port and firewall, then use Test connection to retry immediately."
            } else if (transportFailure != NetworkFailureKind.UNKNOWN) {
                transportFailure.userMessage(receivedVisibleText, activeResponseObserver?.snapshot()?.httpStatus in 200..299)
            } else {
                when (e) {
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
                    if (e !is FreeProviderException) dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordFailure(provider, errorMessage, readiness, if (networkFailure) false else null, cooldown, request.model, freeCredentialIdentity(provider, config.token))
                    dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, request.model, errorMessage, retryAt = cooldown, terminal = config.freeDiagnostics?.terminal ?: false.takeIf { config.freeDiagnostics?.jobId != null })
                }
            }
            emit(
                ChatCompletionChunk(
                    providerResponseTiming = activeResponseObserver?.snapshot(),
                    reasoningControlReceipt = activeControlReceipt,
                    error = ErrorDetail(
                        message = errorMessage,
                        type = if (config.freeProvider != null) (config.freeDiagnostics?.category ?: freeFailureCategory(e)).name else providerFailure?.type ?: if (transportFailure == NetworkFailureKind.UNKNOWN) "provider_error" else "network_error",
                        code = if (gatewayOutcomeUnknown) {
                            "GATEWAY_OUTCOME_UNKNOWN"
                        } else if (e is LocalServiceUnavailableException) {
                            "LOCAL_SERVICE_UNREACHABLE"
                        } else {
                            failureCode
                        },
                        retryable = failureRetryable,
                        failureDetails = providerTransportFailureDetails(
                            details = when {
                                gatewayOutcomeUnknown -> kotlinx.serialization.json.buildJsonObject {
                                    put("failed_stage", kotlinx.serialization.json.JsonPrimitive("gateway_dispatch"))
                                    put("outcome", kotlinx.serialization.json.JsonPrimitive("unknown"))
                                    put("replay_safe", kotlinx.serialization.json.JsonPrimitive(false))
                                }
                                e is LocalServiceUnavailableException -> e.failureDetails()
                                providerFailure != null -> providerFailure.failureDetails
                                else -> kotlinx.serialization.json.buildJsonObject {
                                    put("code", kotlinx.serialization.json.JsonPrimitive(failureCode))
                                    put(
                                        "failed_stage",
                                        kotlinx.serialization.json.JsonPrimitive(
                                            if (e is ProviderConnectivityPendingException) {
                                                "provider_admission"
                                            } else if (transportFailure == NetworkFailureKind.UNKNOWN) {
                                                "stage_not_recorded"
                                            } else {
                                                transportFailure.failedStage
                                            }
                                        )
                                    )
                                    if (e is ProviderConnectivityPendingException) {
                                        put("dispatch_state", kotlinx.serialization.json.JsonPrimitive("PRECHECK_REJECTED"))
                                        put("dispatched", kotlinx.serialization.json.JsonPrimitive(false))
                                    }
                                    put("retryable", kotlinx.serialization.json.JsonPrimitive(failureRetryable))
                                    if (e is ProviderConnectivityPendingException) {
                                        put("waiting_for_connectivity", kotlinx.serialization.json.JsonPrimitive(true))
                                        put("retry_after_ms", kotlinx.serialization.json.JsonPrimitive(e.retryAfterMs))
                                    }
                                    put("partial_output_preserved", kotlinx.serialization.json.JsonPrimitive(receivedAssistantPayload))
                                }
                            },
                            error = e,
                            timing = activeResponseObserver?.snapshot(),
                            dispatched = requestDispatched,
                            receivedPayload = receivedAssistantPayload,
                            receivedVisibleText = receivedVisibleText,
                            generationCompleted = receivedTerminal,
                            replayAllowed = failureRetryable,
                            requiresReconciliation = gatewayOutcomeUnknown
                        ),
                        providerLimit = providerLimitDecision(config, request.model, receivedAssistantPayload || receivedGatewayToolActivity, e)
                    )
                )
            )
        } finally {
            llamaActivity?.close()
            if (recoveryCheck) config.freeProvider?.let { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.endGeneration(it, requestedHealthModel, freeCredentialIdentity(it, config.token)) }
        }
    }.normalizeFreeTextBlocks(config.freeProvider).recordOpenRouterReceipts(config.openRouterReceipt, openRouterReceipts).flowOn(Dispatchers.IO)

    override fun streamResponses(
        request: ResponsesRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<ResponsesStreamEvent> = flow {
        val workerBudget = kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]
        val connectionTimeout = kotlinx.coroutines.currentCoroutineContext()[ProfileConnectionTimeout]?.millis
        var receivedResponsePayload = false
        var receivedVisibleText = false
        var requestDispatched = false
        var activeResponseObserver: ProviderResponseObserver? = null
        val dispatchRetryGuard = ProviderDispatchRetryGuard(config)
        var receivedTerminal = false
        var resolvedFreeModel: String? = null
        var preparedRequest = request.withModelSamplingPolicy()
        var effectiveConfig = config
        try {
            effectiveConfig = ProviderConnectivityPreflight.withAvailability(config.apiUrl) {
                gatewayControls.prepare(GatewayClientDiagnostics.withHeaders(config, request.model))
            }
            val endpoint = effectiveConfig.buildEndpoint("responses")

            ResilientStreamingClient.executeWithRetry(
                config = ResilientStreamingClient.RetryConfig(maxAttempts = 3, initialDelayMs = 250, maxDelayMs = 1000),
                shouldRetry = { dispatchRetryGuard.mayRetry(receivedResponsePayload) },
                onRetry = { attempt, delay, failure ->
                    ProviderConnectivityPreflight.failed(effectiveConfig.apiUrl, failure)
                    LocalServiceHealth.recordLocalFailure(effectiveConfig.apiUrl, failure)
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                        "ProviderRetry",
                        "RESPONSES_RETRY · attempt=$attempt delayMs=$delay · ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                        "W"
                    )
                }
            ) {
                // Reset before the availability gate, including retries rejected
                // without dispatch after a preceding transport/DNS failure.
                requestDispatched = false
                activeResponseObserver = null
                ProviderConnectivityPreflight.withAvailability(effectiveConfig.apiUrl) {
                    LocalConnectionPreflight.requireReady(effectiveConfig.apiUrl)
                    ProviderCooldown.withPermit(effectiveConfig) {
                        effectiveConfig.freeDiagnostics?.stage = "preparation"
                        var admittedOutputTokens: Int? = null
                        val gatewayRequest = dev.chungjungsoo.gptmobile.data.network.gateway.gatewayConversationRequest(NetworkClient.openAIJson.encodeToString(preparedRequest), effectiveConfig)
                        val admittedPayload = requestPreparation.prepare(gatewayRequest.payload, effectiveConfig.copy(onSerializedOutputTokens = { admittedOutputTokens = it }), preparedRequest.model, null)
                        gatewayRequest.beforeDispatch(effectiveConfig)
                        gatewayRequest.armDispatch(effectiveConfig)
                        effectiveConfig.freeDiagnostics?.stage = "dispatch"
                        effectiveConfig.freeDiagnostics?.attempted()
                        dispatchRetryGuard.dispatched()
                        requestDispatched = true
                        val responseObserver = ProviderResponseObserver().also { activeResponseObserver = it }
                        dev.chungjungsoo.gptmobile.data.network.gateway.gatewayClientExchange(effectiveConfig, "chat", safeRead = false, method = "POST", knownGateway = false) { diagnosticHeaders ->
                            networkClient().preparePost(endpoint) {
                                applyPlatformStreamingTimeout(timeoutSeconds, workerBudget, connectionTimeout)
                                contentType(ContentType.Application.Json)
                                setBody(admittedPayload)
                                accept(ContentType.Text.EventStream)
                                effectiveConfig.token?.let { bearerAuth(it) }
                                diagnosticHeaders.forEach { (key, value) -> header(key, value) }
                            }.execute { response ->
                                responseObserver.headersReceived(response.status.value) { response.headers[it] }
                                if (response.status.isSuccess()) {
                                    dispatchRetryGuard.accepted()
                                    ProviderCooldown.observeQuota(effectiveConfig) { response.headers[it] }
                                }
                                emit(ResponseGatewayMetadataEvent(providerResponseTiming = responseObserver.snapshot()))
                                ProviderConnectivityPreflight.responseReceived(effectiveConfig.apiUrl)
                                GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                                effectiveConfig.freeDiagnostics?.responded()
                                if (!response.status.isSuccess()) {
                                    val errorBody = response.body<String>()
                                    val parameter = rejectedSamplingParameter(errorBody)
                                    if (response.status.value == 400 && parameter != null && !dispatchRetryGuard.remoteOutcomeUnknown) {
                                        val sanitized = when (parameter) {
                                            "temperature" -> preparedRequest.copy(temperature = null)
                                            "top_p" -> preparedRequest.copy(topP = null)
                                            else -> preparedRequest
                                        }
                                        if (sanitized != preparedRequest) {
                                            preparedRequest = sanitized
                                            dispatchRetryGuard.rejectedBeforeExecution()
                                            throw java.io.IOException("Retrying without unsupported sampling parameter: $parameter")
                                        }
                                    }
                                    if (response.status.value in listOf(429, 502, 503, 504)) {
                                        throw ProviderCooldown.rejected(
                                            effectiveConfig,
                                            response.status.value,
                                            response.headers[HttpHeaders.RetryAfter],
                                            knownNotAccepted = response.status.value == 429 &&
                                                !dispatchRetryGuard.remoteOutcomeUnknown &&
                                                !receivedResponsePayload &&
                                                !effectiveConfig.requireGatewayConversation &&
                                                effectiveConfig.gatewayConversationId == null &&
                                                effectiveConfig.gatewayConversationKey == null &&
                                                response.headers["X-Gateway-Job-ID"].isNullOrBlank(),
                                            rateReset = response.headers["X-RateLimit-Reset"],
                                            errorOrigin = response.headers["X-OpenRouter-Error-Origin"],
                                            errorType = rateLimitErrorType(errorBody)
                                        )
                                    }
                                    throwIfToolDefinitionsRejected(response.status.value, !request.tools.isNullOrEmpty(), errorBody)

                                    val errorMessage = if (response.status.value in setOf(401, 402, 403)) dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(response.status.value, errorBody).message() else providerErrorDetails(errorBody, "HTTP ${response.status.value}", effectiveConfig.token)
                                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                                        "Provider",
                                        "HTTP_ERROR · status=${response.status.value} · model=${request.model} · detail=$errorMessage",
                                        "E"
                                    )

                                    emit(ResponseErrorEvent(message = if (response.status.value in setOf(401, 402, 403)) errorMessage else effectiveConfig.readableProviderError(errorMessage, response.status.value.toString()), code = response.status.value.toString()))
                                    return@execute
                                }

                                LocalServiceHealth.recordLocalHandshakeSuccess(effectiveConfig.apiUrl)
                                effectiveConfig.freeDiagnostics?.stage = "generation"
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                                    "Provider",
                                    "REQUEST_ACCEPTED · model=${request.model} · server=${response.headers["Server"].orEmpty().take(120)} · requestId=${providerRequestIdentity { response.headers[it] }} · requestedOutputCap=${preparedRequest.maxOutputTokens} · serverEffectiveSettings=unverified"
                                )
                                // Responses API requests need the same response-bound provenance as chat completions.
                                emit(ResponseGatewayMetadataEvent(gatewayResponseMetadata { response.headers[it] } ?: GatewayResponseMetadata()))
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
                                    val errorCode = when (decoded) {
                                        is ResponseErrorEvent -> decoded.code
                                        is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent -> decoded.response.error?.code
                                        else -> null
                                    }
                                    if (errorCode in setOf("429", "rate_limit_exceeded") &&
                                        ProviderCooldown.durableRateLimits() &&
                                        !dispatchRetryGuard.remoteOutcomeUnknown &&
                                        !effectiveConfig.requireGatewayConversation &&
                                        effectiveConfig.gatewayConversationId == null &&
                                        effectiveConfig.gatewayConversationKey == null &&
                                        response.headers["X-Gateway-Job-ID"].isNullOrBlank()
                                    ) {
                                        throw ProviderCooldown.rejected(
                                            effectiveConfig,
                                            429,
                                            response.headers[HttpHeaders.RetryAfter],
                                            knownNotAccepted = !receivedResponsePayload,
                                            rateReset = response.headers["X-RateLimit-Reset"],
                                            errorOrigin = response.headers["X-OpenRouter-Error-Origin"],
                                            errorType = errorCode,
                                            checkpointContinuationAllowed = true
                                        )
                                    }
                                    val streamEvent = when (decoded) {
                                        is ResponseErrorEvent -> {
                                            val reportedMessage = effectiveConfig.readableProviderError(providerErrorDetails(data, decoded.message, effectiveConfig.token), decoded.code)
                                            if (dispatchRetryGuard.remoteOutcomeUnknown) {
                                                decoded.copy(message = uncertainGatewayFailureMessage(reportedMessage), code = "GATEWAY_OUTCOME_UNKNOWN", failureDetails = uncertainGatewayFailureDetails(decoded.failureDetails ?: structuredResponsesFailure(data, response.status.value), responseObserver.snapshot(), decoded.code))
                                            } else {
                                                decoded.copy(message = reportedMessage)
                                            }
                                        }
                                        is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent -> {
                                            if (dispatchRetryGuard.remoteOutcomeUnknown) {
                                                val reported = decoded.response.error
                                                ResponseErrorEvent(
                                                    message = uncertainGatewayFailureMessage(effectiveConfig.readableProviderError(providerErrorDetails(data, reported?.message ?: "Response failed", effectiveConfig.token), reported?.code)),
                                                    code = "GATEWAY_OUTCOME_UNKNOWN",
                                                    failureDetails = uncertainGatewayFailureDetails(structuredResponsesFailure(data, response.status.value), responseObserver.snapshot(), reported?.code)
                                                )
                                            } else {
                                                decoded
                                            }
                                        }
                                        else -> decoded
                                    }
                                    receivedTerminal = receivedTerminal ||
                                        streamEvent is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCompletedEvent ||
                                        streamEvent is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent ||
                                        streamEvent is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseIncompleteEvent ||
                                        streamEvent is ResponseErrorEvent
                                    receivedResponsePayload = receivedResponsePayload || streamEvent.hasResponseStreamPayload()
                                    receivedVisibleText = receivedVisibleText || streamEvent is dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDeltaEvent && streamEvent.delta.isNotBlank()
                                    if (streamEvent is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCompletedEvent) {
                                        ProviderCooldown.accepted(effectiveConfig)
                                    }
                                    emit(streamEvent)
                                }
                                if (!receivedTerminal) throw java.io.EOFException("STREAM_INTERRUPTED: provider closed SSE before response.completed")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException) throw e
            ProviderConnectivityPreflight.failed(effectiveConfig.apiUrl, e)
            LocalServiceHealth.recordLocalFailure(effectiveConfig.apiUrl, e)
            val gatewayOutcomeUnknown = dispatchRetryGuard.remoteOutcomeUnknown
            val transportFailure = networkFailureKind(e)
            val providerFailure = providerExceptionDetails(e, receivedResponsePayload)
            val failureCode = providerFailure?.code ?: if (transportFailure == NetworkFailureKind.UNKNOWN) "PROVIDER_INVOCATION_FAILED" else transportFailure.code
            val failureRetryable = !gatewayOutcomeUnknown && e !is LocalServiceUnavailableException && e !is ProviderConnectivityPendingException && dispatchRetryGuard.mayRetry(receivedResponsePayload) && (providerFailure?.retryable ?: transportFailure.retryable)
            val errorMessage = if (gatewayOutcomeUnknown) {
                "GATEWAY_OUTCOME_UNKNOWN: The connection ended after a request that allows server tools was dispatched. Check the saved gateway job before resuming; the request was not replayed."
            } else if (providerFailure != null) {
                providerFailure.message
            } else if (e.message.orEmpty().startsWith("LOCAL_SERVICE_UNREACHABLE:")) {
                e.message.orEmpty() + ". Local server offline or unreachable; check the server listen address, port and firewall, then use Test connection to retry immediately."
            } else if (transportFailure != NetworkFailureKind.UNKNOWN) {
                transportFailure.userMessage(receivedVisibleText, activeResponseObserver?.snapshot()?.httpStatus in 200..299)
            } else {
                when (e) {
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
            }
            emit(
                ResponseErrorEvent(
                    message = errorMessage,
                    code = if (gatewayOutcomeUnknown) {
                        "GATEWAY_OUTCOME_UNKNOWN"
                    } else if (e is LocalServiceUnavailableException) {
                        "LOCAL_SERVICE_UNREACHABLE"
                    } else {
                        failureCode
                    },
                    failureDetails = providerTransportFailureDetails(
                        details = when {
                            gatewayOutcomeUnknown -> kotlinx.serialization.json.buildJsonObject {
                                put("failed_stage", kotlinx.serialization.json.JsonPrimitive("gateway_dispatch"))
                                put("outcome", kotlinx.serialization.json.JsonPrimitive("unknown"))
                                put("replay_safe", kotlinx.serialization.json.JsonPrimitive(false))
                            }
                            e is LocalServiceUnavailableException -> e.failureDetails()
                            providerFailure != null -> providerFailure.failureDetails
                            else -> kotlinx.serialization.json.buildJsonObject {
                                put("code", kotlinx.serialization.json.JsonPrimitive(failureCode))
                                put(
                                    "failed_stage",
                                    kotlinx.serialization.json.JsonPrimitive(
                                        if (e is ProviderConnectivityPendingException) {
                                            "provider_admission"
                                        } else if (transportFailure == NetworkFailureKind.UNKNOWN) {
                                            "stage_not_recorded"
                                        } else {
                                            transportFailure.failedStage
                                        }
                                    )
                                )
                                if (e is ProviderConnectivityPendingException) {
                                    put("dispatch_state", kotlinx.serialization.json.JsonPrimitive("PRECHECK_REJECTED"))
                                    put("dispatched", kotlinx.serialization.json.JsonPrimitive(false))
                                }
                                put("retryable", kotlinx.serialization.json.JsonPrimitive(failureRetryable))
                                if (e is ProviderConnectivityPendingException) {
                                    put("waiting_for_connectivity", kotlinx.serialization.json.JsonPrimitive(true))
                                    put("retry_after_ms", kotlinx.serialization.json.JsonPrimitive(e.retryAfterMs))
                                }
                                put("partial_output_preserved", kotlinx.serialization.json.JsonPrimitive(receivedResponsePayload))
                            }
                        },
                        error = e,
                        timing = activeResponseObserver?.snapshot(),
                        dispatched = requestDispatched,
                        receivedPayload = receivedResponsePayload,
                        receivedVisibleText = receivedVisibleText,
                        generationCompleted = receivedTerminal,
                        replayAllowed = failureRetryable,
                        requiresReconciliation = gatewayOutcomeUnknown
                    ),
                    providerLimit = providerLimitDecision(effectiveConfig, request.model, receivedResponsePayload, e)
                )
            )
        }
    }.flowOn(Dispatchers.IO)
}

private fun rateLimitErrorType(body: String): String? = runCatching {
    val root = NetworkClient.openAIJson.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
    val error = root?.get("error") as? kotlinx.serialization.json.JsonObject
    val metadata = error?.get("metadata") as? kotlinx.serialization.json.JsonObject
    (metadata?.get("error_type") as? JsonPrimitive)?.content
}.getOrNull()

/** A server error is retained as a server report, not fabricated as a local socket exception. */
private fun uncertainGatewayFailureMessage(reportedMessage: String): String =
    "GATEWAY_OUTCOME_UNKNOWN: The accepted request may have performed server tool work. Check the saved gateway job before continuing; no request or tool action was replayed. Provider reported: $reportedMessage"

private fun uncertainGatewayFailureDetails(details: kotlinx.serialization.json.JsonObject?, timing: ProviderResponseTiming, reportedCode: String?): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.JsonObject(
        details.orEmpty() + kotlinx.serialization.json.buildJsonObject {
            put("code", JsonPrimitive("GATEWAY_OUTCOME_UNKNOWN"))
            put("failed_stage", JsonPrimitive("gateway_dispatch"))
            put("dispatched", JsonPrimitive(true))
            timing.httpStatus?.let { put("http_status", JsonPrimitive(it)) }
            put("remote_acceptance", JsonPrimitive(if (timing.httpStatus in 200..299) "YES" else "UNKNOWN"))
            put("generation_completed", JsonPrimitive(false))
            put("requires_reconciliation", JsonPrimitive(true))
            put("request_replay_allowed", JsonPrimitive(false))
            put("logical_continuation_allowed", JsonPrimitive(false))
            timing.serverRequestId?.let { put("server_request_id", JsonPrimitive(it)) }
            timing.generationId?.let { put("generation_id", JsonPrimitive(it)) }
            (reportedCode ?: (details?.get("code") as? JsonPrimitive)?.content)?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) }?.let { put("upstream_error_code", JsonPrimitive(it)) }
            (details?.get("failed_stage") as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) }?.let { put("upstream_failed_stage", JsonPrimitive(it)) }
            (details?.get("dispatched") as? JsonPrimitive)?.booleanOrNull?.let { put("upstream_dispatched", JsonPrimitive(it)) }
        }
    )

private fun structuredResponsesFailure(data: String, status: Int): kotlinx.serialization.json.JsonObject? {
    val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
    val error = ((root["response"] as? kotlinx.serialization.json.JsonObject)?.get("error") as? kotlinx.serialization.json.JsonObject) ?: root
    return structuredProviderFailure(kotlinx.serialization.json.JsonObject(mapOf("error" to error)).toString(), status)
}

private fun ChatCompletionChunk.hasAssistantGeneratedOutput(): Boolean =
    choices.orEmpty().any { choice ->
        val delta = choice.effectiveDelta
        !delta.content.isNullOrEmpty() || !delta.effectiveReasoning.isNullOrEmpty() || !delta.toolCalls.isNullOrEmpty()
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
    is ResponseGatewayMetadataEvent,
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

internal fun freeResponseOutcome(provider: FreeAiProvider, hasAnswer: Boolean, hasTools: Boolean, hasError: Boolean, limited: Boolean, hasReasoning: Boolean = false, failureCategory: FreeFailureCategory? = null): ChatCompletionChunk? = when {
    hasError || hasTools -> null
    !hasAnswer -> {
        val category = failureCategory ?: noVisibleAnswerCategory(hasReasoning, limited)
        val retry = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.value[provider]?.retryAt?.takeIf { it > System.currentTimeMillis() }?.let { " Next eligible retry: ${java.time.Instant.ofEpochMilli(it)}." }.orEmpty()
        ChatCompletionChunk(error = ErrorDetail(message = "${category.name}: ${provider.displayName} returned ${if (hasReasoning) "reasoning without a visible answer" else "no visible answer"}. ${category.action}.$retry", type = category.name.lowercase()))
    }
    limited -> ChatCompletionChunk(model = provider.model, applicationNotice = "This model reached its response limit. Would you like me to continue?", choices = listOf(Choice(delta = Delta(content = null), finishReason = "length")))
    else -> null
}

private fun dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayGenerationTiming.record(requestId: String) {
    val observed = observedFields()
    if (observed.isEmpty()) return
    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
        "Provider",
        "GATEWAY_BACKEND_TIMINGS · requestId=$requestId · source=physical_backend_request · " +
            observed.entries.joinToString(" · ") { "${it.key}=${it.value}" } + " · first_user_visible_text_ms=Not measured at transport"
    )
}
