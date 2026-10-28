package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Never infer a green PR from an empty, partial or unavailable check response. */
internal fun gitHubCheckSummary(checks: JsonObject, status: JsonObject): String {
    val observed = checks["check_runs"]?.jsonArray ?: return "UNKNOWN"
    val identified = observed.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.longOrNull?.takeIf { id -> id > 0 } }
    if (identified.distinct().size != identified.size) return "UNKNOWN"
    val groups = observed.mapIndexed { index, value ->
        val run = value.jsonObject
        val name = run["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val app = (run["app"] as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
        // Unidentified producers cannot supersede one another.
        (if (name != null && app != null) "$app:$name" else "unidentified:$index") to value
    }.groupBy({ it.first }, { it.second })
    val runs = groups.values.map { attempts ->
        if (attempts.size == 1) {
            attempts.single()
        } else {
            if (attempts.any { (it.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: 0) <= 0 }) return "UNKNOWN"
            attempts.maxBy { it.jsonObject.getValue("id").jsonPrimitive.longOrNull!! }
        }
    }
    val statuses = status["total_count"]?.jsonPrimitive?.intOrNull ?: return "UNKNOWN"
    val conclusions = runs.map { it.jsonObject["conclusion"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    val statusState = status["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (conclusions.any { it in setOf("failure", "cancelled", "timed_out", "action_required", "startup_failure") } || statuses > 0 && statusState in setOf("failure", "error")) return "FAILED"
    if (runs.any { it.jsonObject["status"]?.jsonPrimitive?.contentOrNull != "completed" } || statuses > 0 && statusState == "pending") return "RUNNING"
    val expected = checks["total_count"]?.jsonPrimitive?.intOrNull ?: return "UNKNOWN"
    if (expected != observed.size) return "UNKNOWN"
    if (runs.isEmpty() && statuses == 0) return "NONE"
    if (conclusions.any { it !in setOf("success", "neutral", "skipped") } || statuses > 0 && statusState != "success") return "UNKNOWN"
    return if ("success" in conclusions || statuses > 0 && statusState == "success") "PASSED" else "NONE"
}
