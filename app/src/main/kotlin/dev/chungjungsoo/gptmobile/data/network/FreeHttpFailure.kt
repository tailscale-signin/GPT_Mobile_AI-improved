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
    // Pollinations Classic uses 402 + Retry-After for an exhausted anonymous slot.
    // It is a cooldown, never permission to visit its separately billed API.
    val classicSlotCooldown = provider == dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.POLLINATIONS && status == 402 && !header("Retry-After").isNullOrBlank()
    val rateLimited = status == 429 || classicSlotCooldown
    config.freeDiagnostics?.category = when {
        status in setOf(401, 403) && provider.acceptsUserToken -> if (config.token.isNullOrBlank()) FreeFailureCategory.AUTHENTICATION_REQUIRED else FreeFailureCategory.AUTHENTICATION_REJECTED
        rateLimited -> FreeFailureCategory.PROVIDER_RATE_LIMITED
        status in setOf(400, 413, 415, 422) -> FreeFailureCategory.REQUEST_INVALID
        else -> FreeFailureCategory.HOSTED_GENERATION_FAILED
    }
    if (!rateLimited) return null
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
            category = if (classicSlotCooldown) dev.chungjungsoo.gptmobile.data.network.error.ProviderFailureKind.RATE_LIMIT.name else failure.kind.name,
            message = message,
            quotaScope = field("quota_scope") ?: field("scope") ?: header("X-RateLimit-Scope")?.takeIf { it.matches(Regex("[A-Za-z0-9_.:/ -]{1,80}")) } ?: "unknown",
            originatingFailureId = config.freeDiagnostics?.failureId
        )
    )
    val cooldown = FreeAiRequestLimiter.shared.savedCooldown(provider, model, identity)
    config.freeDiagnostics?.cooldown = cooldown
    return cooldown?.retryAt
}
