package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Last-page success is never proof that earlier pages were collected or passed. */
internal object GitHubWorkflowJobEvidence {
    fun completeJobs(pages: List<JsonObject>): List<JsonObject>? {
        fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
        val byPage = pages.groupBy { it.number("page") ?: 1 }.mapValues { it.value.last() }
        val end = byPage.keys.filter { byPage[it]?.get("has_more") == JsonPrimitive(false) }.maxOrNull() ?: return null
        if (end !in 1..1000 || (1..end).any { it !in byPage }) return null
        val totals = byPage.values.mapNotNull { it.number("total_count") }.distinct()
        if (totals.size > 1) return null
        val jobs = (1..end).flatMap { ((byPage[it]?.get("jobs") as? JsonArray) ?: return null).map { row -> row as? JsonObject ?: return null } }
        val ids = jobs.map { (it["id"] as? JsonPrimitive)?.contentOrNull ?: return null }
        if (ids.distinct().size != ids.size || totals.singleOrNull()?.let { it != jobs.size } == true) return null
        return jobs.takeIf { it.isNotEmpty() }
    }
}
