package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApprovalRecoveryTest {
    private val head = "a".repeat(40)
    private val published = "b".repeat(40)
    private fun pending() = buildJsonObject {
        put("receipt_id", "pending-receipt")
        put("call_id", "prepare")
        put("binding", "task")
        put("repository", "owner/repo")
        put("branch", "work")
        put("tool", "github_write")
        put("trusted", true)
        put("success", true)
        put("dispatched", false)
        put(
            "arguments",
            buildJsonObject {
                put("action", "commit")
                put("branch", "work")
                put("expected_head_sha", head)
                put("stage_id", "saved-stage")
            }
        )
        put(
            "result",
            buildJsonObject {
                put("engineering_task_id", "approval-42")
                put("status", "AWAITING_APPROVAL")
                put("committed", false)
            }
        )
    }
    private fun proof(branch: String = "work") = buildJsonObject {
        put("receipt_id", "proof")
        put("binding", "task")
        put("repository", "owner/repo")
        put("tool", "github_context")
        put("trusted", true)
        put("success", true)
        put(
            "arguments",
            buildJsonObject {
                put("action", "recovery")
                put("engineering_task_id", "approval-42")
            }
        )
        put(
            "result",
            buildJsonObject {
                put("engineering_task_id", "approval-42")
                put("approval_state", "PUBLISHED")
                put("remote_state_verified", true)
                put("branch", branch)
                put("sha", published)
                put("parent_sha", head)
            }
        )
    }

    @Test fun localSuccessRetainsExactApprovalAndCannotBecomeOrdinaryContinuation() {
        val receipts = mapOf("pending" to pending())
        val checkpoint = GitHubExecutionLedger.attach(buildJsonObject { put("task_outcome", "PARTIAL") }, receipts)
        assertEquals(JsonPrimitive("WAITING_FOR_APPROVAL"), checkpoint["task_state"])
        assertEquals(JsonPrimitive(true), checkpoint["user_input_required"])
        assertEquals(JsonPrimitive(false), checkpoint["retryable_without_new_input"])
        assertEquals(GitHubExecutionLedger.ReceiptState.WAITING_FOR_APPROVAL, GitHubExecutionLedger.receiptState(pending()))
        assertEquals(GitHubExecutionLedger.ObservationKind.PENDING_APPROVAL, GitHubExecutionLedger.observationKind(pending()))
        val restored = Json.parseToJsonElement(checkpoint.toString()).jsonObject
        val approval = (restored["pending_approvals"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive("approval-42"), approval["engineering_task_id"])
        assertEquals(JsonPrimitive("github-approval-approval-42"), approval["card_id"])
        assertEquals(JsonPrimitive("saved-stage"), approval["stage_id"])
    }

    @Test fun onlyMatchingNativeRemoteProofResolvesSavedApproval() {
        assertTrue(GitHubPendingApproval.snapshot(linkedMapOf("pending" to pending(), "wrong" to proof("other"))).isNotEmpty())
        assertTrue(GitHubPendingApproval.snapshot(linkedMapOf("pending" to pending(), "foreign" to JsonObject(proof() + ("binding" to JsonPrimitive("foreign"))))).isNotEmpty())
        assertTrue(GitHubPendingApproval.snapshot(linkedMapOf("pending" to pending(), "proof" to proof())).isEmpty())
        assertNotNull(GitHubPendingApproval.publication(pending(), listOf(proof())))
    }

    @Test fun explicitResumeInspectsExistingTaskOnceAndNeverCallsCommit() {
        val tools = listOf(AgentToolDefinition("github_context", "Read context", buildJsonObject { put("type", "object") }))
        val receipts = linkedMapOf("pending-receipt" to pending())
        val next = requireNotNull(GitHubApprovalContinuation.next("resume-1", tools, receipts, receipts.keys))
        assertEquals("github_context", next.name)
        assertEquals(JsonPrimitive("recovery"), next.arguments["action"])
        assertEquals(JsonPrimitive("approval-42"), next.arguments["engineering_task_id"])
        receipts["status"] = JsonObject(pending() + mapOf("call_id" to JsonPrimitive(next.callId), "receipt_id" to JsonPrimitive("status"), "tool" to JsonPrimitive(next.name), "arguments" to next.arguments))
        assertNull(GitHubApprovalContinuation.next("resume-1", tools, receipts, receipts.keys))
        assertNotNull(GitHubApprovalContinuation.next("resume-2", tools, receipts, receipts.keys))
        assertNull(GitHubApprovalContinuation.next("resume-1", tools, mapOf("pending" to pending()), emptySet()))
    }

    @Test fun unknownMutationStillRequiresReconciliationWhileApprovalRemainsVisible() {
        val unknown = JsonObject(pending() + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("DISPATCH_OUTCOME_UNKNOWN")))
        val checkpoint = GitHubExecutionLedger.attach(JsonObject(emptyMap()), mapOf("pending" to pending(), "unknown" to unknown))
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), checkpoint.getValue("execution_ledger").jsonObject["recovery"])
        assertEquals(JsonPrimitive(true), checkpoint.getValue("execution_ledger").jsonObject["user_input_required"])
        assertFalse(checkpoint["completion_verified"] == JsonPrimitive(true))
    }

    @Test fun outcomeCategoriesKeepRemoteReadsMutationCacheAndLocalSuppressionSeparate() {
        fun observation(origin: String, result: JsonObject = JsonObject(emptyMap()), acceptedMutations: Int = 0) = buildJsonObject {
            put("success", true)
            put("trusted", true)
            put("evidence_origin", origin)
            put("result", result)
            put("execution", buildJsonObject { put("accepted_mutations", acceptedMutations) })
        }
        assertEquals(GitHubExecutionLedger.ObservationKind.REMOTE_API_SUCCESS, GitHubExecutionLedger.observationKind(observation("LIVE")))
        assertEquals(GitHubExecutionLedger.ObservationKind.MUTATION_COMPLETED, GitHubExecutionLedger.observationKind(observation("LIVE", acceptedMutations = 1)))
        assertEquals(GitHubExecutionLedger.ObservationKind.CACHE_HIT, GitHubExecutionLedger.observationKind(observation("CACHED")))
        assertEquals(GitHubExecutionLedger.ObservationKind.LOCAL_RESULT, GitHubExecutionLedger.observationKind(observation("LOCAL")))
        assertEquals(GitHubExecutionLedger.ObservationKind.SUPPRESSED, GitHubExecutionLedger.observationKind(observation("LOCAL", buildJsonObject { put("schema", "github.repeat.v1") })))
    }
}
