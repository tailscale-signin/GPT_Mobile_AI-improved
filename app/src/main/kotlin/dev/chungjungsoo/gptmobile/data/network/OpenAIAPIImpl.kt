package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCreatedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseInProgressEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.UnknownEvent
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable

class OpenAIAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : OpenAIAPI {
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
        var receivedAssistantPayload = false
        try {
            val endpoint = config.buildEndpoint("chat/completions")

            networkClient().preparePost(endpoint) {
                applyPlatformStreamingTimeout(timeoutSeconds)
                contentType(ContentType.Application.Json)
                setBody(NetworkClient.openAIJson.encodeToString(request))
                accept(ContentType.Text.EventStream)
                config.token?.let { bearerAuth(it) }
                config.extraHeaders.forEach { (key, value) -> header(key, value) }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.body<String>()
                    throwIfToolDefinitionsRejected(response.status.value, !request.tools.isNullOrEmpty(), errorBody)

                    val errorMessage = try {
                        val errorResponse = NetworkClient.openAIJson.decodeFromString<OpenAIErrorResponse>(errorBody)
                        errorResponse.error.message
                    } catch (_: Exception) {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(
                        ChatCompletionChunk(
                            error = ErrorDetail(
                                message = errorMessage,
                                type = "http_error",
                                code = response.status.value.toString()
                            )
                        )
                    )
                    return@execute
                }

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

                // If gateway metadata is present, emit an initial chunk carrying the metadata
                var firstChunk = true

                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break
                    val data = SseUtils.extractSseData(line) ?: continue

                    // OpenAI sends "[DONE]" as final message
                    if (data == "[DONE]") break

                    try {
                        val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(data)
                        receivedAssistantPayload = receivedAssistantPayload || chunk.hasAssistantStreamPayload()
                        if (firstChunk && gatewayMetadata != null) {
                            firstChunk = false
                            emit(chunk.copy(gatewayMetadata = gatewayMetadata))
                        } else {
                            emit(chunk)
                        }
                    } catch (_: Exception) {
                        // Skip malformed chunks
                    }
                }

                // If no chunks were emitted but metadata was present, emit a metadata chunk
                if (firstChunk && gatewayMetadata != null) {
                    emit(ChatCompletionChunk(gatewayMetadata = gatewayMetadata))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException) throw e
            if (ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(receivedAssistantPayload, e)) {
                return@flow
            }
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "Network error: Unable to resolve host."
                is java.nio.channels.UnresolvedAddressException -> "Network error: Unable to resolve address. Check your internet connection."
                is java.net.ConnectException -> "Network error: Connection refused. Check the API URL."
                is HttpRequestTimeoutException -> "Request timed out."
                is java.net.SocketTimeoutException -> "Response timed out while waiting for the next chunk."
                is javax.net.ssl.SSLException -> "Network error: SSL/TLS connection failed."
                else -> if (ResilientStreamingClient.isPrematureConnectionClose(e)) {
                    "Connection closed before the response started. Please retry."
                } else {
                    e.message ?: "Unknown network error"
                }
            }
            emit(
                ChatCompletionChunk(
                    error = ErrorDetail(
                        message = errorMessage,
                        type = "network_error"
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun streamResponses(
        request: ResponsesRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<ResponsesStreamEvent> = flow {
        var receivedResponsePayload = false
        try {
            val endpoint = config.buildEndpoint("responses")

            networkClient().preparePost(endpoint) {
                applyPlatformStreamingTimeout(timeoutSeconds)
                contentType(ContentType.Application.Json)
                setBody(NetworkClient.openAIJson.encodeToString(request))
                accept(ContentType.Text.EventStream)
                config.token?.let { bearerAuth(it) }
                config.extraHeaders.forEach { (key, value) -> header(key, value) }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.body<String>()
                    throwIfToolDefinitionsRejected(response.status.value, !request.tools.isNullOrEmpty(), errorBody)

                    val errorMessage = try {
                        val errorResponse = NetworkClient.openAIJson.decodeFromString<OpenAIErrorResponse>(errorBody)
                        errorResponse.error.message
                    } catch (_: Exception) {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(ResponseErrorEvent(message = errorMessage, code = response.status.value.toString()))
                    return@execute
                }

                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break
                    val data = SseUtils.extractSseData(line) ?: continue

                    if (data == "[DONE]") break

                    try {
                        val streamEvent = NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(data)
                        receivedResponsePayload = receivedResponsePayload || streamEvent.hasResponseStreamPayload()
                        emit(streamEvent)
                    } catch (_: Exception) {
                        emit(UnknownEvent)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException) throw e
            if (ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(receivedResponsePayload, e)) {
                return@flow
            }
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "Network error: Unable to resolve host."
                is java.nio.channels.UnresolvedAddressException -> "Network error: Unable to resolve address. Check your internet connection."
                is java.net.ConnectException -> "Network error: Connection refused. Check the API URL."
                is HttpRequestTimeoutException -> "Request timed out."
                is java.net.SocketTimeoutException -> "Response timed out while waiting for the next chunk."
                is javax.net.ssl.SSLException -> "Network error: SSL/TLS connection failed."
                else -> if (ResilientStreamingClient.isPrematureConnectionClose(e)) {
                    "Connection closed before the response started. Please retry."
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
        val delta = choice.delta
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
