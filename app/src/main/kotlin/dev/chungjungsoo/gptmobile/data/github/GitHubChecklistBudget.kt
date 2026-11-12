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

    /** Once a fixture exists, optional reads cannot spend its reconciliation/cleanup allowance. */
    fun cleanupReserve(receipts: Map<String, JsonObject>): Int =
        if (GitHubSelfTestFixtures.checkpoint(receipts).filterIsInstance<JsonObject>().any { it["final_state_verified"] != JsonPrimitive(true) }) FIXTURE_CALLS else 0

    fun acquisitionAllowed(receipts: Map<String, JsonObject>, current: Int? = null): Boolean =
        remaining(receipts, current)?.let { it > VERIFICATION_RESERVE + cleanupReserve(receipts) } != false

    fun remaining(receipts: Map<String, JsonObject>, current: Int? = null): Int? {
        if (current != null) return current.coerceAtLeast(0)
        return recorded(receipts)?.let { receipt ->
            val budget = receipt["tool_budget"] as JsonObject
            ((budget["limit"] as JsonPrimitive).intOrNull!! - (budget["used"] as JsonPrimitive).intOrNull!!).coerceAtLeast(0)
        }
    }

    // Restored receipts may arrive out of order. Without a live ledger, use the
    // conservative high-water observation rather than resurrecting spent calls.
    private fun recorded(receipts: Map<String, JsonObject>) = receipts.values.filter { receipt ->
        val budget = receipt["tool_budget"] as? JsonObject
        (budget?.get("used") as? JsonPrimitive)?.intOrNull != null && (budget?.get("limit") as? JsonPrimitive)?.intOrNull != null
    }.minByOrNull { receipt ->
        val budget = receipt["tool_budget"] as JsonObject
        (budget["limit"] as JsonPrimitive).intOrNull!! - (budget["used"] as JsonPrimitive).intOrNull!!
    }

    fun checkpoint(receipts: Map<String, JsonObject>, current: Int? = null, fixtureCalls: Int = FIXTURE_CALLS): JsonObject = buildJsonObject {
        val remaining = remaining(receipts, current)
        val receipt = recorded(receipts)
        val observed = receipt?.get("tool_budget") as? JsonObject
        observed?.get("limit")?.let { put("effective_call_limit", it) }
        observed?.get("configured")?.let { put("configured_call_limit", it) }
        put("limit_source", if (observed != null) "RECORDED_EXECUTION_ALLOWANCE" else "NOT_RECORDED")
        put("limit_rule", "Minimum of the profile/chat allowance, workflow/task policy and explicit request ceiling.")
        remaining?.let { put("remaining_calls", it) }
        put(
            "remaining_source",
            if (current != null) {
                "LIVE_EXECUTION_LEDGER"
            } else if (observed != null) {
                "RECORDED_EXECUTION_HIGH_WATER"
            } else {
                "NOT_RECORDED"
            }
        )
        observed?.get("used")?.let { put("observed_used_calls", it) }
        observed?.get("sequence")?.let { put("observed_counter_sequence", it) }
        receipt?.get("receipt_id")?.let { put("observed_counter_receipt_id", it) }
        put("counter_scope", "Shared task allowance, including automatic result paging and recovery; live execution remaining supersedes receipt snapshots.")
        put("remaining_known", remaining != null)
        put("verification_reserve", minOf(remaining ?: VERIFICATION_RESERVE, VERIFICATION_RESERVE))
        put("cleanup_reserve", minOf(remaining ?: FIXTURE_CALLS, cleanupReserve(receipts)))
        put("cleanup_reserve_scope", "Remaining reconciliation and verified cleanup of the existing task-owned fixture; does not raise the accepted allowance.")
        put("fixture_start_minimum", fixtureCalls + VERIFICATION_RESERVE)
        put("acquisition_allowed", acquisitionAllowed(receipts, current))
        put("hard_limit_policy", "Use the authorized execution ceiling; never expand it to finish a checklist.")
        put("reporting", "Final response capacity remains separately reserved by the run budget.")
    }
}
