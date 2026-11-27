package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Capability counts describe the accepted qualification scope, never overall task completion. */
internal object GitHubQualificationSummary {
    fun scope(task: String): String = when {
        GitHubChecklistScope.readOnly(task) -> "READ_ONLY_INSPECTION"
        GitHubChecklistScope.supportedFixturePlan(task) -> "SUPPORTED_FIXTURE_QUALIFICATION"
        else -> "FULL_LIVE_QUALIFICATION"
    }

    fun scopeLabel(task: String): String = when (scope(task)) {
        "READ_ONLY_INSPECTION" -> "Read-only inspection"
        "SUPPORTED_FIXTURE_QUALIFICATION" -> "Supported fixture qualification"
        else -> "Full live qualification"
    }

    fun snapshot(task: String, capabilities: JsonArray): JsonObject {
        val rows = capabilities.filterIsInstance<JsonObject>()
        val required = rows.filter { it["required"] == JsonPrimitive(true) }
        val outcomes = required.groupingBy { row ->
            when {
                row.text("acceptance_state") == "PASS" -> "passed"
                row.text("status") in setOf("FAILED", "PREFLIGHT_REJECTED") -> "failed"
                row.text("status") in setOf("BLOCKED", "CONDITIONAL", "WAITING_FOR_APPROVAL") -> "blocked"
                else -> "untested"
            }
        }.eachCount()
        return buildJsonObject {
            put("scope", scope(task))
            put("scope_label", scopeLabel(task))
            put("required", required.size)
            put("excluded", rows.size - required.size)
            for (state in listOf("passed", "failed", "blocked", "untested")) put(state, outcomes.getOrDefault(state, 0))
            put("remaining", required.size - outcomes.getOrDefault("passed", 0))
            put("scope_authority", "ACCEPTED_TASK")
            put("counts_basis", "REQUIRED_CAPABILITY_ACCEPTANCE")
            put("task_criteria_are_separate", true)
            put("permissions_changed", false)
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
