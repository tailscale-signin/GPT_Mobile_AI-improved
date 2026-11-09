package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Stage allocations live within the already authorized execution ceiling. */
internal object GitHubChecklistBudget {
    const val FIXTURE_CALLS = 4 // create, restore selected branch, dependencies, verified deletion
    const val VERIFICATION_RESERVE = 2

    fun remaining(receipts: Map<String, JsonObject>, current: Int? = null): Int? {
        if (current != null) return current.coerceAtLeast(0)
        return receipts.values.toList().asReversed().firstNotNullOfOrNull { receipt ->
            val budget = receipt["tool_budget"] as? JsonObject ?: return@firstNotNullOfOrNull null
            val used = (budget["used"] as? JsonPrimitive)?.intOrNull ?: return@firstNotNullOfOrNull null
            val limit = (budget["limit"] as? JsonPrimitive)?.intOrNull ?: return@firstNotNullOfOrNull null
            (limit - used).coerceAtLeast(0)
        }
    }

    fun checkpoint(receipts: Map<String, JsonObject>, current: Int? = null, fixtureCalls: Int = FIXTURE_CALLS): JsonObject = buildJsonObject {
        val remaining = remaining(receipts, current)
        val observed = receipts.values.toList().asReversed().firstNotNullOfOrNull { receipt ->
            (receipt["tool_budget"] as? JsonObject)?.takeIf { (it["limit"] as? JsonPrimitive)?.intOrNull != null }
        }
        observed?.get("limit")?.let { put("effective_call_limit", it) }
        observed?.get("configured")?.let { put("configured_call_limit", it) }
        put("limit_source", if (observed != null) "RECORDED_EXECUTION_ALLOWANCE" else "NOT_RECORDED")
        put("limit_rule", "Minimum of the profile/chat allowance, workflow/task policy and explicit request ceiling.")
        remaining?.let { put("remaining_calls", it) }
        put("remaining_known", remaining != null)
        put("verification_reserve", minOf(remaining ?: VERIFICATION_RESERVE, VERIFICATION_RESERVE))
        put("fixture_start_minimum", fixtureCalls + VERIFICATION_RESERVE)
        put("acquisition_allowed", remaining == null || remaining > VERIFICATION_RESERVE)
        put("hard_limit_policy", "Use the authorized execution ceiling; never expand it to finish a checklist.")
        put("reporting", "Final response capacity remains separately reserved by the run budget.")
    }
}
