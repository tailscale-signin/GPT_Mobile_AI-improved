package com.example.gptmobileai.debug

import com.example.gptmobileai.data.model.CircuitBreakerState
import com.example.gptmobileai.data.model.NetworkDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NetworkDiagnosticsProvider(
    private val eventBus: TelemetryCollector,
    private val config: AetherionMaxConfig = AetherionMaxConfig
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .build()

    suspend fun checkEndpoint(url: String): NetworkDiagnostics {
        return withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()

            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()

                val diag = NetworkDiagnostics(
                    endpoint = url,
                    statusCode = response.code,
                    latencyMs = (System.currentTimeMillis() - start).toDouble(),
                    retryCount = 0,
                    errorType = null,
                    circuitBreakerState = CircuitBreakerState.CLOSED
                )
                eventBus.emit(
                    TelemetryEvent.NetworkHealth(
                        endpoint = url,
                        statusCode = diag.statusCode,
                        latencyMs = diag.latencyMs,
                        errorType = null
                    )
                )
                diag
            } catch (e: Exception) {
                val diag = NetworkDiagnostics(
                    endpoint = url,
                    statusCode = 0,
                    latencyMs = (System.currentTimeMillis() - start).toDouble(),
                    retryCount = 0,
                    errorType = e::class.simpleName,
                    circuitBreakerState = CircuitBreakerState.OPEN
                )
                eventBus.emit(
                    TelemetryEvent.NetworkHealth(
                        endpoint = url,
                        statusCode = 0,
                        latencyMs = diag.latencyMs,
                        errorType = diag.errorType
                    )
                )
                diag
            }
        }
    }

    suspend fun healthCheck(): List<NetworkDiagnostics> {
        val endpoints = listOf(
            "https://api.anthropic.com/v1/models",
            "https://api.openai.com/v1/models"
        )

        return endpoints.map { checkEndpoint(it) }
    }
}
