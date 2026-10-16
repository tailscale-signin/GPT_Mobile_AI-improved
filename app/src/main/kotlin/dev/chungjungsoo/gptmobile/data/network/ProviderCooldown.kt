package dev.chungjungsoo.gptmobile.data.network

import java.security.MessageDigest
import kotlinx.coroutines.delay

internal class ProviderHttpFailure(val status: Int, val retryAfterMs: Long?) : java.io.IOException("HTTP $status: transient provider failure")

/** A credential's cooldown is shared across profiles and benchmark requests; credentials are never logged. */
internal object ProviderCooldown {
    private val registry = ProviderThrottleRegistry()
    private fun key(config: ProviderRequestConfig): String = MessageDigest.getInstance("SHA-256")
        .digest((config.apiUrl.trimEnd('/') + "\u0000" + config.token.orEmpty()).encodeToByteArray()).joinToString("") { "%02x".format(it) }

    fun defer(config: ProviderRequestConfig, retryAfter: String?): Long {
        val wait = ProviderRetryPolicy.retryAfterMillis(retryAfter.orEmpty(), System.currentTimeMillis()) ?: 5_000L
        registry.defer(key(config), wait)
        return wait
    }

    suspend fun await(config: ProviderRequestConfig) {
        val identity = key(config)
        while (true) {
            val remaining = registry.remainingMillis(identity)
            if (remaining <= 0) return
            kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.transportWait(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.PROVIDER_COOLDOWN, remaining)
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Readiness", "REQUIRED_WAIT · reason=PROVIDER_COOLDOWN · remainingMs=$remaining")
            kotlinx.coroutines.currentCoroutineContext()[ProviderWaitContext]?.notifyRemaining?.invoke(remaining)
            delay(remaining)
        }
    }
}
