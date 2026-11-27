package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRecoveryEvidenceTest {
    private val original = ResearchResumeRequest("action", "task", "root", "checkpoint", "RETRY_FAILED_STAGE", "profile", "evidence", "policy", 100)

    @Test fun changedFieldsDescribeTheExactGuardAndRetainedEvidenceWithoutValues() {
        val evidence = ResearchRecoveryEvidence.changed(original, original.copy(requestedStage = "RETRY_WRITING", policyRevision = "private new setting", checkpointId = "private new checkpoint", evidenceRevision = "private new evidence"), false)
        assertEquals(JsonArray(listOf("REQUESTED_STAGE", "POLICY_REVISION", "CHECKPOINT_ID", "EVIDENCE_REVISION").map(::JsonPrimitive)), evidence[ResearchRecoveryEvidence.CHANGED_FIELDS])
        assertEquals(JsonPrimitive(true), evidence[ResearchRecoveryEvidence.SAVED_ACTION_PRESERVED])
        val message = ResearchRecoveryEvidence.message(evidence)
        assertTrue(message.contains("requested stage, authorized settings changed"))
        assertFalse(message.contains("private"))
        assertFalse(evidence.toString().contains("private"))
        assertFalse(message.contains("evidence revision changed"))
    }

    @Test fun provenPolicyMigrationDoesNotClaimThatAuthorityChanged() {
        val evidence = ResearchRecoveryEvidence.changed(original, original.copy(policyRevision = "dispatch-v2:current", checkpointId = "latest"), true)
        assertEquals(JsonArray(listOf(JsonPrimitive("CHECKPOINT_ID"))), evidence[ResearchRecoveryEvidence.CHANGED_FIELDS])
        assertFalse(evidence.toString().contains("POLICY_REVISION"))
    }

    @Test fun storedDiagnosticsRejectUnboundedPrivateOrContradictoryValues() {
        val valid = ResearchRecoveryEvidence.changed(original, original.copy(policyRevision = "changed"), false)
        assertEquals(valid, ResearchRecoveryEvidence.retained(valid))
        for (invalid in listOf(
            JsonArray(emptyList()),
            JsonArray(listOf(JsonPrimitive("POLICY_REVISION"), JsonPrimitive("POLICY_REVISION"))),
            JsonArray(listOf(JsonPrimitive("private profile name"))),
            JsonPrimitive("POLICY_REVISION")
        )) {
            assertNull(ResearchRecoveryEvidence.validValue(ResearchRecoveryEvidence.CHANGED_FIELDS, invalid))
        }
        assertNull(ResearchRecoveryEvidence.validValue(ResearchRecoveryEvidence.SAVED_ACTION_PRESERVED, JsonPrimitive(false)))
        assertNull(ResearchRecoveryEvidence.validValue(ResearchRecoveryEvidence.RESOLUTION, JsonPrimitive("REPLAY_WITH_NEW_AUTHORITY")))
        assertEquals(JsonObject(emptyMap()), ResearchRecoveryEvidence.retained(JsonObject(mapOf("private" to JsonPrimitive("secret")))))
    }
}
