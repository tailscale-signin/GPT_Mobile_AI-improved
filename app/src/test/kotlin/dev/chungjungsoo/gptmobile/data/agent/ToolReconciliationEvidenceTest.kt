package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReconciliationEvidenceTest {
    private val arguments = buildJsonObject { put("private-path", "private-user-content") }

    private fun original(id: String = "original-call"): JsonObject {
        val call = ProviderEvent.ToolCall(id, "private-remote-tool", arguments)
        return ToolReconciliationEvidence.observation(call, ToolResultEnvelope.error(id, "DISPATCH_OUTCOME_UNKNOWN", "private exception", dispatched = true))
    }

    @Test fun blockedRetryKeepsTheOriginalIdentityWithoutDispatchingOrReplaying() {
        val evidence = original()
        val blocked = ToolReconciliationEvidence.blocked("blocked-new-call", evidence)
        val retained = requireNotNull(blocked.executionReceipt)
        assertFalse(blocked.dispatched)
        assertTrue(blocked.isError)
        assertEquals(JsonPrimitive(false), retained["request_replay_allowed"])
        assertEquals(JsonPrimitive(true), retained["requires_reconciliation"])
        assertEquals(JsonPrimitive(true), retained["reconciliation_original_dispatched"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("call", "original-call")), retained["reconciliation_original_call_hash"])
        val again = ToolReconciliationEvidence.observation(ProviderEvent.ToolCall("blocked-new-call", "private-remote-tool", arguments), blocked)
        assertEquals(evidence, again)
        assertFalse(retained.toString().contains("private-"))
        assertFalse(retained.toString().contains("original-call"))
    }

    @Test fun multipleUncertainOperationsDoNotPublishOneAsTheOnlyCause() {
        val summary = ToolReconciliationEvidence.summary(listOf(original("first"), original("second")))
        assertEquals(JsonPrimitive("MULTIPLE"), summary["reconciliation_identity_state"])
        assertEquals(JsonPrimitive(2), summary["reconciliation_tracked_operation_count"])
        assertTrue(ToolReconciliationEvidence.hashes.none { it in summary })
        val unknown = ToolReconciliationEvidence.summary(emptyList())
        assertEquals(JsonPrimitive("NOT_RECORDED"), unknown["reconciliation_identity_state"])
        assertEquals(JsonPrimitive("MULTIPLE"), ToolReconciliationEvidence.summary(listOf(original(), unknown))["reconciliation_identity_state"])
    }

    @Test fun projectionRequiresAnUnknownOutcomeAndRejectsMalformedIdentityFields() {
        val evidence = original()
        assertNull(ToolReconciliationEvidence.retained("CHAT_FAILED", evidence))
        for (bad in listOf(JsonPrimitive("raw-private-id"), Json.parseToJsonElement("1".repeat(64)))) {
            val malformed = JsonObject(evidence + ("reconciliation_original_call_hash" to bad))
            assertNull(ToolReconciliationEvidence.retained("RECONCILIATION_REQUIRED", malformed))
        }
        assertNull(ToolReconciliationEvidence.validValue("reconciliation_original_dispatched", JsonPrimitive("true")))
        assertNull(ToolReconciliationEvidence.validValue("reconciliation_tracked_operation_count", JsonPrimitive(1025)))
        assertNull(ToolReconciliationEvidence.validValue("reconciliation_original_code", JsonPrimitive("PRIVATE_EXCEPTION")))
    }
}
