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
    val httpStatus: Int? = null
) {
    init {
        require(retryAt > 0 && scope.isNotBlank() && provider.isNotBlank())
        require(continuationPrefixDigest == null || continuationPrefixDigest.matches(Regex("[a-f0-9]{64}")))
    }
}

internal suspend fun ProviderLimitDecision.bindAuthority(guard: dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard): ProviderLimitDecision {
    if (!knownNotAccepted) return this
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

internal fun providerLimitDecision(config: ProviderRequestConfig, model: String, receivedPayload: Boolean, error: Exception): ProviderLimitDecision? {
    val prefixDigest = config.continuationPrefixDigest?.takeIf { !receivedPayload && config.requestRole.contains("continuation") }
    (error as? FreeProviderException)?.providerLimit?.let {
        return if (receivedPayload) it.copy(knownNotAccepted = false, continuationPrefixDigest = null) else it.copy(continuationPrefixDigest = prefixDigest.takeIf { _ -> it.knownNotAccepted })
    }
    val provider = config.freeProvider ?: return null
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
