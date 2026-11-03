package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object GitHubEngineeringMemoryPolicy {
    fun scope(connection: String, revision: Long, profile: String, repository: String, branch: String, permission: String = "") = "github:" + GitHubEvidenceBook.digest(JsonArray(listOf(connection, revision.toString(), profile, repository.lowercase(java.util.Locale.ROOT), branch, permission).map(::JsonPrimitive)).toString())
    fun terms(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.length >= 3 }.toSet()
    fun checkpoint(verdict: JsonObject): JsonObject? {
        if (verdict["schema"] != JsonPrimitive("github.verification.v1") || (verdict["task"] as? JsonPrimitive)?.content.isNullOrBlank()) return null
        return buildJsonObject {
            put("task", verdict["task"] ?: JsonPrimitive(""))
            put("task_outcome", verdict["task_outcome"] ?: JsonPrimitive("PARTIAL"))
            put("operation_coverage", verdict["operation_coverage"] ?: JsonArray(emptyList()))
            verdict["test_checklist"]?.let { put("test_checklist", it) }
            verdict["requirements"]?.let { put("requirements", it) }
            verdict["remaining_requirements"]?.let { put("remaining_requirements", it) }
            put("completion_verified", false)
            put("next_action", "Resume unresolved requirements using retained receipt IDs. Refresh mutable CI/PR state; source reads are not test execution. Do not repeat a completed mutation.")
        }
    }

    fun requirements(task: String): JsonArray {
        val lines = task.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        // A long task remains one exact requirement; it is never silently truncated to the first N bullets.
        val items = if (lines.size in 1..64) lines else listOf(task)
        return JsonArray(
            items.map { value ->
                buildJsonObject {
                    put("id", GitHubEvidenceBook.digest(value).take(24))
                    put("requirement", value)
                    put("status", "PLANNED")
                    put("acceptance", "Not available / Not measured until bound to explicit changed-path and test evidence")
                }
            }
        )
    }
}
