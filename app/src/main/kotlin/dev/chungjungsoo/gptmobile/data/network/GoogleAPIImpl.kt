package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class GoogleAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : GoogleAPI {
    private val requestPreparation = ModelRequestPreparation(networkClient)

    override suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        config: ProviderRequestConfig
    ): UploadedProviderFile {
        val file = File(filePath)
        val apiUrl = config.apiUrl
        val startEndpoint = if (apiUrl.endsWith("/")) "${apiUrl}upload/v1beta/files" else "$apiUrl/upload/v1beta/files"
        val uploadUrl = networkClient().preparePost(startEndpoint) {
            header(GOOGLE_API_KEY_HEADER, config.token ?: "")
            contentType(ContentType.Application.Json)
            header("X-Goog-Upload-Protocol", "resumable")
            header("X-Goog-Upload-Command", "start")
            header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            header("X-Goog-Upload-Header-Content-Type", mimeType)
            setBody(kotlinx.serialization.json.buildJsonObject { put("file", kotlinx.serialization.json.buildJsonObject { put("display_name", kotlinx.serialization.json.JsonPrimitive(fileName)) }) }.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Google file upload failed (HTTP ${response.status.value}).")
            }
            response.headers["x-goog-upload-url"] ?: response.headers["X-Goog-Upload-URL"]
        } ?: throw IllegalStateException("Failed to obtain Google upload URL")

        requireSameUploadOrigin(startEndpoint, uploadUrl)
        val responseBody = networkClient().preparePost(uploadUrl) {
            header("X-Goog-Upload-Offset", "0")
            header("X-Goog-Upload-Command", "upload, finalize")
            header(HttpHeaders.ContentLength, file.length().toString())
            contentType(ContentType.parse(mimeType))
            setBody(io.ktor.client.content.LocalFileContent(file, ContentType.parse(mimeType)))
        }.execute { it.boundedResponseText() }

        val uploadResponse = NetworkClient.json.decodeFromString<GoogleFileUploadResponse>(responseBody)
        return UploadedProviderFile(
            id = uploadResponse.file.uri ?: uploadResponse.file.name ?: "",
            mimeType = uploadResponse.file.mimeType ?: mimeType,
            name = uploadResponse.file.name,
            uri = uploadResponse.file.uri
        )
    }

    override suspend fun isFileAvailable(fileName: String, config: ProviderRequestConfig): Boolean {
        val apiUrl = config.apiUrl
        val endpoint = if (apiUrl.endsWith("/")) "${apiUrl}v1beta/$fileName" else "$apiUrl/v1beta/$fileName"
        return try {
            networkClient().prepareGet(endpoint) {
                header(GOOGLE_API_KEY_HEADER, config.token ?: "")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    false
                } else {
                    val metadata = NetworkClient.json.decodeFromString<GoogleFileMetadata>(response.boundedResponseText())
                    metadata.state == null || metadata.state == "ACTIVE"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override fun streamGenerateContent(
        request: GenerateContentRequest,
        model: String,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<GenerateContentResponse> = flow {
        val workerBudget = kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]
        var receivedPayload = false
        var completed = false
        emitAll(
            flow {
                val apiUrl = config.apiUrl
                val endpoint = if (apiUrl.endsWith("/")) {
                    "${apiUrl}v1beta/models/$model:streamGenerateContent"
                } else {
                    "$apiUrl/v1beta/models/$model:streamGenerateContent"
                }

                ResilientStreamingClient.executeWithRetry(
                    config = ResilientStreamingClient.RetryConfig(
                        maxAttempts = 3,
                        initialDelayMs = 500L,
                        maxDelayMs = 4_000L
                    ),
                    shouldRetry = { !receivedPayload && !completed },
                    onRetry = { attempt, delayMs, reason ->
                        AppLogRecorder.record(
                            "Network",
                            "Gemini stream retry · model=$model · attempt=$attempt · delayMs=$delayMs · reason=${reason.javaClass.simpleName}",
                            "W"
                        )
                    }
                ) {
                    ProviderCooldown.withPermit(config) {
                        val admittedPayload = requestPreparation.prepare(NetworkClient.json.encodeToString(request), config, model, null)
                        networkClient().preparePost(endpoint) {
                            applyPlatformStreamingTimeout(timeoutSeconds, workerBudget)
                            header(GOOGLE_API_KEY_HEADER, config.token ?: "")
                            parameter("alt", "sse")
                            contentType(ContentType.Application.Json)
                            setBody(admittedPayload)
                        }.execute { response ->
                            if (!response.status.isSuccess()) {
                                val errorBody = readProviderErrorBody(response, 256 * 1024)
                                throwIfToolDefinitionsRejected(
                                    response.status.value,
                                    !request.tools.isNullOrEmpty(),
                                    errorBody
                                )
                                if (response.status.value in TRANSIENT_HTTP_STATUSES && !receivedPayload) {
                                    throw ProviderCooldown.rejected(
                                        config,
                                        response.status.value,
                                        response.headers[HttpHeaders.RetryAfter],
                                        knownNotAccepted = response.status.value == 429,
                                        errorOrigin = "provider"
                                    )
                                }

                                val errorMessage = if (response.status.value == 400 && !request.tools.isNullOrEmpty() && "function_declarations" in errorBody && "parameters" in errorBody) {
                                    "HTTP 400: provider rejected the tool parameter schema."
                                } else {
                                    dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure.parse(response.status.value, errorBody).message()
                                }

                                receivedPayload = true
                                emit(
                                    GenerateContentResponse(
                                        error = ErrorDetail(
                                            message = errorMessage,
                                            code = response.status.value,
                                            status = "ERROR"
                                        )
                                    )
                                )
                                return@execute
                            }

                            ProviderCooldown.observeQuota(config) { response.headers[it] }
                            val channel = response.bodyAsChannel()
                            val events = ProviderEventReader(channel)
                            while (!channel.isClosedForRead) {
                                val line = events.readLine() ?: break
                                val data = SseUtils.extractSseData(line) ?: continue

                                val chunk = try {
                                    NetworkClient.json.decodeFromString<GenerateContentResponse>(data)
                                } catch (_: kotlinx.serialization.SerializationException) {
                                    // Only malformed JSON is skipped. Cancellation/collector failures
                                    // must escape instead of being mistaken for invalid server data.
                                    continue
                                }
                                val error = chunk.error
                                if ((error?.code == 429 || error?.status == "RESOURCE_EXHAUSTED") && ProviderCooldown.durableRateLimits()) {
                                    throw ProviderCooldown.rejected(
                                        config,
                                        429,
                                        response.headers[HttpHeaders.RetryAfter],
                                        knownNotAccepted = !receivedPayload,
                                        errorOrigin = "provider",
                                        errorType = error?.status,
                                        checkpointContinuationAllowed = true
                                    )
                                }
                                receivedPayload = true
                                if (chunk.candidates.orEmpty().any { !it.finishReason.isNullOrBlank() }) {
                                    completed = true
                                    if (error == null) ProviderCooldown.accepted(config)
                                }
                                emit(chunk)
                            }
                        }
                    }
                }
            }.catch { e ->
                if (e is CancellationException || e is dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException) throw e
                if (e is ProviderHttpFailure && e.status == 429 && ProviderCooldown.durableRateLimits()) throw e
                if (ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(receivedPayload, e, completed)) {
                    AppLogRecorder.record("Network", "Gemini stream closed after completion · model=$model · cause=${e.javaClass.simpleName}", "W")
                    return@catch
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
                AppLogRecorder.record(
                    "Network",
                    "Gemini stream failed · model=$model · receivedPayload=$receivedPayload · completed=$completed · ${e.javaClass.simpleName}",
                    "E"
                )
                emit(
                    GenerateContentResponse(
                        error = ErrorDetail(
                            message = errorMessage,
                            code = -1,
                            status = "NETWORK_ERROR"
                        )
                    )
                )
            }
        )
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val GOOGLE_API_KEY_HEADER = "x-goog-api-key"
        val TRANSIENT_HTTP_STATUSES = setOf(429, 502, 503, 504)
    }
}

@Serializable
private data class GoogleFileUploadResponse(
    val file: GoogleFileMetadata
)

@Serializable
private data class GoogleFileMetadata(
    val name: String? = null,
    val uri: String? = null,
    @kotlinx.serialization.SerialName("mime_type")
    val mimeType: String? = null,
    val state: String? = null
)

/** A resumable upload URL is supplied by the server and will receive the entire attachment. */
internal fun requireSameUploadOrigin(start: String, destination: String) {
    val origin = start.toHttpUrlOrNull()
    val upload = destination.toHttpUrlOrNull()
    require(
        origin != null &&
            upload != null &&
            upload.scheme == origin.scheme &&
            upload.host == origin.host &&
            upload.port == origin.port &&
            upload.username.isEmpty() &&
            upload.password.isEmpty() &&
            upload.fragment == null
    ) { "The provider returned an invalid file upload destination." }
}
