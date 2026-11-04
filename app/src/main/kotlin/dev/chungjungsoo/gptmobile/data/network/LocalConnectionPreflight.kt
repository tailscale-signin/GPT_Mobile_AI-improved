package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class LocalServiceUnavailableException(
    val health: ConnectionHealth,
    val endpointAddress: String,
    val retryAfterMillis: Long,
    val probeAttempts: Int = 0,
    cause: Throwable? = null
) : IOException(
    "LOCAL_SERVICE_UNREACHABLE: $health; endpoint=$endpointAddress; retryAfterMs=$retryAfterMillis; " +
        "probeAttempts=$probeAttempts; TCP connectivity unavailable; HTTP/model health unverified; configured endpoint preserved",
    cause
) {
    fun afterAttempts(attempts: Int) = LocalServiceUnavailableException(health, endpointAddress, retryAfterMillis, attempts, this)

    fun failureDetails() = buildJsonObject {
        put("failed_stage", "connection_preflight")
        put("connection_health", health.name)
        put("probe_attempts", probeAttempts)
        put("retry_after_ms", retryAfterMillis)
        put("inference_dispatched", false)
        put("model_ready", JsonNull)
    }
}

/** Retries read-only TCP checks before inference can create a gateway job or mutate remote state. */
internal object LocalConnectionPreflight {
    suspend fun requireReady(url: String) {
        if (endpointLocality(url) == EndpointLocality.EXTERNAL) return
        val workerBudget = currentCoroutineContext()[WorkerRequestBudget]
        recover(
            probe = {
                workerBudget?.preflight()
                LocalServiceHealth.probe(url)
            },
            remainingMillis = { workerBudget?.remainingMillis() ?: Long.MAX_VALUE },
            onRetry = { attempts, waitMillis, health ->
                AppLogRecorder.record("LocalConnection", "PREFLIGHT_RETRY · attempts=$attempts · health=$health · cooldownMs=$waitMillis · inferenceDispatched=false · alternateRoute=false", "W")
            }
        )
    }

    internal suspend fun recover(
        probe: suspend () -> Unit,
        await: suspend (Long) -> Unit = { delay(it) },
        remainingMillis: () -> Long = { Long.MAX_VALUE },
        onRetry: (Int, Long, ConnectionHealth) -> Unit = { _, _, _ -> },
        maxAttempts: Int = 3,
        maxWaitMillis: Long = 12_000
    ) {
        require(maxAttempts > 0 && maxWaitMillis >= 0)
        var attempts = 0
        var waitedMillis = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            attempts++
            try {
                probe()
                return
            } catch (failure: LocalServiceUnavailableException) {
                val waitMillis = failure.retryAfterMillis.coerceAtLeast(250)
                // Reserve time for a final request preflight; never overrun the worker deadline just to reconnect.
                if (attempts >= maxAttempts || waitMillis > maxWaitMillis - waitedMillis || waitMillis >= remainingMillis().coerceAtLeast(0) - 1_000) {
                    throw failure.afterAttempts(attempts)
                }
                onRetry(attempts, waitMillis, failure.health)
                await(waitMillis)
                waitedMillis += waitMillis
            }
        }
    }
}
