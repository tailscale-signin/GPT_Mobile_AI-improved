package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun retainFreeHttpFailure(config: ProviderRequestConfig, model: String, status: Int, body: String, header: (String) -> String?): Long? {
    val provider = requireNotNull(config.freeProvider)
    val parsed = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = parsed?.get("error") as? JsonObject ?: parsed
    fun field(name: String) = (error?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9_.:/ -]{1,80}")) }
    val failure = ProviderFailure.parse(status, body)
    val message = providerErrorDetails(body, failure.message(), config.token).take(600)
    config.freeDiagnostics?.category = when {
        status in setOf(401, 403) && provider.requiresUserToken -> FreeFailureCategory.AUTHENTICATION_REJECTED
        status == 429 -> FreeFailureCategory.PROVIDER_RATE_LIMITED
        status in setOf(400, 413, 415, 422) -> FreeFailureCategory.REQUEST_INVALID
        else -> FreeFailureCategory.HOSTED_GENERATION_FAILED
    }
    if (status != 429) return null
    val identity = freeCredentialIdentity(provider, config.token)
    FreeAiRequestLimiter.shared.defer(
        provider,
        header("Retry-After"),
        model,
        identity,
        FreeCooldown(
            retryAt = 0,
            status = status,
            providerCode = field("code") ?: field("rc"),
            category = failure.kind.name,
            message = message,
            quotaScope = field("quota_scope") ?: field("scope") ?: header("X-RateLimit-Scope")?.takeIf { it.matches(Regex("[A-Za-z0-9_.:/ -]{1,80}")) } ?: "unknown",
            originatingFailureId = config.freeDiagnostics?.failureId
        )
    )
    val cooldown = FreeAiRequestLimiter.shared.savedCooldown(provider, model, identity)
    config.freeDiagnostics?.cooldown = cooldown
    return cooldown?.retryAt
}
