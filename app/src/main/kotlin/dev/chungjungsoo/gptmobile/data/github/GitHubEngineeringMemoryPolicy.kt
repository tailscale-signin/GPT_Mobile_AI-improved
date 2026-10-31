package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object GitHubEngineeringMemoryPolicy {
    fun scope(connection: String, revision: Long, profile: String, repository: String, branch: String, permission: String = "") = "github:" + GitHubEvidenceBook.digest(JsonArray(listOf(connection, revision.toString(), profile, repository.lowercase(java.util.Locale.ROOT), branch, permission).map(::JsonPrimitive)).toString())
    fun terms(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.length >= 3 }.toSet()
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
