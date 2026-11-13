package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A replayed prior invocation keeps its source identity without impersonating a new dispatch. */
internal fun currentGitHubInvocationReceipt(result: AgentToolResult): AgentToolResult {
    if (!result.sharedResult) return result
    val execution = result.executionReceipt ?: return result
    val trace = execution["tool_trace"] as? JsonObject ?: return result
    val sourceId = (trace["call_id"] as? JsonPrimitive)?.content ?: return result
    // A current invocation can reuse a catalogue while making physical scope-resolution
    // reads. Only a trace belonging to a different call proves that this accounting is old.
    if (sourceId == result.callId) return result
    val current = JsonObject(
        trace + mapOf(
            "call_id" to JsonPrimitive(result.callId),
            "dispatched" to JsonPrimitive(false),
            "reused_receipt" to JsonPrimitive(true),
            "remote_read_requests" to JsonPrimitive(0),
            "response_time_ms" to kotlinx.serialization.json.JsonNull
        )
    )
    val provenance = buildMap<String, kotlinx.serialization.json.JsonElement> {
        put("tool_trace", current)
        put("source_observation_call_id", execution["source_observation_call_id"] ?: JsonPrimitive(sourceId))
        put("source_observation_trace", execution["source_observation_trace"] ?: trace)
        (execution["source_observation_read_requests"] ?: execution["read_requests"])?.let { put("source_observation_read_requests", it) }
        (execution["source_observation_read_response_observations"] ?: execution["read_response_observations"])?.let { put("source_observation_read_response_observations", it) }
    }
    return result.copy(
        dispatched = false,
        executionReceipt = JsonObject(
            execution + provenance + mapOf(
                "read_requests" to JsonPrimitive(0),
                "read_response_observations" to JsonPrimitive(0),
                "observation_origin" to JsonPrimitive("RETAINED_OBSERVATION")
            )
        )
    )
}
