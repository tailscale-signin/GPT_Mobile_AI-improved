package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubCiAbsenceTargetTest {
    private val head = "a".repeat(40)
    private val historical = "b".repeat(40)
    private val task = GitHubWorkflowPreset.selected("diagnose_ci").prompt()
    private fun receipt(id: String, action: String, data: JsonObject, target: String = head, time: Long = 1000) = buildJsonObject {
        put("receipt_id", id)
        put("trusted", true)
        put("success", true)
        put("verification_eligible", true)
        put("repository", "owner/repo")
        put("repository_role", "WRITE_DESTINATION")
        put("observed_at_ms", time)
        put("epoch", 0)
        put("tool", if (action == "resolve") "github_context_inspect__selected" else "github_ci__selected")
        put("head_observation", action == "resolve")
        if (action == "resolve") put("source_head_sha", target)
        put(
            "arguments",
            buildJsonObject {
                put("action", action)
                if (action == "runs") {
                    put("head_sha", target)
                    put("status", "failure")
                }
            }
        )
        put("result", data)
    }
    private fun completeEmpty() = buildJsonObject {
        put("workflow_runs", JsonArray(emptyList()))
        put("total_count", 0)
        put("returned", 0)
        put("page", 1)
        put("has_more", false)
        put("traversal_complete", true)
    }
    private fun sources() = linkedMapOf(
        "head" to receipt("head", "resolve", buildJsonObject { put("commit_sha", head) }),
        "absence" to receipt("absence", "runs", completeEmpty(), time = 1010)
    )
    private fun render(rows: Map<String, JsonObject>) = GitHubCiObservationReport.render(task, rows, 1100, 0, false, head, "owner/repo")

    @Test fun completeSelectedHeadAbsenceRemainsIndependentOfMalformedHistoricalPagesAndLaterOtherHeadObservation() {
        val rows = sources()
        rows["historical"] = receipt("historical", "runs", buildJsonObject { put("observationTruncated", true) }, historical, 1020)
        rows["other-head"] = receipt("other-head", "resolve", buildJsonObject { put("commit_sha", historical) }, historical, 1030)
        val report = requireNotNull(render(rows)).second
        assertEquals(JsonPrimitive(head), report["head_sha"])
        assertEquals(JsonPrimitive(true), report["completion_verified"])
        assertEquals(JsonPrimitive(false), report["build_health_verified"])
        assertEquals(JsonPrimitive(false), report["new_test_execution_verified"])
    }

    @Test fun aNewerMalformedSelectedHeadObservationOrContradictoryFailureStillBlocksCompletion() {
        val rows = sources()
        rows["new-page"] = receipt("new-page", "runs", buildJsonObject { put("observationTruncated", true) }, time = 1020)
        assertNull(render(rows))
        rows.remove("new-page")
        rows["failed"] = receipt(
            "failed",
            "runs",
            buildJsonObject {
                put(
                    "workflow_runs",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", 17)
                                put("run_attempt", 2)
                                put("head_sha", head)
                                put("status", "completed")
                                put("conclusion", "failure")
                            }
                        )
                    )
                )
                put("returned", 1)
            },
            time = 1020
        )
        assertNull(render(rows))
        rows.remove("failed")
        assertNotNull(render(rows))
        rows.remove("head")
        assertNull(render(rows))
    }
}
