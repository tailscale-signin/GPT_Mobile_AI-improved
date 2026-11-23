package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One observation for an accepted task, independent of attempts, model calls and progress notices. */
internal data class GitHubWorkMeasurement(val state: String, val passing: Int, val required: Int, val completed: Boolean, val assessed: Boolean, val timing: GitHubTaskWorkTiming? = null) {
    fun json() = buildJsonObject {
        put("task_state", state)
        put("receipt_backed_requirements", passing)
        put("required_requirements", required)
        put("completion_verified_and_delivered", completed)
        put("acceptance_assessed", assessed)
        timing?.let { put("timing", it.json()) }
    }

    companion object {
        private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
        private val receipt = Regex("ev_[a-f0-9]{24}")
        fun from(verdict: JsonObject?, runState: String? = null): GitHubWorkMeasurement {
            val ledger = verdict?.get("acceptance_ledger") as? JsonObject
            val required = (ledger?.get("requirements") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it["required"] == JsonPrimitive(true) }
            val assessed = ledger.text("schema") == "github.acceptance-ledger.v1" && required.isNotEmpty() && verdict?.get("evidence_retention_complete") != JsonPrimitive(false)
            val backed = required.count { row ->
                row.text("state") == "PASS" && (row["receipt_ids"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull?.matches(receipt) == true }
            }
            val complete = assessed &&
                required.all { it.text("state") == "PASS" } &&
                ledger?.get("completion_verified") == JsonPrimitive(true) &&
                verdict?.get("task_outcome") == JsonPrimitive("COMPLETED") &&
                verdict["completion_verified"] == JsonPrimitive(true) &&
                verdict["response_delivered"] == JsonPrimitive(true)
            val execution = verdict?.get("execution_ledger") as? JsonObject
            val original = verdict?.get("original_failure") as? JsonObject
            val stop = verdict?.get("stop_reason") as? JsonObject
            val markers = listOfNotNull(runState, verdict.text("task_state"), verdict.text("blocking_condition"), verdict.text("task_outcome"), verdict.text("recovery_state"), verdict.text("verification_failure"), stop.text("code"), original.text("code"), execution.text("recovery"))
            val state = when {
                complete -> "COMPLETED"
                "REJECTED" in markers || "APPROVAL_REJECTED" in markers -> "REJECTED"
                "ACCEPTED_TASK_DEADLINE_REACHED" in markers || "PAUSED" in markers -> "PAUSED"
                "WAITING_FOR_APPROVAL" in markers -> "WAITING_FOR_APPROVAL"
                markers.any { it in setOf("WAITING_PROVIDER_LIMIT", "WAITING_FOR_PROVIDER", "WAITING_REMOTE_JOB") } -> "WAITING_FOR_PROVIDER"
                "WAITING_FOR_CONNECTION" in markers -> "WAITING_FOR_CONNECTION"
                "CANCELED" in markers -> "CANCELED"
                markers.any { it in setOf("INTERRUPTED", "SERVICE_STOPPED") } -> "INTERRUPTED"
                execution?.get("user_input_required") == JsonPrimitive(true) || verdict?.get("user_input_required") == JsonPrimitive(true) || "BASELINE_REQUIRED" in markers -> "WAITING_FOR_INPUT"
                markers.any { it in setOf("BLOCKED", "CONFIGURATION_INVALIDATED") } -> "BLOCKED"
                "FAILED" in markers -> "FAILED"
                "PARTIAL" in markers -> "PARTIAL"
                verdict.text("phase") == "IN_PROGRESS" || "RUNNING" in markers || "QUEUED" in markers -> "WORKING"
                else -> "UNASSESSED"
            }
            return GitHubWorkMeasurement(state, if (assessed) backed else 0, required.size, complete, assessed)
        }
    }
}

internal data class GitHubConversationWork(val tasks: List<GitHubWorkMeasurement>, val olderTasks: Boolean) {
    fun description(): String = if (tasks.isEmpty()) {
        "No accepted GitHub task has been saved in this conversation."
    } else {
        val scope = if (olderTasks) "the latest ${tasks.size} retained tasks" else "${tasks.size} retained ${if (tasks.size == 1) "task" else "tasks"}"
        val assessed = tasks.filter { it.assessed }
        buildString {
            append("${tasks.count { it.completed }} of $scope verified complete and delivered.")
            if (assessed.isNotEmpty()) append(" ${assessed.sumOf { it.passing }} of ${assessed.sumOf { it.required }} required items have passing receipts.")
            if (tasks.size > assessed.size) append(" ${tasks.size - assessed.size} awaiting an acceptance assessment.")
            val pending = tasks.filterNot { it.completed }.groupingBy { it.state }.eachCount()
            if (pending.isNotEmpty()) append(pending.entries.joinToString(", ", prefix = "\n") { (key, count) -> "$count ${key.lowercase().replace('_', ' ')}" })
            if (tasks.any { it.timing != null }) {
                append("\nTiming uses retained wall-clock observations. Unobserved time can include gaps or suspension; it is not measured active work. Task intervals count overlapping runs once.")
                tasks.forEachIndexed { index, task ->
                    task.timing?.let { timing ->
                        append("\n\nTask ${index + 1}: ")
                        append(timing.task?.description() ?: "Elapsed timing is unavailable.")
                        if (timing.startBasis == "EARLIEST_RETAINED_RUN") append(" Start is the earliest retained run; task acceptance time is unavailable.")
                        timing.runs.take(4).forEachIndexed { runIndex, run -> append("\nRun ${runIndex + 1}: ${run.timing?.description() ?: "Timing unavailable."}") }
                        if (timing.runs.size > 4) append("\n${timing.runs.size - 4} further runs contribute to the task breakdown.")
                    }
                }
            }
        }
    }
}
