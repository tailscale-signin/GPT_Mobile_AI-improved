package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqErrorDetail
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class GroqAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : GroqAPI {
    private val requestPreparation = ModelRequestPreparation(networkClient)

    override fun streamChatCompletion(
        request: GroqChatCompletionRequest,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<GroqChatCompletionChunk> = flow {
        val workerBudget = kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]
        var receivedPayload = false
        try {
            val endpoint = config.copy(apiUrl = config.apiUrl.ifBlank { ModelConstants.GROQ_API_URL }).buildEndpoint("chat/completions")

            ProviderCooldown.withPermit(config) {
                val admittedPayload = requestPreparation.prepare(NetworkClient.openAIJson.encodeToString(request), config, request.model, request.maxCompletionTokens)
                networkClient.inferenceClient().preparePost(endpoint) {
                    applyPlatformStreamingTimeout(timeoutSeconds, workerBudget)
                    contentType(ContentType.Application.Json)
                    setBody(admittedPayload)
                    accept(if (request.stream) ContentType.Text.EventStream else ContentType.Application.Json)
                    config.token?.let { bearerAuth(it) }
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        if (response.status.value == 429 && !receivedPayload) {
                            throw ProviderCooldown.rejected(
                                config,
                                429,
                                response.headers[HttpHeaders.RetryAfter],
                                knownNotAccepted = response.headers["X-Gateway-Job-ID"].isNullOrBlank(),
                                rateReset = response.headers["X-RateLimit-Reset"],
                                errorOrigin = "provider"
                            )
                        }
                        val errorBody = readProviderErrorBody(response, 256 * 1024)
                        throwIfToolDefinitionsRejected(response.status.value, !request.tools.isNullOrEmpty(), errorBody)
                        val errorMessage = dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(response.status.value, errorBody).message()

                        emit(
                            GroqChatCompletionChunk(
                                error = GroqErrorDetail(
                                    message = errorMessage,
                                    type = "http_error",
                                    code = response.status.value.toString()
                                )
                            )
                        )
                        return@execute
                    }

                    ProviderCooldown.observeQuota(config) { response.headers[it] }
                    if (!request.stream) {
                        val chunk = NetworkClient.openAIJson.decodeFromString<GroqChatCompletionChunk>(response.boundedResponseText())
                        if (chunk.error == null) ProviderCooldown.accepted(config)
                        receivedPayload = true
                        emit(chunk)
                        return@execute
                    }

                    var malformedEvent = false
                    val channel = response.bodyAsChannel()
                    val events = ProviderEventReader(channel)
                    while (!channel.isClosedForRead) {
                        val line = events.readLine() ?: break
                        val data = SseUtils.extractSseData(line) ?: continue

                        if (data == "[DONE]") {
                            if (malformedEvent && !receivedPayload) throw kotlinx.serialization.SerializationException("Unreadable Groq response.")
                            ProviderCooldown.accepted(config)
                            emit(GroqChatCompletionChunk(streamFinished = true))
                            break
                        }

                        val chunk = try {
                            NetworkClient.openAIJson.decodeFromString<GroqChatCompletionChunk>(data)
                        } catch (_: kotlinx.serialization.SerializationException) {
                            // A parser exception can contain private completion text.
                            malformedEvent = true
                            continue
                        }
                        val error = chunk.error
                        if (error != null &&
                            (error.code == "429" || error.code == "rate_limit_exceeded") &&
                            ProviderCooldown.durableRateLimits() &&
                            response.headers["X-Gateway-Job-ID"].isNullOrBlank()
                        ) {
                            throw ProviderCooldown.rejected(
                                config,
                                429,
                                response.headers[HttpHeaders.RetryAfter],
                                knownNotAccepted = !receivedPayload,
                                rateReset = response.headers["X-RateLimit-Reset"],
                                errorOrigin = "provider",
                                errorType = error.type,
                                checkpointContinuationAllowed = true
                            )
                        }
                        receivedPayload = true
                        emit(chunk)
                    }
                    if (malformedEvent && !receivedPayload) throw kotlinx.serialization.SerializationException("Unreadable Groq response.")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is ProviderHttpFailure || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException || e is dev.chungjungsoo.gptmobile.data.privacy.PrivateDispatchRejected) throw e
            if (ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(receivedPayload, e)) {
                return@flow
            }
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "Network error: Unable to resolve host."
                is java.nio.channels.UnresolvedAddressException -> "Network error: Unable to resolve address. Check your internet connection."
                is java.net.ConnectException -> "Network error: Connection refused. Check the API URL."
                is HttpRequestTimeoutException -> "Request timed out."
                is java.net.SocketTimeoutException -> "Response timed out while waiting for the next chunk."
                is javax.net.ssl.SSLException -> "Network error: SSL/TLS connection failed."
                else -> dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification.classify(e).userMessage
            }

            emit(
                GroqChatCompletionChunk(
                    error = GroqErrorDetail(
                        message = errorMessage,
                        type = if (e is kotlinx.serialization.SerializationException) "serialization_error" else "network_error"
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)
}
