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

    fun checkpoint(
        receipts: Map<String, JsonObject>,
        current: Int? = null,
        fixtureCalls: Int = FIXTURE_CALLS,
        acceptedContract: JsonObject? = null,
        retainedBudget: JsonObject? = null
    ): JsonObject = buildJsonObject {
        val receipt = recorded(receipts)
        val observed = receipt?.get("tool_budget") as? JsonObject
        val contractLimit = acceptedContract?.number("effective_tool_call_limit") ?: acceptedContract?.number("tool_call_limit")
        val contractIdentity = acceptedContract?.takeIf { contractLimit != null }?.let { contract ->
            GitHubEvidenceBook.digest(JsonObject(contract.filterKeys { it in setOf("binding", "repository", "source_message_id", "request_digest", "tool_call_limit", "effective_tool_call_limit") }.toSortedMap()).toString())
        }
        val retained = retainedBudget?.takeIf { contractIdentity != null && it["accepted_contract_identity"] == JsonPrimitive(contractIdentity) }
        val limit = listOfNotNull(contractLimit, observed?.number("limit"), retained?.number("effective_call_limit")).filter { it >= 0 }.minOrNull()
        val restoredRemaining = retained?.number("remaining_calls")?.takeIf { it >= 0 }
        val receiptRemaining = remaining(receipts)
        val remaining = current?.coerceAtLeast(0) ?: listOfNotNull(receiptRemaining, restoredRemaining).minOrNull()
        limit?.let { put("effective_call_limit", it) }
        (acceptedContract?.get("tool_call_limit") ?: observed?.get("configured"))?.let { put("configured_call_limit", it) }
        contractIdentity?.let { put("accepted_contract_identity", it) }
        put(
            "limit_source",
            if (contractLimit != null && limit == contractLimit) {
                "AUTHORIZED_RUN_CONTRACT"
            } else if (observed?.number("limit") == limit && observed != null) {
                "RECORDED_EXECUTION_ALLOWANCE"
            } else if (retained != null && limit != null) {
                (retained["limit_source"] as? JsonPrimitive)?.content ?: "RETAINED_EXECUTION_ALLOWANCE"
            } else {
                "NOT_RECORDED"
            }
        )
        put("limit_rule", "Minimum of the profile/chat allowance, workflow/task policy and explicit request ceiling.")
        remaining?.let { put("remaining_calls", it) }
        put(
            "remaining_source",
            if (current != null) {
                "LIVE_EXECUTION_LEDGER"
            } else if (restoredRemaining != null && remaining == restoredRemaining) {
                "RETAINED_EXECUTION_CHECKPOINT"
            } else if (observed != null) {
                "RECORDED_EXECUTION_HIGH_WATER"
            } else {
                "NOT_RECORDED"
            }
        )
        // A ceiling without its counter does not imply zero calls used. The
        // accepted identity also prevents a resumed report borrowing another task's allowance.
        if (limit != null && remaining != null && remaining <= limit) put("used_calls", limit - remaining)
        observed?.get("used")?.let { put("observed_used_calls", it) }
        observed?.get("sequence")?.let { put("observed_counter_sequence", it) }
        receipt?.get("receipt_id")?.let { put("observed_counter_receipt_id", it) }
        put("counter_scope", "Shared task allowance, including automatic result paging and recovery; live execution remaining supersedes receipt snapshots.")
        put("remaining_known", remaining != null)
        put("verification_reserve", minOf(remaining ?: VERIFICATION_RESERVE, VERIFICATION_RESERVE))
        put("cleanup_reserve", minOf(remaining ?: FIXTURE_CALLS, cleanupReserve(receipts)))
        put("cleanup_reserve_scope", "Remaining reconciliation and verified cleanup of the existing task-owned fixture; does not raise the accepted allowance.")
        put("fixture_start_minimum", fixtureCalls + VERIFICATION_RESERVE)
        put("acquisition_allowed", remaining?.let { it > VERIFICATION_RESERVE + cleanupReserve(receipts) } != false)
        put("hard_limit_policy", "Use the authorized execution ceiling; never expand it to finish a checklist.")
        put("reporting", "Final response capacity remains separately reserved by the run budget.")
    }

    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
}
