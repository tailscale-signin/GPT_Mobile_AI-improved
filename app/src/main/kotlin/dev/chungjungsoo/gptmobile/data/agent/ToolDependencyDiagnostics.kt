package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Observed blocked DAG edges only; a deferred prerequisite is not a failed operation. */
internal object ToolDependencyDiagnostics {
    enum class State { DEFERRED, FAILED }

    fun rejection(callId: String, state: State, dependencies: Set<String>, blocked: Set<String>, message: String): AgentToolResult {
        require(dependencies.containsAll(blocked) && blocked.isNotEmpty())
        val receipt = buildJsonObject {
            put("failed_stage", "tool_precheck")
            put("native_dependency_state", state.name)
            put("dependency_count", dependencies.size)
            if (state == State.FAILED) put("dependency_failed_count", blocked.size)
            // Match the report's call-ID hash domain so an explicit edge joins
            // its prerequisite receipt without exposing raw call identifiers.
            put("dependency_call_id_hashes", JsonArray(blocked.sorted().take(8).map { JsonPrimitive(callHash(it)) }))
        }
        return AgentToolResult(callId, ToolResultContent.Text(message), true, errorCode = "DEPENDENCY_${state.name}", dispatched = false, executionReceipt = receipt)
    }

    private fun callHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("gptmobile.issue-context.v1:call:$value".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
