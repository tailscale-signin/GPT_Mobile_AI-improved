package dev.chungjungsoo.gptmobile.data.network.error

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@Serializable
enum class ProviderBudgetKind { ACCOUNT_CREDITS, KEY_LIMIT, REQUEST_AFFORDABILITY, IN_FLIGHT_BUDGET, UNKNOWN }

/** Closed OpenRouter metadata; human-readable remedy text and account identifiers are never retained. */
@Serializable
data class ProviderBudgetEvidence(val kind: ProviderBudgetKind, val limitSource: String? = null, val reason: String? = null) {
    fun fields(): JsonObject = buildJsonObject {
        put("provider_budget_kind", kind.name)
        limitSource?.takeIf { it in LIMIT_SOURCES }?.let { put("provider_budget_limit_source", it) }
        reason?.takeIf { it in REASONS }?.let { put("provider_budget_reason", it) }
    }

    fun message(): String = when (kind) {
        ProviderBudgetKind.ACCOUNT_CREDITS -> "The provider's available account credits cannot cover this request. Add credits or explicitly reduce the request's input or output allowance, then retry the saved work."
        ProviderBudgetKind.KEY_LIMIT -> "The provider rejected this request at the API key's spending limit. Review that key's allowance or reset time, then retry the saved work."
        ProviderBudgetKind.REQUEST_AFFORDABILITY -> "This request's estimated cost exceeds the provider's per-request budget. Explicitly reduce the input or output allowance, or add credits, then retry the saved work."
        ProviderBudgetKind.IN_FLIGHT_BUDGET -> "The provider's budget is temporarily committed to concurrent or recently completed requests. Allow those requests to settle before explicitly retrying the saved work."
        ProviderBudgetKind.UNKNOWN -> "The provider reported a budget or credit restriction without a specific cause. Check the account and API key allowances before retrying the saved work."
    }

    companion object {
        val LIMIT_SOURCES = setOf("openrouter_credits", "openrouter_key_limit", "openrouter_in_flight_budget")
        val REASONS = setOf("weight_exceeds_budget", "in_flight_budget_exhausted")
        val RETRY_MODES = setOf("MANUAL_AFTER_PROVIDER_DELAY", "PROVIDER_TIMING_UNAVAILABLE", "AFTER_CONFIGURATION_CHANGE", "REVIEW_SAVED_WORK")

        // https://openrouter.ai/docs/api_reference/limits and errors-and-debugging.
        // Use documented metadata, never account guesses or a substring in remedy_hint.
        fun from(metadata: JsonObject?): ProviderBudgetEvidence {
            val rawSource = (metadata?.get("limit_source") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val rawReason = (metadata?.get("reason") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val source = rawSource?.takeIf { it in LIMIT_SOURCES }
            val reason = rawReason?.takeIf { it in REASONS }
            val kind = when {
                metadata?.containsKey("reason") == true && (rawReason == null || reason == null) -> ProviderBudgetKind.UNKNOWN
                source == "openrouter_credits" && reason == "weight_exceeds_budget" -> ProviderBudgetKind.REQUEST_AFFORDABILITY
                source == "openrouter_credits" && reason == null -> ProviderBudgetKind.ACCOUNT_CREDITS
                source == "openrouter_key_limit" && reason == null -> ProviderBudgetKind.KEY_LIMIT
                source == "openrouter_in_flight_budget" && reason in setOf(null, "in_flight_budget_exhausted") -> ProviderBudgetKind.IN_FLIGHT_BUDGET
                else -> ProviderBudgetKind.UNKNOWN
            }
            return ProviderBudgetEvidence(kind, source, reason)
        }

        fun metadata(body: String): JsonObject? {
            val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val error = ((root["response"] as? JsonObject)?.get("error") as? JsonObject) ?: root["error"] as? JsonObject ?: root
            return error["metadata"] as? JsonObject
        }
    }
}
