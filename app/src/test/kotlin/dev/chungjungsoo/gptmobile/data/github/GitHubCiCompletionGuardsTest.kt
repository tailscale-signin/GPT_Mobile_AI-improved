package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubCiCompletionGuardsTest {
    private val head = "a".repeat(40)
    private fun evidence() = linkedMapOf(
        "head" to receipt("github_context", buildJsonObject { put("head_sha", head) }, true),
        "runs" to receipt(
            "github_ci",
            buildJsonObject {
                put("returned", 1)
                put(
                    "workflow_runs",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", 1)
                                put("run_attempt", 1)
                                put("head_sha", head)
                                put("status", "completed")
                                put("conclusion", "success")
                            }
                        )
                    )
                )
            }
        )
    )

    private fun receipt(tool: String, result: JsonObject, headObservation: Boolean = false) = buildJsonObject {
        put("tool", tool)
        put("arguments", buildJsonObject { put("action", "runs") })
        put("trusted", true)
        put("success", true)
        put("observed_at_ms", 1L)
        put("epoch", 1L)
        put("source_head_sha", head)
        put("head_observation", headObservation)
        put("result", result)
    }

    @Test fun boundedCiFindingKeepsExistingApprovalAndUncertainMutationAsCompletionBlockers() {
        val approval = receipt(
            "github_write",
            buildJsonObject {
                put("status", "AWAITING_APPROVAL")
                put("engineering_task_id", "saved-approval")
                put("committed", false)
            }
        )
        val unknown = buildJsonObject {
            put("success", false)
            put("dispatched", true)
            put("error_code", "DISPATCH_OUTCOME_UNKNOWN")
        }
        for ((blocker, recovery) in listOf(approval to "WAITING_FOR_APPROVAL", unknown to "RECONCILE_REMOTE_OUTCOME")) {
            val receipts = evidence().apply { put("blocker", blocker) }
            val report = requireNotNull(GitHubCiObservationReport.render(GitHubWorkflowPreset.selected("diagnose_ci").prompt(), receipts, 10L, 1L)).second
            val checkpoint = GitHubExecutionLedger.attach(GitHubAcceptanceLedger.attach(report), receipts)
            assertEquals(JsonPrimitive(false), checkpoint["completion_verified"])
            assertEquals(JsonPrimitive(recovery), checkpoint.getValue("execution_ledger").jsonObject["recovery"])
        }
    }
}
