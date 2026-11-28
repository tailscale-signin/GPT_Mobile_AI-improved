package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Observed blocked DAG edges only; a deferred prerequisite is not a failed operation. */
internal object ToolDependencyDiagnostics {
    enum class State { DEFERRED, FAILED }
    enum class Admission { TOOL_UNAVAILABLE, REPEATED_FAILURE, REPEAT_LIMIT, CALL_ALLOWANCE, DECISION_REQUIRED, PREREQUISITE_DEFERRED }
    enum class RepeatScope { TOOL_NAME, TOOL_NAME_AND_ARGUMENTS }

    /** Original admission operands, before dependency closure or tool execution. */
    data class RepeatLimitSnapshot(val scope: RepeatScope, val priorAdmissions: Int, val batchAdmissions: Int, val used: Int, val limit: Int)

    fun repeatLimitDetails(snapshot: RepeatLimitSnapshot?): Map<String, Any> {
        if (snapshot == null ||
            listOf(snapshot.priorAdmissions, snapshot.batchAdmissions, snapshot.used, snapshot.limit).any { it !in 0..16_000_000 } ||
            snapshot.priorAdmissions.toLong() + snapshot.batchAdmissions.toLong() != snapshot.used.toLong() ||
            snapshot.used.toLong() < snapshot.limit.toLong()
        ) {
            return emptyMap()
        }
        return mapOf(
            "dependency_repeat_scope" to snapshot.scope.name,
            "dependency_repeat_prior_admissions" to snapshot.priorAdmissions,
            "dependency_repeat_batch_admissions" to snapshot.batchAdmissions,
            "dependency_repeat_used" to snapshot.used,
            "dependency_repeat_limit" to snapshot.limit
        )
    }

    fun rejection(callId: String, state: State, dependencies: Set<String>, blocked: Set<String>, message: String, admission: Set<Admission> = emptySet()): AgentToolResult {
        require(dependencies.containsAll(blocked) && blocked.isNotEmpty())
        val receipt = buildJsonObject {
            put("failed_stage", "tool_precheck")
            put("native_dependency_state", state.name)
            put("dependency_count", dependencies.size)
            if (admission.isNotEmpty()) put("dependency_admission_reasons", JsonArray(admission.map { JsonPrimitive(it.name) }))
            if (state == State.FAILED) put("dependency_failed_count", blocked.size)
            // Match the report's call-ID hash domain so an explicit edge joins
            // its prerequisite receipt without exposing raw call identifiers.
            val hashes = blocked.sorted().take(8).map(::callHash)
            put("dependency_call_id_hashes", JsonArray(hashes.map(::JsonPrimitive)))
            if (blocked.size == 1) put("causal_id", "tool-dependency:${hashes.single()}")
        }
        return AgentToolResult(callId, ToolResultContent.Text(message), true, errorCode = "DEPENDENCY_${state.name}", dispatched = false, executionReceipt = receipt)
    }

    fun decisionDeferred(callId: String, decisions: List<ProviderEvent.ToolCall>, completed: List<AgentToolResult>): AgentToolResult {
        val ids = decisions.mapTo(linkedSetOf()) { it.callId }
        require(ids.isNotEmpty())
        val answered = completed.count { it.callId in ids && !it.isError }
        val cancelled = completed.any { it.callId in ids && it.errorCode == "GITHUB_USER_STOP" }
        val pending = (ids.size - answered).coerceAtLeast(0)
        val message = when {
            cancelled -> "The saved decision stopped this work. The dependent call was not dispatched. Retain the results and report the stop."
            pending > 0 -> "Resolve the saved decision card before replanning this dependent call. It was not dispatched; its tool remains authorized."
            else -> "The saved decision was answered in this batch. Apply that answer and replan the dependent call with a new call ID. It was not dispatched; this is not a missing tool capability."
        }
        val rejected = rejection(callId, State.DEFERRED, ids, ids, message, setOf(Admission.DECISION_REQUIRED))
        return rejected.copy(
            executionReceipt = JsonObject(
                rejected.executionReceipt.orEmpty() + buildJsonObject {
                    put("decision_count", ids.size)
                    put("answered_decision_count", answered)
                    put("pending_decision_count", pending)
                    put("decision_cancelled", cancelled)
                    put("dispatched", false)
                    put("requires_reconciliation", false)
                }
            )
        )
    }

    internal fun callHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("gptmobile.issue-context.v1:call:$value".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
