package dev.chungjungsoo.gptmobile.data.benchmark

import kotlin.math.abs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal data class GitHubScoreDimension(val label: String, val weight: Int, val observed: Int, val planned: Int, val points: Double?)

internal data class GitHubIntelligenceScore(val revision: String, val reportId: String, val total: Double?, val dimensions: List<GitHubScoreDimension>) {
    companion object {
        private val rubric = listOf(
            Triple("task_completion", "Task completion", 25),
            Triple("code_correctness", "Code correctness", 20),
            Triple("repository_understanding", "Repository understanding", 15),
            Triple("error_recovery", "Error recovery", 15),
            Triple("context_retention", "Context retention", 10),
            Triple("tool_efficiency", "Tool efficiency", 5),
            Triple("evidence_accuracy", "Evidence accuracy", 5),
            Triple("response_quality", "Response quality", 5)
        )

        val unmeasured = rubric.map { (_, label, weight) -> GitHubScoreDimension(label, weight, 0, 0, null) }

        fun read(response: JsonObject, profileUid: String, model: String, profileRevision: String): GitHubIntelligenceScore? {
            require(response["schema"] == JsonPrimitive("gateway.profile-intelligence-score.v1") && response["profile_revision"] == JsonPrimitive(profileRevision))
            if (response["report"] == JsonNull) return null
            val report = requireNotNull(response["report"] as? JsonObject)
            require(report["profile_uid"] == JsonPrimitive(profileUid) && report["model"] == JsonPrimitive(model))
            val id = requireNotNull((report["id"] as? JsonPrimitive)?.contentOrNull).also { require(it.matches(Regex("qual_[a-f0-9]{32}"))) }
            val revision = requireNotNull((report["base_sha"] as? JsonPrimitive)?.contentOrNull).also { require(it.matches(Regex("[a-f0-9]{40}"))) }
            if (report["score"] == JsonNull) return GitHubIntelligenceScore(revision, id, null, unmeasured)
            val score = requireNotNull(report["score"] as? JsonObject)
            require(score["schema"] == JsonPrimitive("gateway.github-intelligence.v1"))
            val values = requireNotNull(score["dimensions"] as? JsonObject)
            require(values.keys == rubric.map { it.first }.toSet())
            val dimensions = rubric.map { (key, label, weight) ->
                val item = requireNotNull(values[key] as? JsonObject)
                require(item["weight"] == JsonPrimitive(weight))
                val observed = requireNotNull((item["observed"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
                val planned = requireNotNull((item["planned"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
                require(planned > 0 && observed in 0..planned)
                val points = item.points("points", weight)
                require(points == null || observed == planned)
                GitHubScoreDimension(label, weight, observed, planned, points)
            }
            val total = score.points("pointsOutOf100", 100)
            require(total == null || dimensions.all { it.points != null } && abs(total - dimensions.sumOf { requireNotNull(it.points) }) < 0.000001)
            return GitHubIntelligenceScore(revision, id, total, dimensions)
        }

        private fun JsonObject.points(key: String, max: Int): Double? {
            if (get(key) == JsonNull) return null
            return requireNotNull((get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull).also { require(it in 0.0..max.toDouble()) }
        }
    }
}
