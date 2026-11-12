package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ErrorResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageStopResponseChunk
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.accept
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ANTHROPIC_FILES_BETA = "files-api-2025-04-14"

class AnthropicAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : AnthropicAPI {
    private val requestPreparation = ModelRequestPreparation(networkClient)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    override suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        config: ProviderRequestConfig
    ): UploadedProviderFile {
        val endpoint = config.buildEndpoint("files")
        val responseBody = networkClient().preparePost(endpoint) {
            setBody(
                MultiPartFormDataContent(
                    formData {
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
            headers {
                append(API_KEY_HEADER, config.token ?: "")
                append(VERSION_HEADER, ANTHROPIC_VERSION)
                append(BETA_HEADER, ANTHROPIC_FILES_BETA)
            }
        }.body<String>()

        val uploadResponse = json.decodeFromString<AnthropicFileResponse>(responseBody)
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
                headers {
                    append(API_KEY_HEADER, config.token ?: "")
                    append(VERSION_HEADER, ANTHROPIC_VERSION)
                    append(BETA_HEADER, ANTHROPIC_FILES_BETA)
                }
            }.execute { response ->
                response.status.isSuccess()
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun streamChatMessage(
        messageRequest: MessageRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<MessageResponseChunk> = flow {
        val workerBudget = kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]
        var receivedPayload = false
        var responseEvidence: AnthropicResponseEvidence? = null
        emitAll(
            flow<MessageResponseChunk> {
                val endpoint = config.buildEndpoint("messages")

                ResilientStreamingClient.executeWithRetry(
                    config = ResilientStreamingClient.RetryConfig(maxAttempts = 3),
                    shouldRetry = { !receivedPayload }
                ) {
                    // A new physical attempt cannot inherit the previous response's status or ID.
                    responseEvidence = null
                    ProviderCooldown.withPermit(config) {
                        val admittedPayload = requestPreparation.prepare(json.encodeToString(messageRequest), config, messageRequest.model, null)
                        networkClient().preparePost(endpoint) {
                            applyPlatformStreamingTimeout(timeoutSeconds, workerBudget)
                            contentType(ContentType.Application.Json)
                            setBody(admittedPayload)
                            accept(if (messageRequest.stream) ContentType.Text.EventStream else ContentType.Application.Json)
                            headers {
                                append(API_KEY_HEADER, config.token ?: "")
                                append(VERSION_HEADER, ANTHROPIC_VERSION)
                                append(BETA_HEADER, anthropicBetaHeader(config.anthropicBetaFeatures))
                            }
                        }.execute { response ->
                            val observed = AnthropicResponseEvidence(
                                response.status.value,
                                safeProviderRequestIdentity(response.headers["request-id"])
                            )
                            responseEvidence = observed
                            if (!response.status.isSuccess()) {
                                if (response.status.value in setOf(429, 502, 503, 504, 529)) {
                                    throw ProviderCooldown.rejected(
                                        config,
                                        response.status.value,
                                        response.headers[HttpHeaders.RetryAfter],
                                        knownNotAccepted = response.status.value == 429 && !receivedPayload,
                                        errorOrigin = "provider",
                                        observedHttpStatus = observed.status,
                                        serverRequestId = observed.requestId
                                    )
                                }
                                val errorBody = response.body<String>()
                                throwIfToolDefinitionsRejected(
                                    response.status.value,
                                    !messageRequest.tools.isNullOrEmpty(),
                                    errorBody
                                )

                                val errorResponse = try {
                                    json.decodeFromString<AnthropicErrorResponse>(errorBody)
                                } catch (_: Exception) {
                                    null
                                }
                                val typed = requireNotNull(
                                    providerExceptionDetails(
                                        ProviderHttpFailure(observed.status, null),
                                        receivedPayload
                                    )
                                )
                                emit(
                                    ErrorResponseChunk(
                                        error = ErrorDetail(
                                            type = errorResponse?.error?.type ?: "api_error",
                                            message = errorResponse?.error?.message ?: "HTTP ${observed.status}: $errorBody",
                                            code = typed.code,
                                            failureDetails = typed.failureDetails
                                        ).withResponseEvidence(observed, receivedPayload)
                                    )
                                )
                                return@execute
                            }

                            ProviderCooldown.observeQuota(config) { response.headers[it] }
                            if (!messageRequest.stream) {
                                val chunks = anthropicCompleteResponseChunks(response.body<String>())
                                receivedPayload = true
                                if (chunks.none { it is ErrorResponseChunk }) ProviderCooldown.accepted(config)
                                chunks.forEach { emit(it) }
                                return@execute
                            }
                            val channel = response.bodyAsChannel()
                            while (!channel.isClosedForRead) {
                                val line = channel.readLine() ?: break
                                val data = SseUtils.extractSseData(line) ?: continue

                                var chunk = try {
                                    json.decodeFromString<MessageResponseChunk>(data)
                                } catch (_: kotlinx.serialization.SerializationException) {
                                    // Skip malformed payloads, never exceptions from downstream emit.
                                    continue
                                }
                                if (chunk is ErrorResponseChunk && chunk.error.type == "rate_limit_error" && ProviderCooldown.durableRateLimits()) {
                                    throw ProviderCooldown.rejected(
                                        config,
                                        429,
                                        response.headers[HttpHeaders.RetryAfter],
                                        knownNotAccepted = !receivedPayload,
                                        errorOrigin = "provider",
                                        errorType = chunk.error.type,
                                        checkpointContinuationAllowed = true,
                                        observedHttpStatus = observed.status,
                                        serverRequestId = observed.requestId
                                    )
                                }
                                if (chunk is ErrorResponseChunk) {
                                    chunk = chunk.copy(error = chunk.error.withResponseEvidence(observed, receivedPayload))
                                }
                                if (chunk == MessageStopResponseChunk) ProviderCooldown.accepted(config)
                                receivedPayload = true
                                emit(chunk)
                            }
                        }
                    }
                }
            }.catch { e ->
                if (e is CancellationException || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException) throw e
                if (e is ProviderHttpFailure && e.status == 429 && ProviderCooldown.durableRateLimits()) throw e
                if (ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(receivedPayload, e)) {
                    return@catch
                }
                val errorMessage = when (e) {
                    is java.net.UnknownHostException -> "Network error: Unable to resolve host."
                    is java.nio.channels.UnresolvedAddressException -> "Network error: Unable to resolve address. Check your internet connection."
                    is java.net.ConnectException -> "Network error: Connection refused. Check the API URL."
                    is HttpRequestTimeoutException -> "Request timed out."
                    is java.net.SocketTimeoutException -> "Response timed out while waiting for the next chunk."
                    is javax.net.ssl.SSLException -> "Network error: SSL/TLS connection failed."
                    else -> e.message ?: "Unknown network error"
                }
                val typed = (e as? Exception)?.let { providerExceptionDetails(it, receivedPayload) }
                val transport = networkFailureKind(e)
                val details = JsonObject(
                    typed?.failureDetails.orEmpty() + buildJsonObject {
                        if (typed == null && transport != NetworkFailureKind.UNKNOWN) put("failed_stage", transport.failedStage)
                        if (typed == null && transport == NetworkFailureKind.UNKNOWN) {
                            put("failed_stage", "model_invocation")
                            put("retryable", false)
                            put("request_replay_allowed", false)
                            put("logical_continuation_allowed", false)
                            put("remote_acceptance", "UNKNOWN")
                        }
                        put("partial_output_preserved", receivedPayload)
                        put("cause_types", JsonArray(sanitizedCauseTypes(e).map(::JsonPrimitive)))
                    }
                )
                val failure = ErrorDetail(
                    type = typed?.type ?: if (transport == NetworkFailureKind.UNKNOWN) "client_error" else "network_error",
                    message = typed?.message ?: errorMessage,
                    code = typed?.code ?: if (transport == NetworkFailureKind.UNKNOWN) "PROVIDER_INVOCATION_FAILED" else transport.code,
                    failureDetails = details
                )
                emit(ErrorResponseChunk(error = responseEvidence?.let { failure.withResponseEvidence(it, receivedPayload) } ?: failure))
            }
        )
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val API_KEY_HEADER = "x-api-key"
        private const val VERSION_HEADER = "anthropic-version"
        private const val BETA_HEADER = "anthropic-beta"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

private data class AnthropicResponseEvidence(val status: Int, val requestId: String?)

private fun ErrorDetail.withResponseEvidence(observed: AnthropicResponseEvidence, partialOutput: Boolean): ErrorDetail {
    val knownType = type.takeIf {
        it in setOf("invalid_request_error", "authentication_error", "billing_error", "permission_error", "not_found_error", "conflict_error", "request_too_large", "rate_limit_error", "api_error", "timeout_error", "overloaded_error")
    }
    return copy(
        code = code ?: knownType,
        failureDetails = JsonObject(
            failureDetails.orEmpty() + buildJsonObject {
                if (failureDetails?.containsKey("failed_stage") != true) put("failed_stage", "model_invocation")
                put("http_status", observed.status)
                put("dispatched", true)
                put("dispatch_state", "FAILED")
                put("partial_output_preserved", partialOutput)
                observed.requestId?.let { put("server_request_id", it) }
                knownType?.let { put("provider_error_type", it) }
            }
        )
    )
}

internal fun anthropicBetaHeader(additionalFeatures: Set<String>): String = (listOf(ANTHROPIC_FILES_BETA) + additionalFeatures.sorted()).distinct().joinToString(",")

@Serializable
private data class AnthropicErrorResponse(
    val type: String,
    val error: AnthropicError
)

@Serializable
private data class AnthropicError(
    val type: String,
    val message: String
)

@Serializable
private data class AnthropicFileResponse(
    val id: String,
    val filename: String? = null,
    @kotlinx.serialization.SerialName("mime_type")
    val mimeType: String? = null
)
