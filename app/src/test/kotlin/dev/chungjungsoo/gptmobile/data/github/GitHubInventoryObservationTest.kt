package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubInventoryObservationTest {
    private fun source(field: String = "workflow_runs") = buildJsonObject {
        put("page", 1)
        put("total_count", 18)
        put("has_more", false)
        put(
            field,
            JsonArray(
                (1..18).map { index ->
                    buildJsonObject {
                        put("id", index)
                        put("head_sha", "a".repeat(40))
                        put("status", "completed")
                        put("conclusion", if (index <= 7) "success" else "skipped")
                        put("details", "retained payload ".repeat(100))
                    }
                }
            )
        )
    }
    private fun receipt(original: JsonObject, action: String = "runs") = buildJsonObject {
        put("receipt_id", "runs")
        put("tool", if (action == "runs") "github_ci" else "github_context")
        put("trusted", true)
        put("success", true)
        put("arguments", buildJsonObject { put("action", action) })
        put("result", GitHubEvidenceProjection.result(original, 400))
        put("review_projection", "EXACT_VALUES_WITH_DURABLE_ORIGINAL")
        put("inventory_observation", requireNotNull(GitHubInventoryObservation.capture(original, buildJsonObject { put("workflow_runs", JsonArray((original["workflow_runs"] as? JsonArray).orEmpty().take(3))) })))
        put("retained_result_complete", true)
        put("result_sha256", "retained-hash")
        put("retained_remote_continuations", JsonArray(emptyList()))
    }

    @Test fun fetchedCoverageSurvivesProjectionAndStaysSeparateFromDisplayAndConsumption() {
        val row = GitHubTestChecklist.inventoryCoverage(mapOf("runs" to receipt(source()))).single().jsonObject
        assertEquals(JsonPrimitive(18), row["retrieved"])
        assertEquals(JsonPrimitive(18), row["fetched"])
        assertEquals(JsonPrimitive(3), row["displayed"])
        assertEquals(JsonNull, row["consumed"])
        assertEquals(JsonPrimitive("NOT_RECORDED"), row["consumption_status"])
        assertEquals(JsonPrimitive("retrieval complete at observation"), row["coverage"])
    }

    @Test fun aSuppressionReceiptCannotReplaceTheCanonicalPageOrResetItsCount() {
        val original = receipt(source())
        val suppression = JsonObject(original - "inventory_observation" + ("result" to buildJsonObject { put("schema", "github.repeat.v1") }))
        val row = GitHubTestChecklist.inventoryCoverage(linkedMapOf("runs" to original, "repeat" to suppression)).single().jsonObject
        assertEquals(JsonPrimitive(18), row["retrieved"])
    }

    @Test fun commitHistoryAliasAndMissingKeysAreCountedWithoutInventingCompleteness() {
        val commits = source("recent_commits")
        val row = GitHubTestChecklist.inventoryCoverage(mapOf("commits" to receipt(commits, "commits"))).single().jsonObject
        assertEquals(JsonPrimitive(18), row["retrieved"])
        val malformed = JsonObject(source() + ("workflow_runs" to JsonArray(listOf(JsonObject(emptyMap())))))
        val broken = GitHubTestChecklist.inventoryCoverage(mapOf("broken" to receipt(malformed))).single().jsonObject
        assertEquals(JsonPrimitive(1), broken["fetched"])
        assertTrue(broken["coverage"] != JsonPrimitive("retrieval complete at observation"))
    }
}
