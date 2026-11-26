package dev.chungjungsoo.gptmobile.data.network.error

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class ProviderFailureKind { AUTHENTICATION, BUDGET, PERMISSION, CONTENT_POLICY, FORBIDDEN_UNKNOWN, RATE_LIMIT, UNAVAILABLE, UNKNOWN }

@Serializable
data class ProviderFailure(val status: Int, val kind: ProviderFailureKind, val code: String? = null, val retryAfterMillis: Long? = null, val budget: ProviderBudgetEvidence? = null) {
    val retryable: Boolean get() = kind in setOf(ProviderFailureKind.RATE_LIMIT, ProviderFailureKind.UNAVAILABLE)

    /** Cached denials need the same cause-specific guidance as the original response. */
    fun blockedMessage(): String = message() + when (kind) {
        ProviderFailureKind.BUDGET -> if (budget?.kind == ProviderBudgetKind.IN_FLIGHT_BUDGET) " Saved evidence is retained for an explicit retry." else " Check the provider allowance or choose another AI profile. After resolving the restriction, retry the failed stage to recheck. Any saved evidence is retained."
        ProviderFailureKind.AUTHENTICATION, ProviderFailureKind.PERMISSION, ProviderFailureKind.FORBIDDEN_UNKNOWN -> " Update the platform connection or choose another AI profile, then retry the failed stage to recheck. Any saved evidence is retained."
        else -> " Check the provider, then retry the failed stage for an explicit recheck. Any saved evidence is retained."
    }

    fun message(): String = when (kind) {
        ProviderFailureKind.AUTHENTICATION -> "HTTP $status: the provider could not authenticate this request. Check the platform connection in Settings → Platforms."
        ProviderFailureKind.BUDGET -> when (code) {
            "kudosupfront" -> "HTTP $status: this generation exceeds the current anonymous allowance. Reduce the output allowance or allow a shorter generation."
            "sharedkeyempty" -> "HTTP $status: the provider reported that the shared key allowance is exhausted."
            else -> "HTTP $status: " + (budget ?: ProviderBudgetEvidence(ProviderBudgetKind.UNKNOWN)).message()
        }
        ProviderFailureKind.CONTENT_POLICY -> "HTTP $status: The provider declined this request under its content policy."
        ProviderFailureKind.PERMISSION -> "HTTP $status: the provider reported an explicit permission restriction for this route."
        ProviderFailureKind.FORBIDDEN_UNKNOWN -> "HTTP 403: access forbidden. The provider's specific cause is unavailable."
        ProviderFailureKind.RATE_LIMIT -> "HTTP $status: provider rate limit reached. Resume after the provider cooldown."
        ProviderFailureKind.UNAVAILABLE -> if (code == "model_load_failed") "HTTP $status: the configured model failed to load on the server. Check its model file, backend and available resources. A brief cooldown prevents repeated load attempts; saved evidence remains available." else "HTTP $status: the provider is temporarily unavailable."
        ProviderFailureKind.UNKNOWN -> "HTTP $status: provider request failed; specific cause unavailable."
    }

    companion object {
        /** Only documented semantic codes are retained; arbitrary bodies and messages are excluded. */
        fun parse(status: Int, body: String = "", retryAfterMillis: Long? = null): ProviderFailure {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = root?.get("error") as? JsonObject ?: root
            return fromValues(
                status,
                ((error?.get("code") ?: root?.get("code") ?: root?.get("rc")) as? JsonPrimitive)?.contentOrNull,
                (error?.get("message") as? JsonPrimitive)?.contentOrNull,
                (error?.get("type") as? JsonPrimitive)?.contentOrNull,
                retryAfterMillis,
                error?.get("metadata") as? JsonObject
            )
        }

        /** HTTP and accepted streams share semantic classification; status remains the observed transport status. */
        fun fromValues(status: Int, code: String?, message: String?, type: String? = null, retryAfterMillis: Long? = null, metadata: JsonObject? = null): ProviderFailure {
            val rawCode = code?.lowercase(java.util.Locale.ROOT)
            val rawType = type?.lowercase(java.util.Locale.ROOT)
            val semanticCodes = setOfNotNull(rawCode, rawType)
            val budget = if ("insufficient_quota" in semanticCodes && metadata?.containsKey("limit_source") != true && metadata?.containsKey("reason") != true) {
                ProviderBudgetEvidence(ProviderBudgetKind.PROVIDER_QUOTA)
            } else {
                ProviderBudgetEvidence.from(metadata)
            }
            val kind = when {
                semanticCodes.any { it in setOf("content_policy_violation", "content_filter", "safety_blocked") } -> ProviderFailureKind.CONTENT_POLICY
                semanticCodes.any { it in BUDGET_CODES } || status == 402 || budget.limitSource != null || budgetMessage.containsMatchIn(message.orEmpty().take(4096)) -> ProviderFailureKind.BUDGET
                semanticCodes.any { it in setOf("invalid_api_key", "authentication_error", "401") } || status == 401 -> ProviderFailureKind.AUTHENTICATION
                semanticCodes.any { it in setOf("permission_denied", "model_access_denied", "route_forbidden", "anonforbidden") } -> ProviderFailureKind.PERMISSION
                status == 403 -> ProviderFailureKind.FORBIDDEN_UNKNOWN
                status == 429 || semanticCodes.any { it in setOf("429", "rate_limit_exceeded", "tokens_limit_exceeded", "rate_limit_error") } -> ProviderFailureKind.RATE_LIMIT
                status in 500..599 || semanticCodes.any { it in TRANSIENT_CODES } || processingMessage.containsMatchIn(message.orEmpty().take(4096)) -> ProviderFailureKind.UNAVAILABLE
                else -> ProviderFailureKind.UNKNOWN
            }
            val safeCode = semanticCodes.firstOrNull { it in SAFE_CODES }
            return ProviderFailure(status, kind, safeCode, retryAfterMillis, budget.takeIf { kind == ProviderFailureKind.BUDGET })
        }

        internal val BUDGET_CODES = setOf("402", "insufficient_quota", "insufficient_credits", "budget_exceeded", "credit_limit_exceeded", "kudosupfront", "sharedkeyempty")
        internal val TRANSIENT_CODES = setOf("500", "502", "503", "504", "server_error", "internal_error", "internal_server_error", "service_unavailable", "overloaded_error", "api_error", "processing_error")
        internal val SAFE_CODES = BUDGET_CODES + TRANSIENT_CODES + setOf("401", "429", "invalid_api_key", "authentication_error", "permission_denied", "model_access_denied", "route_forbidden", "anonforbidden", "noworkers", "no_valid_workers", "context_window_exceeded", "context_length_exceeded", "rate_limit_exceeded", "tokens_limit_exceeded", "rate_limit_error", "content_policy_violation", "content_filter", "safety_blocked")
        private val budgetMessage = Regex("(?i)\\b(?:no credits remaining|insufficient (?:credits|funds|balance)|credit balance|payment required|billing.{0,30}(?:required|disabled|exhausted))\\b")
        private val processingMessage = Regex("(?i)\\b(?:error (?:occurred )?(?:while )?processing (?:your|the) request|provider is temporarily unavailable)\\b")
    }
}
