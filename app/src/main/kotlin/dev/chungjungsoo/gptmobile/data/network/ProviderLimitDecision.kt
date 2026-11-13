package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.serialization.Serializable

/** Typed scheduling evidence, never reconstructed from the user-facing failure message. */
@Serializable
data class ProviderLimitDecision(
    val provider: String,
    val profileUid: String,
    val model: String,
    val retryAt: Long,
    val scope: String,
    val category: String,
    val knownNotAccepted: Boolean,
    val failureId: String? = null,
    val authority: dev.chungjungsoo.gptmobile.data.context.CapturedProviderAuthority? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val continuationPrefixDigest: String? = null,
    val httpStatus: Int? = null,
    val retrySource: String? = null,
    val consecutiveRejections: Int = 0,
    val checkpointContinuationAllowed: Boolean = false,
    val gitHubCheckpoint: GitHubProviderCheckpoint? = null
) {
    val mayWait: Boolean get() = knownNotAccepted || checkpointContinuationAllowed && gitHubCheckpoint != null

    init {
        require(retryAt > 0 && scope.isNotBlank() && provider.isNotBlank())
        require(continuationPrefixDigest == null || continuationPrefixDigest.matches(Regex("[a-f0-9]{64}")))
    }
}

internal suspend fun ProviderLimitDecision.bindAuthority(guard: dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard): ProviderLimitDecision {
    if (!knownNotAccepted && !checkpointContinuationAllowed) return this
    guard.validate()
    val captured = requireNotNull(guard.authority) { "PROVIDER_WAIT_AUTHORITY_UNAVAILABLE" }
    require(profileUid == captured.profileUid && model == captured.model) { "PROVIDER_WAIT_ROUTE_CHANGED" }
    return copy(authority = captured)
}

internal fun providerLimitDecision(profile: PlatformV2, outcome: FreePreflightOutcome): ProviderLimitDecision? {
    if (outcome.category !in setOf(FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE, FreeFailureCategory.LOCAL_ALLOWANCE_EXHAUSTED, FreeFailureCategory.PROVIDER_RATE_LIMITED)) return null
    val provider = FreeAiProvider.fromApiUrl(profile.apiUrl) ?: return null
    val retryAt = outcome.retryAt ?: return null
    val identity = freeCredentialIdentity(provider, profile.token)
    return ProviderLimitDecision(
        provider.name,
        profile.uid,
        profile.model,
        retryAt,
        FreeAiRequestLimiter.shared.activeQuotaScopeKey(provider, profile.model, identity),
        outcome.category.name,
        true,
        outcome.cooldown?.originatingFailureId,
        httpStatus = outcome.cooldown?.status
    )
}

internal fun providerPreflightLimit(profile: PlatformV2): ProviderLimitDecision? =
    FreeProviderPreflight.check(profile)?.let { providerLimitDecision(profile, it) } ?: ProviderCooldown.preflight(profile)

internal fun providerLimitDecision(config: ProviderRequestConfig, model: String, receivedPayload: Boolean, error: Exception): ProviderLimitDecision? {
    val prefixDigest = config.continuationPrefixDigest?.takeIf { !receivedPayload && config.requestRole.contains("continuation") }
    (error as? FreeProviderException)?.providerLimit?.let {
        return if (receivedPayload) it.copy(knownNotAccepted = false, continuationPrefixDigest = null) else it.copy(continuationPrefixDigest = prefixDigest.takeIf { _ -> it.knownNotAccepted })
    }
    val provider = config.freeProvider ?: return providerHttpLimitDecision(config, model, receivedPayload, error, prefixDigest)
    val category = config.freeDiagnostics?.category ?: freeFailureCategory(error)
    if (category !in setOf(FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE, FreeFailureCategory.LOCAL_ALLOWANCE_EXHAUSTED, FreeFailureCategory.PROVIDER_RATE_LIMITED)) return null
    val identity = freeCredentialIdentity(provider, config.token)
    val retryAt = (error as? FreeAiRateLimitException)?.retryAt
        ?: runCatching { FreeAiRequestLimiter.shared.cooldownUntilMillis(provider, model, identity) }.getOrNull()
        ?: return null
    val cooldown = config.freeDiagnostics?.cooldown
    val localRejection = error is FreeAiRateLimitException && error.localBlock || category in setOf(FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE, FreeFailureCategory.LOCAL_ALLOWANCE_EXHAUSTED)
    val rejectedHttp = cooldown?.status in setOf(429, 402)
    return ProviderLimitDecision(
        provider.name,
        config.profileUid.orEmpty(),
        model,
        retryAt,
        FreeAiRequestLimiter.shared.activeQuotaScopeKey(provider, model, identity),
        category.name,
        !receivedPayload && config.freeDiagnostics?.jobId == null && (localRejection || rejectedHttp),
        cooldown?.originatingFailureId,
        continuationPrefixDigest = prefixDigest.takeIf { localRejection || rejectedHttp },
        httpStatus = cooldown?.status
    )
}

/** Only an admission failure with no previous generation dispatch can use this wait. */
internal fun providerConnectivityWait(
    config: ProviderRequestConfig,
    model: String,
    failure: Exception,
    requestDispatched: Boolean,
    receivedPayload: Boolean,
    now: Long = System.currentTimeMillis()
): ProviderLimitDecision? {
    if (requestDispatched || receivedPayload || config.profileUid.isNullOrBlank() || model.isBlank()) return null
    if (config.lockedModel != null && config.lockedModel != model || config.continuationPrefixDigest != null) return null
    val endpoint = runCatching { java.net.URI(config.apiUrl) }.getOrNull() ?: return null
    if (endpoint.host.isNullOrBlank() || endpoint.scheme !in setOf("http", "https")) return null
    val pending = failure as? ProviderConnectivityPendingException
    val dns = pending?.dnsRecovery ?: generateSequence<Throwable>(failure) { it.cause }.take(8)
        .any { it is java.net.UnknownHostException || it is java.nio.channels.UnresolvedAddressException }
    if (pending == null && !dns) return null
    return ProviderLimitDecision(
        provider = config.providerType ?: "OPENAI_COMPATIBLE",
        profileUid = requireNotNull(config.profileUid),
        model = model,
        retryAt = ProviderRetryPolicy.deadlineMillis(now, pending?.retryAfterMs?.coerceIn(5_000, 120_000) ?: 5_000),
        scope = ProviderCooldown.scope(config),
        category = if (dns) NetworkFailureKind.DNS.code else NetworkFailureKind.DISCONNECTED.code,
        knownNotAccepted = true,
        retrySource = "CONNECTIVITY_BACKOFF"
    )
}

/** A durable wait follows an observed rejection, never a message containing "429". */
private fun providerHttpLimitDecision(config: ProviderRequestConfig, model: String, receivedPayload: Boolean, error: Exception, prefixDigest: String?): ProviderLimitDecision? {
    val failure = error as? ProviderHttpFailure ?: return null
    if (failure.status != 429 || !(failure.knownNotAccepted || failure.checkpointContinuationAllowed) || config.profileUid.isNullOrBlank() || model.isBlank()) return null
    if (config.lockedModel != null && config.lockedModel != model) return null
    val retryAt = failure.retryAtMillis ?: return null
    return ProviderLimitDecision(
        provider = config.providerType?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) } ?: "OPENAI_COMPATIBLE",
        profileUid = requireNotNull(config.profileUid),
        model = model,
        retryAt = retryAt,
        scope = ProviderCooldown.scope(config),
        category = FreeFailureCategory.PROVIDER_RATE_LIMITED.name,
        knownNotAccepted = failure.knownNotAccepted && !receivedPayload,
        continuationPrefixDigest = prefixDigest.takeUnless { receivedPayload },
        httpStatus = failure.status.takeUnless { failure.beforeDispatch },
        retrySource = failure.retrySource,
        consecutiveRejections = failure.consecutiveRejections,
        checkpointContinuationAllowed = failure.checkpointContinuationAllowed
    )
}
