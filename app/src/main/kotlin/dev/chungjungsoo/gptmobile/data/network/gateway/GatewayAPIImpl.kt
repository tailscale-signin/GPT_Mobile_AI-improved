package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.SseUtils
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.call.body
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

    override suspend fun getCapabilities(config: ProviderRequestConfig): GatewayCapabilities? {
        val endpoint = config.buildEndpoint("gateway/capabilities")
        return try {
            val responseBody = networkClient().prepareGet(endpoint) {
                config.token?.let { bearerAuth(it) }
                config.extraHeaders.forEach { (key, value) -> header(key, value) }
                accept(ContentType.Application.Json)
            }.execute { response ->
                if (response.status.isSuccess()) response.body<String>() else null
            } ?: return null
            json.decodeFromString<GatewayCapabilities>(responseBody)
        } catch (_: Exception) {
            null
        }
    }

    override fun resumeEvents(
        jobId: String,
        afterSequence: Int,
        timeoutSeconds: Int,
        config: ProviderRequestConfig
    ): Flow<GatewayProgress> = flow {
        val endpoint = config.buildEndpoint("gateway/jobs/$jobId/events?after_sequence=$afterSequence")
        try {
            networkClient().prepareGet(endpoint) {
                applyPlatformStreamingTimeout(timeoutSeconds)
                accept(ContentType.Text.EventStream)
                config.token?.let { bearerAuth(it) }
                config.extraHeaders.forEach { (key, value) -> header(key, value) }
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break
                    val data = SseUtils.extractSseData(line) ?: continue
                    if (data == "[DONE]") break

                    try {
                        val progress = json.decodeFromString<GatewayProgress>(data)
                        emit(progress)
                    } catch (_: Exception) {
                        // Skip unparseable events
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getJobResult(
        jobId: String,
        config: ProviderRequestConfig
    ): GatewayJobResult? {
        val endpoint = config.buildEndpoint("gateway/jobs/$jobId/result")
        return try {
            networkClient().prepareGet(endpoint) {
                config.token?.let { bearerAuth(it) }
                config.extraHeaders.forEach { (key, value) -> header(key, value) }
                accept(ContentType.Application.Json)
            }.execute { response ->
                when {
                    response.status.isSuccess() -> {
                        val body = response.body<String>()
                        json.decodeFromString<GatewayJobResult>(body)
                    }
                    response.status == HttpStatusCode.Accepted -> {
                        // HTTP 202: Job is still in progress
                        GatewayJobResult(
                            jobId = jobId,
                            status = "RUNNING"
                        )
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun cancelJob(
        jobId: String,
        config: ProviderRequestConfig
    ): GatewayCancelResult? {
        val endpoint = config.buildEndpoint("gateway/jobs/$jobId/cancel")
        return try {
            val responseBody = networkClient().preparePost(endpoint) {
                config.token?.let { bearerAuth(it) }
                config.extraHeaders.forEach { (key, value) -> header(key, value) }
                accept(ContentType.Application.Json)
            }.execute { response ->
                if (response.status.isSuccess()) response.body<String>() else null
            } ?: return null
            json.decodeFromString<GatewayCancelResult>(responseBody)
        } catch (_: Exception) {
            null
        }
    }
}
