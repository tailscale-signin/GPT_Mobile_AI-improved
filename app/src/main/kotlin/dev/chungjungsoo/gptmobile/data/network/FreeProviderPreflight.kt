package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider

enum class FreeFailureCategory(val action: String) {
    LOCAL_COOLDOWN_ACTIVE("Choose another provider or wait until the next eligible retry"),
    PROVIDER_RATE_LIMITED("Wait until the next eligible retry or choose another provider"),
    AUTHENTICATION_REQUIRED("Add API token"),
    AUTHENTICATION_REJECTED("Check API token"),
    PROVIDER_CLIENT_RESTRICTED("Choose another provider"),
    PROVIDER_APPROVAL_REQUIRED("Choose another provider"),
    REQUEST_SCHEMA_INCOMPATIBLE("Update the provider adapter"),
    HOSTED_GENERATION_FAILED("Choose another provider or retry later"),
    PROVIDER_UNAVAILABLE("Choose another provider"),
    REQUEST_INVALID("Review the request settings"),
    QUEUE_JOB_PENDING("Check the saved job or choose another provider"),
    REASONING_BUDGET_EXHAUSTED("Choose a writer with verified reasoning controls or change the output settings"),
    NO_VISIBLE_ANSWER("Retry with different model settings or choose another provider"),
    DNS_RESOLUTION_FAILED("Check the current network, DNS or VPN and retry"),
    NETWORK_ERROR("Check your connection and retry")
}

open class FreeProviderException(val category: FreeFailureCategory, message: String) : IllegalStateException(message)

data class FreePreflightOutcome(val category: FreeFailureCategory, val message: String, val retryAt: Long? = null, val cooldown: FreeCooldown? = null)

object FreeProviderPreflight {
    fun check(provider: FreeAiProvider, model: String, token: String?, hasStoredToken: Boolean = false): FreePreflightOutcome? {
        provider.unavailableReason?.let {
            return FreePreflightOutcome(if (provider == FreeAiProvider.OPENCODE) FreeFailureCategory.PROVIDER_CLIENT_RESTRICTED else FreeFailureCategory.PROVIDER_APPROVAL_REQUIRED, it)
        }
        val cooldown = FreeAiRequestLimiter.shared.savedCooldown(provider, model, freeCredentialIdentity(provider, token))
        if (cooldown != null) return FreePreflightOutcome(FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE, cooldown.blockedMessage(provider), cooldown.retryAt, cooldown)
        if (provider.requiresUserToken && token.isNullOrBlank() && !hasStoredToken) return FreePreflightOutcome(FreeFailureCategory.AUTHENTICATION_REQUIRED, "${provider.displayName}: Add API token in this AI profile’s settings.")
        val localDeadline = FreeAiRequestLimiter.shared.cooldownUntilMillis(provider, model, freeCredentialIdentity(provider, token))
        if (localDeadline != null && localDeadline - System.currentTimeMillis() > 60_000) return FreePreflightOutcome(FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE, "${provider.displayName}: local request allowance is temporarily exhausted. Next eligible retry: ${java.time.Instant.ofEpochMilli(localDeadline)}. Choose another provider.", localDeadline)
        FreeCatalogState.generationUnavailableReason(provider, model)?.let {
            return FreePreflightOutcome(FreeFailureCategory.PROVIDER_UNAVAILABLE, it, FreeCatalogState.status.value[provider]?.retryAt)
        }
        return null
    }

    fun check(profile: PlatformV2, model: String = profile.model): FreePreflightOutcome? {
        if (profile.compatibleType != ClientType.FREE) return null
        val provider = FreeAiProvider.fromApiUrl(profile.apiUrl) ?: return FreePreflightOutcome(FreeFailureCategory.REQUEST_INVALID, "Choose a supported Free provider.")
        return check(provider, model, profile.token, !profile.secretRef.isNullOrBlank())
    }

    internal fun require(config: ProviderRequestConfig, model: String) {
        val provider = config.freeProvider ?: return
        check(provider, model, config.token)?.let { outcome ->
            config.freeDiagnostics?.apply {
                category = outcome.category
                cooldown = outcome.cooldown
            }
            throw FreeProviderException(outcome.category, outcome.message)
        }
    }
}

/** A non-reversible identity only; never retain the credential itself in health or diagnostics. */
internal fun freeCredentialIdentity(provider: FreeAiProvider, token: String?): String =
    if (!provider.requiresUserToken) {
        "anonymous"
    } else {
        token?.takeIf { it.isNotBlank() }?.let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        } ?: "unknown"
    }

internal fun freeFailureCategory(error: Exception): FreeFailureCategory = when (error) {
    is FreeProviderException -> error.category
    is FreeAiRateLimitException -> FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE
    is QueueTerminalFailure -> if (error.remoteTerminal == true) FreeFailureCategory.HOSTED_GENERATION_FAILED else FreeFailureCategory.NETWORK_ERROR
    else -> {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        when {
            causes.any { it is java.net.UnknownHostException || it is java.nio.channels.UnresolvedAddressException } -> FreeFailureCategory.DNS_RESOLUTION_FAILED
            causes.any { it is java.io.IOException || it is io.ktor.client.plugins.HttpRequestTimeoutException } -> FreeFailureCategory.NETWORK_ERROR
            else -> FreeFailureCategory.REQUEST_INVALID
        }
    }
}
