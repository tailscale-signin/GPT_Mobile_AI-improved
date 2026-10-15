package dev.chungjungsoo.gptmobile.data.network

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

internal class ProviderHttpFailure(val status: Int, val retryAfterMs: Long?) : java.io.IOException("HTTP $status: transient provider failure")

/** A credential's cooldown is shared across profiles and benchmark requests; credentials are never logged. */
internal object ProviderCooldown {
    private val deadlines = ConcurrentHashMap<String, Long>()
    private fun key(config: ProviderRequestConfig): String = MessageDigest.getInstance("SHA-256")
        .digest((config.apiUrl.trimEnd('/') + "\u0000" + config.token.orEmpty()).encodeToByteArray()).joinToString("") { "%02x".format(it) }

    fun defer(config: ProviderRequestConfig, retryAfter: String?): Long {
        val wait = ProviderRetryPolicy.retryAfterMillis(retryAfter.orEmpty(), System.currentTimeMillis()) ?: 5_000L
        val deadline = System.nanoTime() / 1_000_000 + wait.coerceAtMost(86_400_000L)
        deadlines.merge(key(config), deadline, ::maxOf)
        return wait
    }

    suspend fun await(config: ProviderRequestConfig) {
        val identity = key(config)
        while (true) {
            val deadline = deadlines[identity] ?: return
            val remaining = deadline - System.nanoTime() / 1_000_000
            if (remaining <= 0) {
                deadlines.remove(identity, deadline)
                return
            }
            kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.transportWait(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.PROVIDER_COOLDOWN, remaining)
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Readiness", "REQUIRED_WAIT · reason=PROVIDER_COOLDOWN · remainingMs=$remaining")
            kotlinx.coroutines.currentCoroutineContext()[ProviderWaitContext]?.notifyRemaining?.invoke(remaining)
            delay(remaining)
        }
    }
}
