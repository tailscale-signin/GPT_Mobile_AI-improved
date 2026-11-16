package dev.chungjungsoo.gptmobile.data.network

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
    val checkpointContinuationAllowed: Boolean = false,
    /** SSE error semantics can differ from the actual HTTP response status. */
    val observedHttpStatus: Int? = null,
    val serverRequestId: String? = null,
    /** Opaque rate-limit scope hash; no credential or raw model ID. */
    val cooldownScope: String? = null,
    val limitSource: String? = null
) : java.io.IOException("HTTP $status: transient provider failure") {
    val retryAtMillis: Long? = retryAfterMs?.let { ProviderRetryPolicy.deadlineMillis(observedAtMillis, it) }
}

/** A credential's cooldown is shared across profiles and benchmark requests; credentials are never logged. */
internal object ProviderCooldown {
    private val registry = ProviderThrottleRegistry()
    val revision = registry.revision
    private fun key(config: ProviderRequestConfig, limitSource: String? = null): String =
        OpenRouterRateLimitScopes.identity(config, limitSource)

    private fun keys(config: ProviderRequestConfig): List<String> =
        OpenRouterRateLimitScopes.admissionIdentities(config)

    fun scope(config: ProviderRequestConfig, limitSource: String? = null): String =
        "provider:" + key(config, limitSource)

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
        checkpointContinuationAllowed: Boolean = false,
        observedHttpStatus: Int? = null,
        serverRequestId: String? = null,
        limitSource: String? = null
    ): ProviderHttpFailure {
        val normalizedSource = OpenRouterRateLimitScopes.validLimitSource(limitSource)
        val identity = key(config, normalizedSource)
        if (status != 429 || !durableRateLimits()) {
            val wait = ProviderRetryPolicy.retryAfterMillis(retryAfter.orEmpty(), System.currentTimeMillis()) ?: 5_000L
            registry.defer(identity, wait)
            return ProviderHttpFailure(
                status, wait, knownNotAccepted,
                observedHttpStatus = observedHttpStatus,
                serverRequestId = safeProviderRequestIdentity(serverRequestId),
                cooldownScope = "provider:" + identity, limitSource = normalizedSource
            )
        }
        val now = System.currentTimeMillis()
        val retryDelay = retryAfter?.let { ProviderRetryPolicy.retryAfterMillis(it, now) }
        val resetDelay = rateReset?.let { ProviderRetryPolicy.resetDelayMillis(it, now) }
        val hint = listOfNotNull(retryDelay, resetDelay).maxOrNull()
        val (wait, count) = registry.reject(identity, hint)
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
            checkpointContinuationAllowed = checkpointContinuationAllowed,
            observedHttpStatus = observedHttpStatus,
            serverRequestId = safeProviderRequestIdentity(serverRequestId),
            cooldownScope = "provider:" + identity, limitSource = normalizedSource
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

    /** Read-only admission before prompt preparation. Never reserve or rotate a credential here. */
    fun preflight(profile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2): ProviderLimitDecision? {
        if (profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.FREE ||
            dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(profile.apiUrl) != null
        ) return null
        val candidates = ApiCredentialRotator.parseKeys(profile.token).ifEmpty { listOf("") }
        val now = System.currentTimeMillis()
        val waiting = candidates.map { credential ->
            val config = ProviderRequestConfig(
                profile.apiUrl, credential.takeIf(String::isNotBlank),
                providerType = profile.compatibleType.name, selectedModel = profile.model
            )
            // All applicable quota scopes are read-only until dispatch.
            val active = keys(config).mapNotNull { identity ->
                registry.cooldown(identity)?.let { identity to it }
            }.maxByOrNull { it.second.waitMillis } ?: return null
            val (identity, cooldown) = active
            ProviderLimitDecision(
                profile.compatibleType.name, profile.uid, profile.model,
                ProviderRetryPolicy.deadlineMillis(now, cooldown.waitMillis), "provider:" + identity,
                FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE.name, knownNotAccepted = true,
                retrySource = if (cooldown.rejectionCount == 0 && cooldown.authoritativeReset) "RATE_LIMIT_RESET" else "SHARED_COOLDOWN",
                consecutiveRejections = cooldown.rejectionCount
            )
        }
        return waiting.minByOrNull { it.retryAt }
    }

    suspend fun <T> withPermit(config: ProviderRequestConfig, block: suspend () -> T): T {
        if (!durableRateLimits() || config.freeProvider != null) {
            await(config)
            return block()
        }
        val admissions = keys(config).map { it to registry.admit(it) }
        try {
            val blocked = admissions.filter { it.second.waitMillis > 0 }
                .maxByOrNull { it.second.waitMillis }
            if (blocked != null) {
                throw ProviderHttpFailure(
                    429, blocked.second.waitMillis,
                    knownNotAccepted = true,
                    retrySource = if (blocked.second.rejectionCount == 0) "RATE_LIMIT_RESET" else "SHARED_COOLDOWN",
                    consecutiveRejections = blocked.second.rejectionCount,
                    beforeDispatch = true, cooldownScope = "provider:" + blocked.first
                )
            }
            if (admissions.none { it.second.ownsProbe }) await(config)
            return block()
        } finally {
            admissions.filter { it.second.ownsProbe }.forEach { registry.releaseProbe(it.first) }
        }
    }

    fun accepted(config: ProviderRequestConfig) {
        keys(config).forEach(registry::accepted)
    }

    suspend fun observeQuota(config: ProviderRequestConfig, header: (String) -> String?) {
        if (!durableRateLimits() || config.freeProvider != null) return
        val now = System.currentTimeMillis()
        val reset = quotaHeaders.mapNotNull { (remaining, reset) ->
            if (header(remaining)?.trim()?.toLongOrNull() == 0L) header(reset)?.let { ProviderRetryPolicy.resetDelayMillis(it, now) } else null
        }.maxOrNull() ?: return
        val identity = if (OpenRouterRateLimitScopes.isOpenRouter(config) && OpenRouterRateLimitScopes.isFreeModel(config))
            key(config, "openrouter_free_tier_per_minute") else key(config)
        registry.defer(identity, reset, authoritativeReset = true)
    }

    suspend fun await(config: ProviderRequestConfig) {
        val identities = keys(config)
        if (durableRateLimits() && config.freeProvider == null) {
            val blocked = identities.mapNotNull { identity ->
                val remaining = registry.blockedMillis(identity)
                val count = registry.rejectionCount(identity)
                if (remaining > 0 && count > 0) Triple(identity, remaining, count) else null
            }.maxByOrNull { it.second }
            if (blocked != null) {
                throw ProviderHttpFailure(
                    429, blocked.second,
                    knownNotAccepted = true, retrySource = "SHARED_COOLDOWN",
                    consecutiveRejections = blocked.third, beforeDispatch = true,
                    cooldownScope = "provider:" + blocked.first
                )
            }
        }
        while (true) {
            val remaining = identities.maxOf { registry.remainingMillis(it) }
            if (remaining <= 0) return
            kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]
                ?.transportWait(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.PROVIDER_COOLDOWN, remaining)
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
