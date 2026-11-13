package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Observed blocked DAG edges only; a deferred prerequisite is not a failed operation. */
internal object ToolDependencyDiagnostics {
    enum class State { DEFERRED, FAILED }
    enum class Admission { TOOL_UNAVAILABLE, REPEATED_FAILURE, REPEAT_LIMIT, CALL_ALLOWANCE, DECISION_REQUIRED, PREREQUISITE_DEFERRED }

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

    private fun callHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("gptmobile.issue-context.v1:call:$value".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
