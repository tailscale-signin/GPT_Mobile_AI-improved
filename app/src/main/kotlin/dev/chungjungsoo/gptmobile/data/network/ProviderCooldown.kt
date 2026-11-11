package dev.chungjungsoo.gptmobile.data.network

import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

internal class ProviderHttpFailure(
    val status: Int,
    val retryAfterMs: Long?,
    /** Set at the response boundary only when no accepted payload or remote job exists. */
    val knownNotAccepted: Boolean = false,
    observedAtMillis: Long = System.currentTimeMillis(),
    val retrySource: String? = null,
    val consecutiveRejections: Int = 0,
    val errorOrigin: String? = null,
    val errorType: String? = null,
    val beforeDispatch: Boolean = false,
    val checkpointContinuationAllowed: Boolean = false
) : java.io.IOException("HTTP $status: transient provider failure") {
    val retryAtMillis: Long? = retryAfterMs?.let { ProviderRetryPolicy.deadlineMillis(observedAtMillis, it) }
}

/** A credential's cooldown is shared across profiles and benchmark requests; credentials are never logged. */
internal object ProviderCooldown {
    private val registry = ProviderThrottleRegistry()
    private fun key(config: ProviderRequestConfig): String = MessageDigest.getInstance("SHA-256")
        .digest((config.apiUrl.trimEnd('/') + "\u0000" + config.token.orEmpty()).encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /** The same credential scope used by interactive retries; no token or URL is persisted. */
    fun scope(config: ProviderRequestConfig): String = "provider:" + key(config)

    suspend fun durableRateLimits(): Boolean = currentCoroutineContext()[ProviderWaitContext]?.durableRateLimits == true

    fun defer(config: ProviderRequestConfig, retryAfter: String?): Long {
        val wait = ProviderRetryPolicy.retryAfterMillis(retryAfter.orEmpty(), System.currentTimeMillis()) ?: 5_000L
        registry.defer(key(config), wait)
        return wait
    }

    suspend fun rejected(
        config: ProviderRequestConfig,
        status: Int,
        retryAfter: String?,
        knownNotAccepted: Boolean = false,
        rateReset: String? = null,
        errorOrigin: String? = null,
        errorType: String? = null,
        checkpointContinuationAllowed: Boolean = false
    ): ProviderHttpFailure {
        if (status != 429 || !durableRateLimits()) {
            return ProviderHttpFailure(status, defer(config, retryAfter), knownNotAccepted)
        }
        val now = System.currentTimeMillis()
        val retryDelay = retryAfter?.let { ProviderRetryPolicy.retryAfterMillis(it, now) }
        val resetDelay = rateReset?.let { ProviderRetryPolicy.resetDelayMillis(it, now) }
        val hint = listOfNotNull(retryDelay, resetDelay).maxOrNull()
        val (wait, count) = registry.reject(key(config), hint)
        return ProviderHttpFailure(
            status, wait, knownNotAccepted, now,
            retrySource = when {
                hint == null -> "APP_BACKOFF"
                hint == retryDelay -> "RETRY_AFTER"
                else -> "RATE_LIMIT_RESET"
            },
            consecutiveRejections = count,
            errorOrigin = errorOrigin?.takeIf { it in setOf("provider", "openrouter", "gateway") },
            errorType = errorType?.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,64}")) },
            checkpointContinuationAllowed = checkpointContinuationAllowed
        )
    }

    fun restore(decision: ProviderLimitDecision) {
        if (!decision.scope.startsWith("provider:") ||
            decision.consecutiveRejections < 1 &&
            decision.retrySource != "RATE_LIMIT_RESET" ||
            decision.retryAt < System.currentTimeMillis() - 1_800_000L
        ) {
            return
        }
        registry.restore(
            decision.scope.removePrefix("provider:"),
            ProviderRetryPolicy.remainingMillis(decision.retryAt, System.currentTimeMillis()),
            decision.consecutiveRejections
        )
    }

    fun blockedUntil(scope: String): Long? {
        if (!scope.startsWith("provider:")) return null
        val remaining = registry.blockedMillis(scope.removePrefix("provider:"))
        return remaining.takeIf { it > 0 }?.let { ProviderRetryPolicy.deadlineMillis(System.currentTimeMillis(), it) }
    }

    suspend fun <T> withPermit(config: ProviderRequestConfig, block: suspend () -> T): T {
        if (!durableRateLimits() || config.freeProvider != null) {
            await(config)
            return block()
        }
        val identity = key(config)
        val admission = registry.admit(identity)
        if (admission.waitMillis > 0) {
            throw ProviderHttpFailure(
                429,
                admission.waitMillis,
                knownNotAccepted = true,
                retrySource = if (admission.rejectionCount == 0) "RATE_LIMIT_RESET" else "SHARED_COOLDOWN",
                consecutiveRejections = admission.rejectionCount,
                beforeDispatch = true
            )
        }
        if (admission.rejectionCount == 0 && !admission.ownsProbe) await(config)
        return try {
            block()
        } finally {
            if (admission.ownsProbe) registry.releaseProbe(identity)
        }
    }

    fun accepted(config: ProviderRequestConfig) = registry.accepted(key(config))

    suspend fun observeQuota(config: ProviderRequestConfig, header: (String) -> String?) {
        if (!durableRateLimits() || config.freeProvider != null) return
        val now = System.currentTimeMillis()
        val reset = quotaHeaders.mapNotNull { (remaining, reset) ->
            if (header(remaining)?.trim()?.toLongOrNull() == 0L) header(reset)?.let { ProviderRetryPolicy.resetDelayMillis(it, now) } else null
        }.maxOrNull() ?: return
        registry.defer(key(config), reset, authoritativeReset = true)
    }

    suspend fun await(config: ProviderRequestConfig) {
        val identity = key(config)
        if (durableRateLimits() && config.freeProvider == null) {
            val remaining = registry.blockedMillis(identity)
            val count = registry.rejectionCount(identity)
            if (remaining > 0 && count > 0) {
                throw ProviderHttpFailure(429, remaining, knownNotAccepted = true, retrySource = "SHARED_COOLDOWN", consecutiveRejections = count, beforeDispatch = true)
            }
        }
        while (true) {
            val remaining = registry.remainingMillis(identity)
            if (remaining <= 0) return
            kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.transportWait(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.PROVIDER_COOLDOWN, remaining)
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Readiness", "REQUIRED_WAIT · reason=PROVIDER_COOLDOWN · remainingMs=$remaining")
            kotlinx.coroutines.currentCoroutineContext()[ProviderWaitContext]?.notifyRemaining?.invoke(remaining)
            delay(remaining)
        }
    }

    private val quotaHeaders = listOf(
        "X-RateLimit-Remaining-Requests" to "X-RateLimit-Reset-Requests",
        "X-RateLimit-Remaining-Tokens" to "X-RateLimit-Reset-Tokens",
        "X-RateLimit-Remaining" to "X-RateLimit-Reset",
        "anthropic-ratelimit-requests-remaining" to "anthropic-ratelimit-requests-reset"
    )
}
