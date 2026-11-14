package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Bounded server observations; the active conversation job is not the rejected request's job. */
internal fun providerConflictEvidence(error: JsonObject?): Map<String, JsonElement> = buildMap {
    if (error == null) return@buildMap
    for ((key, allowed) in mapOf(
        "remote_acceptance" to setOf("YES", "NO", "UNKNOWN"),
        "active_job_provenance" to setOf("CONVERSATION_ACTIVE_JOB"),
        "pending_client_tool_state" to setOf("IDENTIFIED", "MISSING", "EMPTY", "INVALID", "UNAVAILABLE")
    )) {
        (error[key] as? JsonPrimitive)?.takeIf { it.isString && it.content in allowed }?.let { put(key, it) }
    }
    for (key in listOf("resume_allowed", "requires_reconciliation")) {
        error[key]?.takeIf { it == JsonPrimitive(true) || it == JsonPrimitive(false) }?.let { put(key, it) }
    }
    (error["pending_client_tool_count"] as? JsonPrimitive)?.takeIf { !it.isString && it.intOrNull?.let { count -> count in 0..128 } == true }
        ?.let { put("pending_client_tool_count", it) }
    (error["client_tool_handoff_sequence"] as? JsonPrimitive)?.takeIf { !it.isString && it.intOrNull?.let { sequence -> sequence in 1..16_000_000 } == true }
        ?.let { put("client_tool_handoff_sequence", it) }
    (error["pending_client_tool_ids_hash"] as? JsonPrimitive)?.takeIf { it.isString && it.contentOrNull?.matches(Regex("[a-f0-9]{64}")) == true }
        ?.let { put("pending_client_tool_ids_hash", it) }
}
