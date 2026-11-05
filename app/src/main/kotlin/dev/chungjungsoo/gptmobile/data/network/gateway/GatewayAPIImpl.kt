package dev.chungjungsoo.gptmobile.data.network.gateway

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

class GatewayAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : GatewayAPI {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Null means a definite unsupported endpoint; transport/auth failures must not remove isolation controls. */
    override suspend fun getCapabilities(config: ProviderRequestConfig): GatewayCapabilities? {
        val endpoint = config.buildEndpoint("gateway/capabilities")
        return try {
            val responseBody = networkClient().prepareGet(endpoint) {
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                GatewayClientDiagnostics.requestHeaders(config).forEach { (key, value) -> header(key, value) }
                accept(ContentType.Application.Json)
                timeout {
                    connectTimeoutMillis = CAPABILITY_CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = CAPABILITY_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = CAPABILITY_REQUEST_TIMEOUT_MS
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(config, response.status.value) { response.headers[it] }
                when {
                    response.status.value in setOf(404, 501) -> null
                    response.status.isSuccess() -> response.boundedMetadata(256 * 1024)
                    else -> throw GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: The selected connection could not verify gateway controls (HTTP ${response.status.value}). Check the connection and retry; no model request was sent.")
                }
            } ?: return null
            json.decodeFromString<GatewayCapabilities>(responseBody).also {
                if (it.version.isNullOrBlank() && it.gatewayVersion.isNullOrBlank()) {
                    throw GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: The selected connection returned an unverified gateway capability document. No model request was sent.")
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: GatewayCapabilityDiscoveryException) {
            throw failure
        } catch (failure: Exception) {
            throw GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: Gateway control discovery did not complete on the selected connection. Check the connection and retry; no model request was sent.", failure)
        }
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
        networkClient().prepareGet(endpoint) {
            applyPlatformStreamingTimeout(timeoutSeconds)
            accept(ContentType.Text.EventStream)
            config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
            GatewayClientDiagnostics.requestHeaders(config).forEach { (key, value) -> header(key, value) }
        }.execute { response ->
            GatewayClientDiagnostics.observe(config, response.status.value) { response.headers[it] }
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
    }.flowOn(Dispatchers.IO)

    override suspend fun getJobResult(
        jobId: String,
        config: ProviderRequestConfig
    ): GatewayJobResult? {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Gateway job identifier." }
        val endpoint = config.buildEndpoint("gateway/jobs/$jobId/result")
        return try {
            networkClient().prepareGet(endpoint) {
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                GatewayClientDiagnostics.requestHeaders(config).forEach { (key, value) -> header(key, value) }
                accept(ContentType.Application.Json)
                timeout {
                    connectTimeoutMillis = RECOVERY_CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = RECOVERY_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = RECOVERY_REQUEST_TIMEOUT_MS
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(config, response.status.value) { response.headers[it] }
                when {
                    response.status == HttpStatusCode.Accepted -> GatewayJobResult(jobId = jobId, status = "RUNNING")
                    response.status.isSuccess() -> {
                        val body = response.boundedMetadata(4 * 1024 * 1024)
                        json.decodeFromString<GatewayJobResult>(body).takeIf { it.jobId == jobId }
                    }
                    else -> null
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
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
            val responseBody = networkClient().preparePost(endpoint) {
                config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                GatewayClientDiagnostics.requestHeaders(config).forEach { (key, value) -> header(key, value) }
                accept(ContentType.Application.Json)
                timeout {
                    connectTimeoutMillis = RECOVERY_CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = RECOVERY_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = RECOVERY_REQUEST_TIMEOUT_MS
                }
            }.execute { response ->
                GatewayClientDiagnostics.observe(config, response.status.value) { response.headers[it] }
                if (response.status.isSuccess()) response.body<String>() else null
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

internal class GatewayCapabilityDiscoveryException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
