package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.LocalEndpointConnectionPolicy
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.SseUtils
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

class GatewayAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : GatewayAPI {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Null means a definite unsupported endpoint; transport/auth failures must not remove isolation controls. */
    override suspend fun getCapabilities(config: ProviderRequestConfig, forceRefresh: Boolean): GatewayCapabilities? {
        val surfaceIdentity = GatewaySurfacePolicy.identity(config)
        if (forceRefresh) {
            GatewaySurfacePolicy.shared.invalidate(surfaceIdentity, GatewaySurfacePolicy.Surface.CAPABILITIES)
        } else if (GatewaySurfacePolicy.shared.rememberedStatus(surfaceIdentity, GatewaySurfacePolicy.Surface.CAPABILITIES) != null) {
            return null
        }
        val endpoint = config.buildEndpoint("gateway/capabilities")
        return try {
            val responseBody = LocalEndpointConnectionPolicy.probe(endpoint, networkClient::evictIdleConnections) {
                gatewayClientExchange(config, "capabilities", safeRead = true) { requestHeaders ->
                    networkClient.gatewayReadClient().prepareGet(endpoint) {
                        config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                        requestHeaders.forEach { (key, value) -> header(key, value) }
                        if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                        accept(ContentType.Application.Json)
                        timeout {
                            connectTimeoutMillis = CAPABILITY_CONNECT_TIMEOUT_MS
                            requestTimeoutMillis = CAPABILITY_REQUEST_TIMEOUT_MS
                            socketTimeoutMillis = CAPABILITY_REQUEST_TIMEOUT_MS
                        }
                    }.execute { response ->
                        GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                        GatewaySurfacePolicy.shared.observe(GatewaySurfacePolicy.identity(config), GatewaySurfacePolicy.Surface.CAPABILITIES, response.status.value)
                        when {
                            response.status.value in setOf(404, 501) -> null
                            response.status.isSuccess() -> response.boundedMetadata(256 * 1024)
                            else -> throw GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: The selected connection could not verify gateway controls (HTTP ${response.status.value}). Check the connection and retry; no model request was sent.", httpStatus = response.status.value)
                        }
                    }
                }
            } ?: return null
            decodeCapabilities(responseBody).also {
                if (it.version.isNullOrBlank() && it.gatewayVersion.isNullOrBlank()) {
                    throw GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: The selected connection returned an unverified gateway capability document. No model request was sent.")
                }
                GatewayClientDiagnostics.observeInstance(config, it.instanceId)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: GatewayCapabilityDiscoveryException) {
            throw failure
        } catch (failure: Exception) {
            throw GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: Gateway control discovery did not complete on the selected connection. Check the connection and retry; no model request was sent.", failure)
        }
    }

    private fun decodeCapabilities(body: String): GatewayCapabilities = try {
        json.decodeFromString<GatewayCapabilities>(body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Decoder exception messages can contain literal values and whole input
        // excerpts. Inspect only the known schema; never retain that raw cause.
        val document = try {
            json.parseToJsonElement(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            rejectCapabilities(GatewayCapabilityDecodeIssue("MALFORMED_JSON", "$"))
        }
        val issue = GatewayCapabilityShapeDiagnostic(json).find(document, GatewayCapabilities.serializer().descriptor)
            ?: GatewayCapabilityDecodeIssue("SCHEMA_MISMATCH", "$")
        rejectCapabilities(issue)
    }

    private fun rejectCapabilities(issue: GatewayCapabilityDecodeIssue): Nothing {
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Gateway", "CAPABILITY_DECODE_REJECTED · reason=${issue.reason} · field=${issue.fieldPath}", "W")
        throw GatewayCapabilityDiscoveryException(
            "GATEWAY_CAPABILITY_UNAVAILABLE: The selected connection returned an invalid gateway capability document (reason=${issue.reason}, field=${issue.fieldPath}). Check or update that gateway; no model request was sent.",
            decodeReason = issue.reason,
            fieldPath = issue.fieldPath
        )
    }

    override fun resumeEvents(
        jobId: String,
        afterSequence: Int,
        timeoutSeconds: Int,
        config: ProviderRequestConfig,
        expectedGeneration: String?
    ): Flow<GatewayProgress> = flow {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Gateway job identifier." }
        require(expectedGeneration == null || expectedGeneration.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "Invalid Gateway generation identifier."
        }
        val generationQuery = expectedGeneration?.let { "&generation=$it" }.orEmpty()
        val endpoint = config.buildEndpoint("gateway/jobs/$jobId/events?after_sequence=${afterSequence.coerceAtLeast(0)}$generationQuery")
        var lastSequence = afterSequence.coerceAtLeast(0)
        var activeGeneration: String? = null
        gatewayClientExchange(config, "job", safeRead = true) { requestHeaders ->
            networkClient().prepareGet(endpoint) {
                applyPlatformStreamingTimeout(timeoutSeconds)
                accept(ContentType.Text.EventStream)
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                requestHeaders.forEach { (key, value) -> header(key, value) }
            }.execute { response ->
                GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                if (!response.status.isSuccess()) {
                    throw GatewayEventReplayException("Gateway event replay failed with HTTP ${response.status.value}.")
                }

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break
                    val data = SseUtils.extractSseData(line) ?: continue
                    if (data == "[DONE]") break

                    val progress = try {
                        json.decodeFromString<GatewayProgress>(data)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        throw GatewayEventReplayException("Gateway returned an unreadable replay event.", failure)
                    }
                    val generation = progress.generation
                    if (progress.jobId != null && progress.jobId != jobId) {
                        throw GatewayEventReplayException("Gateway replay belongs to a different job; retained output was not replaced.")
                    }
                    if (activeGeneration == null && expectedGeneration != null && generation != expectedGeneration && progress.generationChanged != true) {
                        throw GatewayEventReplayException("Gateway replay did not confirm the saved generation or declare a generation change.")
                    }
                    if (generation != null && activeGeneration != null && generation != activeGeneration) {
                        throw GatewayEventReplayException("Gateway event generation changed during replay.")
                    }
                    if (generation != null) activeGeneration = generation
                    val sequence = progress.sequence
                    if (sequence != null && sequence < 0) throw GatewayEventReplayException("Gateway returned an invalid replay cursor.")
                    if (sequence != null && sequence <= lastSequence && progress.generationChanged != true) continue
                    if (sequence != null) lastSequence = if (progress.generationChanged == true) sequence else maxOf(lastSequence, sequence)
                    emit(progress)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getJobResult(
        jobId: String,
        config: ProviderRequestConfig
    ): GatewayJobResult? {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Gateway job identifier." }
        val endpoint = config.buildEndpoint("gateway/jobs/$jobId/result")
        return try {
            LocalEndpointConnectionPolicy.probe(endpoint, networkClient::evictIdleConnections) {
                gatewayClientExchange(config, "job", safeRead = true) { requestHeaders ->
                    networkClient.gatewayReadClient().prepareGet(endpoint) {
                        config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                        requestHeaders.forEach { (key, value) -> header(key, value) }
                        if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                        accept(ContentType.Application.Json)
                        timeout {
                            connectTimeoutMillis = RECOVERY_CONNECT_TIMEOUT_MS
                            requestTimeoutMillis = RECOVERY_REQUEST_TIMEOUT_MS
                            socketTimeoutMillis = RECOVERY_REQUEST_TIMEOUT_MS
                        }
                    }.execute { response ->
                        GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                        when {
                            response.status.isSuccess() -> {
                                val body = response.boundedMetadata(4 * 1024 * 1024)
                                val saved = try {
                                    json.decodeFromString<GatewayJobResult>(body)
                                } catch (failure: kotlinx.serialization.SerializationException) {
                                    // Legacy 202 endpoints can omit their envelope. Keep only
                                    // transport-level waiting; never invent handoff or completion proof.
                                    if (response.status == HttpStatusCode.Accepted) return@execute GatewayJobResult(jobId = jobId, status = "RUNNING")
                                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Gateway", "SAVED_RESULT_READ_OUTCOME · jobId=$jobId · outcome=INVALID_RESPONSE · http=${response.status.value}", "W")
                                    return@execute null
                                }
                                if (saved.jobId != jobId || saved.job?.jobId?.let { it != jobId } == true) {
                                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Gateway", "SAVED_RESULT_READ_OUTCOME · jobId=$jobId · outcome=JOB_IDENTITY_MISMATCH · http=${response.status.value}", "W")
                                    return@execute null
                                }
                                if (response.status == HttpStatusCode.Accepted && saved.status.lowercase() !in setOf("running", "resuming", "waiting_for_client_tool")) {
                                    GatewayJobResult(jobId = jobId, status = "RUNNING")
                                } else {
                                    // A waiting result can contain the original assistant call.
                                    // It is retained evidence and cannot authorize tool execution.
                                    saved
                                }
                            }
                            else -> {
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Gateway", "SAVED_RESULT_READ_OUTCOME · jobId=$jobId · outcome=HTTP_REJECTED · http=${response.status.value}", "W")
                                null
                            }
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Gateway", "SAVED_RESULT_READ_OUTCOME · jobId=$jobId · outcome=READ_FAILED · reason=${failure.javaClass.simpleName}", "W")
            null
        }
    }

    override suspend fun cancelJob(
        jobId: String,
        config: ProviderRequestConfig
    ): GatewayCancelResult? {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Gateway job identifier." }
        val endpoint = config.buildEndpoint("gateway/jobs/$jobId/cancel")
        return try {
            val responseBody = gatewayClientExchange(config, "job", safeRead = false, method = "POST") { requestHeaders ->
                networkClient().preparePost(endpoint) {
                    config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                    requestHeaders.forEach { (key, value) -> header(key, value) }
                    accept(ContentType.Application.Json)
                    timeout {
                        connectTimeoutMillis = RECOVERY_CONNECT_TIMEOUT_MS
                        requestTimeoutMillis = RECOVERY_REQUEST_TIMEOUT_MS
                        socketTimeoutMillis = RECOVERY_REQUEST_TIMEOUT_MS
                    }
                }.execute { response ->
                    GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                    if (response.status.isSuccess()) response.body<String>() else null
                }
            } ?: return null
            json.decodeFromString<GatewayCancelResult>(responseBody)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val CAPABILITY_CONNECT_TIMEOUT_MS = 1_500L
        const val CAPABILITY_REQUEST_TIMEOUT_MS = 2_500L
        const val RECOVERY_CONNECT_TIMEOUT_MS = 5_000L
        const val RECOVERY_REQUEST_TIMEOUT_MS = 10_000L
    }
}

class GatewayEventReplayException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal class GatewayCapabilityDiscoveryException(message: String, cause: Throwable? = null, val decodeReason: String? = null, val fieldPath: String? = null, val code: String = "GATEWAY_CAPABILITY_UNAVAILABLE", val httpStatus: Int? = null) : IllegalStateException(message, cause) {
    init {
        require(code in setOf("GATEWAY_CAPABILITY_UNAVAILABLE", "GATEWAY_ISOLATION_UNAVAILABLE"))
        require(httpStatus == null || httpStatus in 100..599)
    }
}

private data class GatewayCapabilityDecodeIssue(val reason: String, val fieldPath: String)

/** Diagnostic only: the real serializer decides acceptance before this bounded inspection runs. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private class GatewayCapabilityShapeDiagnostic(private val json: Json) {
    private var remaining = 256

    fun find(value: JsonElement, descriptor: kotlinx.serialization.descriptors.SerialDescriptor, path: String = "$", depth: Int = 0): GatewayCapabilityDecodeIssue? {
        if (remaining-- <= 0 || depth >= 12 || path.length > 160) return null
        if (value == JsonNull) return if (descriptor.isNullable) null else GatewayCapabilityDecodeIssue("NULL_NOT_ALLOWED", path)
        // JsonElement explicitly accepts arbitrary metadata, including nested values.
        if (descriptor.serialName == "kotlinx.serialization.json.JsonElement") return null
        return when (descriptor.kind) {
            kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN -> if ((value as? JsonPrimitive)?.booleanOrNull == null) GatewayCapabilityDecodeIssue("EXPECTED_BOOLEAN", path) else null
            kotlinx.serialization.descriptors.PrimitiveKind.INT -> if ((value as? JsonPrimitive)?.intOrNull == null) GatewayCapabilityDecodeIssue("EXPECTED_INTEGER", path) else null
            kotlinx.serialization.descriptors.PrimitiveKind.STRING -> if (value !is JsonPrimitive || !value.isString && !json.configuration.isLenient) GatewayCapabilityDecodeIssue("EXPECTED_STRING", path) else null
            kotlinx.serialization.descriptors.StructureKind.CLASS -> {
                val objectValue = value as? JsonObject ?: return GatewayCapabilityDecodeIssue("EXPECTED_OBJECT", path)
                for (index in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(index)
                    val childPath = "$path.$name"
                    val child = objectValue[name]
                    if (child == null) {
                        if (!descriptor.isElementOptional(index)) return GatewayCapabilityDecodeIssue("MISSING_REQUIRED_FIELD", childPath)
                    } else if (!(child == JsonNull && json.configuration.coerceInputValues && descriptor.isElementOptional(index))) {
                        find(child, descriptor.getElementDescriptor(index), childPath, depth + 1)?.let { return it }
                    }
                }
                null
            }
            kotlinx.serialization.descriptors.StructureKind.LIST -> {
                val array = value as? JsonArray ?: return GatewayCapabilityDecodeIssue("EXPECTED_ARRAY", path)
                for ((index, child) in array.withIndex()) {
                    if (remaining <= 0) break
                    find(child, descriptor.getElementDescriptor(0), "$path[$index]", depth + 1)?.let { return it }
                }
                null
            }
            kotlinx.serialization.descriptors.StructureKind.MAP -> {
                val objectValue = value as? JsonObject ?: return GatewayCapabilityDecodeIssue("EXPECTED_OBJECT", path)
                val childDescriptor = descriptor.getElementDescriptor(1)
                if (childDescriptor.serialName != "kotlinx.serialization.json.JsonElement") {
                    for (child in objectValue.values) {
                        if (remaining <= 0) break
                        // Map keys are server-controlled payload, not schema field names.
                        find(child, childDescriptor, "$path[*]", depth + 1)?.let { return it }
                    }
                }
                null
            }
            else -> null
        }
    }
}
