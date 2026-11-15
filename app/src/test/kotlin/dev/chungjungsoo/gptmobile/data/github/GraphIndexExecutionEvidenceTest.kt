package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphIndexExecutionEvidenceTest {
    private val attempt = "a".repeat(64)
    private val otherAttempt = "b".repeat(64)

    @Test fun restoredGraphReceiptsKeepCurrentOutcomesAndOnePriorAttemptObligation() = runTest {
        val index = receipt("index", "unknown")
        val blocked = receipt("index", "precheck")
        val status = receipt("status", "pending")
        val query = receipt("query", "unknown")
        assertEquals(GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME, GitHubExecutionLedger.receiptState(index))
        assertEquals(GitHubExecutionLedger.ReceiptState.PRECHECK_REJECTED, GitHubExecutionLedger.receiptState(blocked))
        assertEquals(GitHubExecutionLedger.ReceiptState.COMPLETED, GitHubExecutionLedger.receiptState(status))
        assertEquals(GitHubExecutionLedger.ReceiptState.FAILED, GitHubExecutionLedger.receiptState(query))
        val checkpoint = checkpoint(index, blocked, status, query)
        val ledger = checkpoint.getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive(1), ledger["unresolved_remote_outcomes"])
        assertEquals(JsonPrimitive(1), ledger["unresolved_graph_index_attempts"])
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), ledger["recovery"])
        assertEquals(JsonPrimitive(1), GitHubAcceptanceReportDiagnostics.fields(checkpoint, retainedReport = true)["unresolved_outcomes"])
        assertEquals(JsonPrimitive(false), checkpoint["completion_verified"])
    }

    @Test fun ordinaryReadFailuresAndUnsentGuardsAreNotUnknownCurrentIndexRequests() = runTest {
        val query = result("query", "resolved")
        val precheck = result("index", "precheck")
        val status = result("status", "pending")
        assertEquals("FAILED", ToolResultEnvelope.executionState(query))
        assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(precheck))
        assertFalse(precheck.dispatched)
        assertEquals("COMPLETED", ToolResultEnvelope.executionState(status))
        assertEquals(JsonPrimitive(0), checkpoint(receipt("query", "resolved")).getValue("execution_ledger").jsonObject["unresolved_remote_outcomes"])
        assertEquals(JsonPrimitive(1), checkpoint(receipt("index", "precheck")).getValue("execution_ledger").jsonObject["unresolved_remote_outcomes"])
    }

    @Test fun exactConfirmationSettlesOneAttemptWithoutClearingANewerAttempt() = runTest {
        val original = receipt("index", "unknown")
        val priorGuard = receipt("index", "precheck")
        val confirmed = receipt("status", "confirmed")
        val settled = checkpoint(original, priorGuard, confirmed).getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive(0), settled["unresolved_remote_outcomes"])
        assertEquals(JsonPrimitive(1), settled["reconciled_remote_outcomes"])
        assertEquals(2, settled.getValue("reconciliation_proofs").jsonObject.size)
        val newer = receipt("index", "unknown", otherAttempt)
        val stillPending = checkpoint(original, priorGuard, confirmed, newer).getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive(1), stillPending["unresolved_remote_outcomes"])
        assertEquals(JsonPrimitive(1), stillPending["reconciled_remote_outcomes"])
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), stillPending["recovery"])
    }

    @Test fun foreignMissingUnsentAndPayloadOnlyProofsCannotSettleAnAttempt() = runTest {
        val original = receipt("index", "unknown")
        val proof = receipt("status", "confirmed")
        assertTrue("An actual confirmation must retain its native execution receipt", proof["execution"] is JsonObject)
        fun changed(name: String, value: String) = JsonObject(proof + (name to JsonPrimitive(value)))
        fun execution(name: String, value: String) = JsonObject(proof + ("execution" to JsonObject(proof.getValue("execution").jsonObject + (name to JsonPrimitive(value)))))
        val noProof = JsonObject(proof + ("execution" to JsonObject(proof.getValue("execution").jsonObject - "confirmed_index_attempt_hash")))
        val candidates = listOf(
            changed("binding", "another-task"),
            changed("repository", "another/repository"),
            changed("branch", "another-branch"),
            changed("tool", "another_tool"),
            execution("schema", "foreign.schema"),
            execution("call_id", "another-call"),
            execution("confirmed_index_attempt_hash", otherAttempt),
            execution("confirmed_index_attempt_hash", "missing-or-invalid"),
            execution("action", "query"),
            execution("phase", "PRECHECK_REJECTED"),
            JsonObject(proof + ("trusted" to JsonPrimitive(false))),
            JsonObject(proof + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(proof + ("success" to JsonPrimitive(false))),
            JsonObject(proof + ("dispatched" to JsonPrimitive(false))),
            JsonObject(proof + ("shared_result" to JsonPrimitive(true))),
            noProof
        )
        candidates.forEachIndexed { index, candidate ->
            assertEquals("candidate $index", JsonPrimitive(1), checkpoint(original, candidate).getValue("execution_ledger").jsonObject["unresolved_remote_outcomes"])
        }
        val unbound = JsonObject(original + ("execution" to JsonObject(original.getValue("execution").jsonObject - "graph_index_attempt_hash")))
        assertEquals(JsonPrimitive(1), checkpoint(unbound, proof).getValue("execution_ledger").jsonObject["unresolved_remote_outcomes"])
    }

    @Test fun envelopeOnlyAcceptsTheMatchingNativeCurrentIndexReceipt() = runTest {
        val result = result("index", "unknown")
        assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(result))
        val execution = requireNotNull(result.executionReceipt)
        listOf("schema" to "foreign.schema", "call_id" to "foreign-call", "action" to "status", "phase" to "FAILED").forEach { (name, value) ->
            assertEquals(name, "FAILED", ToolResultEnvelope.executionState(result.copy(executionReceipt = JsonObject(execution + (name to JsonPrimitive(value))))))
        }
        assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(result.copy(dispatched = false)))
        assertEquals("COMPLETED", ToolResultEnvelope.executionState(result.copy(isError = false)))
    }

    @Test fun catalogRemovalCannotEraseHistoricalUncertaintyOrAuthorizeItsSettlement() = runTest {
        val pending = receipt("status", "pending")
        val guard = receipt("index", "precheck")
        val proof = receipt("status", "confirmed")
        val saved = listOf(pending, guard, proof).associateBy { it.getValue("receipt_id").jsonPrimitive.content }
        suspend fun restored(authorized: Boolean): Map<String, JsonObject> {
            val book = GitHubEvidenceBook(
                "task",
                "owner/repository",
                "main",
                if (authorized) setOf("repository_graph__selected") else emptySet(),
                load = { saved[it] },
                retainedIds = { saved.keys.toList() }
            )
            book.restore()
            return book.snapshot().first
        }
        val excluded = restored(false)
        excluded.values.forEach {
            assertEquals(JsonPrimitive(true), it["trusted"])
            assertEquals(JsonPrimitive(false), it["verification_eligible"])
        }
        val held = GitHubExecutionLedger.attach(buildJsonObject {}, excluded).getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive(1), held["unresolved_remote_outcomes"])
        assertEquals(JsonPrimitive(0), held["reconciled_remote_outcomes"])
        val eligible = GitHubExecutionLedger.attach(buildJsonObject {}, restored(true)).getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive(0), eligible["unresolved_remote_outcomes"])
        assertEquals(JsonPrimitive(1), eligible["reconciled_remote_outcomes"])
    }

    private fun checkpoint(vararg receipts: JsonObject) = GitHubExecutionLedger.attach(
        buildJsonObject { put("acceptance_ledger", buildJsonObject {}) },
        receipts.mapIndexed { index, receipt -> "receipt-$index" to receipt }.toMap()
    )

    private suspend fun receipt(action: String, mode: String, hash: String = attempt): JsonObject {
        val saved = linkedMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook(
            "task",
            "owner/repository",
            "main",
            setOf("repository_graph__selected"),
            save = { id, row -> saved[id] = row },
            load = { saved[it] },
            retainedIds = { saved.keys.toList() }
        )
        val result = result(action, mode, hash)
        book().observe(ProviderEvent.ToolCall(result.callId, "repository_graph__selected", buildJsonObject { put("action", action) }), result)
        val restored = book()
        restored.restore()
        return restored.snapshot().first.values.single()
    }

    private suspend fun result(action: String, mode: String, hash: String = attempt): AgentToolResult {
        val tool = RepositoryGraphTool("repository_graph__selected") {
            if (mode in setOf("unknown", "resolved", "precheck")) {
                val cause = if (mode == "precheck") GatewayGraphAdmissionException("GRAPH_INDEX_OUTCOME_UNKNOWN", "Fixture pending checkpoint", "CHECK_SAVED_INDEX") else IOException("Fixture transport boundary")
                throw GatewayGraphOperationException(
                    cause,
                    "fixture-gateway",
                    "Fixture",
                    "CHECK_SAVED_INDEX",
                    mode != "resolved",
                    indexAttemptHash = hash.takeUnless { mode == "resolved" },
                    currentIndexOutcomeUnknown = action == "index" && mode == "unknown"
                )
            }
            buildJsonObject {
                put(
                    "index_checkpoint",
                    buildJsonObject {
                        put("index_outcome_unknown", mode == "pending")
                        put("graph_index_attempt_hash", hash)
                        if (mode == "confirmed") put("confirmed_index_attempt_hash", hash)
                    }
                )
            }
        }
        return tool.execute("$action-$mode-${hash.first()}", buildJsonObject { put("action", action) })
    }
}
