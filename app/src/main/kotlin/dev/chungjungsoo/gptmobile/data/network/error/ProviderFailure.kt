package dev.chungjungsoo.gptmobile.data.network.error

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class ProviderFailureKind { AUTHENTICATION, BUDGET, PERMISSION, FORBIDDEN_UNKNOWN, RATE_LIMIT, UNAVAILABLE, UNKNOWN }

@Serializable
data class ProviderFailure(val status: Int, val kind: ProviderFailureKind, val code: String? = null, val retryAfterMillis: Long? = null) {
    val retryable: Boolean get() = kind in setOf(ProviderFailureKind.RATE_LIMIT, ProviderFailureKind.UNAVAILABLE)
    fun message(): String = when (kind) {
        ProviderFailureKind.AUTHENTICATION -> "HTTP $status: the provider could not authenticate this request. Check this profile's connection."
        ProviderFailureKind.BUDGET -> "HTTP $status: the provider reported a budget or credit restriction. Check this account's allowance."
        ProviderFailureKind.PERMISSION -> "HTTP $status: the provider reported an explicit permission restriction for this route."
        ProviderFailureKind.FORBIDDEN_UNKNOWN -> "HTTP 403: access forbidden. The provider's specific cause is unavailable."
        ProviderFailureKind.RATE_LIMIT -> "HTTP $status: provider rate limit reached. Resume after the provider cooldown."
        ProviderFailureKind.UNAVAILABLE -> "HTTP $status: the provider is temporarily unavailable."
        ProviderFailureKind.UNKNOWN -> "HTTP $status: provider request failed; specific cause unavailable."
    }

    companion object {
        /** Only documented semantic codes are retained; arbitrary bodies and messages are excluded. */
        fun parse(status: Int, body: String = "", retryAfterMillis: Long? = null): ProviderFailure {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = root?.get("error") as? JsonObject
            val rawCode = ((error?.get("code") ?: root?.get("code")) as? JsonPrimitive)?.contentOrNull?.lowercase()
            val kind = when {
                rawCode in setOf("insufficient_quota", "insufficient_credits", "budget_exceeded", "credit_limit_exceeded") || status == 402 -> ProviderFailureKind.BUDGET
                rawCode in setOf("invalid_api_key", "authentication_error") || status == 401 -> ProviderFailureKind.AUTHENTICATION
                rawCode in setOf("permission_denied", "model_access_denied", "route_forbidden") -> ProviderFailureKind.PERMISSION
                status == 403 -> ProviderFailureKind.FORBIDDEN_UNKNOWN
                status == 429 -> ProviderFailureKind.RATE_LIMIT
                status in 500..599 -> ProviderFailureKind.UNAVAILABLE
                else -> ProviderFailureKind.UNKNOWN
            }
            val safeCode = rawCode?.takeIf { it in setOf("insufficient_quota", "insufficient_credits", "budget_exceeded", "credit_limit_exceeded", "invalid_api_key", "authentication_error", "permission_denied", "model_access_denied", "route_forbidden") }
            return ProviderFailure(status, kind, safeCode, retryAfterMillis)
        }
    }
}
