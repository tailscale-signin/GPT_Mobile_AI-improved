package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Never infer a green PR from an empty, partial or unavailable check response. */
internal fun gitHubCheckSummary(checks: JsonObject, status: JsonObject): String {
    val runs = checks["check_runs"]?.jsonArray ?: return "UNKNOWN"
    val statuses = status["total_count"]?.jsonPrimitive?.intOrNull ?: return "UNKNOWN"
    val conclusions = runs.map { it.jsonObject["conclusion"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    val statusState = status["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (conclusions.any { it in setOf("failure", "cancelled", "timed_out", "action_required", "startup_failure") } || statuses > 0 && statusState in setOf("failure", "error")) return "FAILED"
    if (runs.any { it.jsonObject["status"]?.jsonPrimitive?.contentOrNull != "completed" } || statuses > 0 && statusState == "pending") return "RUNNING"
    val expected = checks["total_count"]?.jsonPrimitive?.intOrNull ?: return "UNKNOWN"
    if (expected != runs.size) return "UNKNOWN"
    if (runs.isEmpty() && statuses == 0) return "NONE"
    if (conclusions.any { it !in setOf("success", "neutral", "skipped") } || statuses > 0 && statusState != "success") return "UNKNOWN"
    return if ("success" in conclusions || statuses > 0 && statusState == "success") "PASSED" else "NONE"
}
