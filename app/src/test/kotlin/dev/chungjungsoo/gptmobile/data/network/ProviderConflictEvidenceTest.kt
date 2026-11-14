package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConflictEvidenceTest {
    @Test fun explicitConflictEvidencePreservesOnlyBoundedObservations() {
        val fingerprint = "a".repeat(64)
        val source = json("""{"remote_acceptance":"NO","resume_allowed":false,"requires_reconciliation":true,"active_job_provenance":"CONVERSATION_ACTIVE_JOB","pending_client_tool_state":"IDENTIFIED","pending_client_tool_count":2,"pending_client_tool_ids_hash":"$fingerprint","client_tool_handoff_sequence":3,"pending_client_tool_call_ids":["private-call"],"arguments":"private arguments","active_job_id":"private-job"}""")
        val evidence = providerConflictEvidence(source)
        assertEquals(8, evidence.size)
        assertEquals(JsonPrimitive("NO"), evidence["remote_acceptance"])
        assertEquals(JsonPrimitive(false), evidence["resume_allowed"])
        assertEquals(JsonPrimitive(true), evidence["requires_reconciliation"])
        assertEquals(JsonPrimitive("CONVERSATION_ACTIVE_JOB"), evidence["active_job_provenance"])
        assertEquals(JsonPrimitive("IDENTIFIED"), evidence["pending_client_tool_state"])
        assertEquals(JsonPrimitive(2), evidence["pending_client_tool_count"])
        assertEquals(JsonPrimitive(fingerprint), evidence["pending_client_tool_ids_hash"])
        assertEquals(JsonPrimitive(3), evidence["client_tool_handoff_sequence"])
        assertFalse(evidence.toString().contains("private"))
        assertFalse(evidence.containsKey("job_id"))
    }

    @Test fun absentEvidenceDoesNotInventAZeroCountOrResumePermission() {
        assertTrue(providerConflictEvidence(null).isEmpty())
        assertTrue(providerConflictEvidence(json("""{"active_job_state":"WAITING_FOR_CLIENT_TOOL","dispatch_state":"not_dispatched"}""")).isEmpty())
        val missing = providerConflictEvidence(json("""{"pending_client_tool_state":"MISSING"}"""))
        assertEquals(mapOf("pending_client_tool_state" to JsonPrimitive("MISSING")), missing)
        val empty = providerConflictEvidence(json("""{"pending_client_tool_state":"EMPTY","pending_client_tool_count":0}"""))
        assertEquals(JsonPrimitive(0), empty["pending_client_tool_count"])
        assertFalse(empty.containsKey("resume_allowed"))
    }

    @Test fun untypedOrPrivateServerValuesCannotEnterTheEvidence() {
        val source = json("""{"remote_acceptance":false,"resume_allowed":"false","requires_reconciliation":"true","active_job_provenance":"private owner","pending_client_tool_state":"private call","pending_client_tool_count":"2","pending_client_tool_ids_hash":"private-id","client_tool_handoff_sequence":true}""")
        assertTrue(providerConflictEvidence(source).isEmpty())
        assertTrue(providerConflictEvidence(json("""{"remote_acceptance":"ACCEPTED","resume_allowed":1,"requires_reconciliation":0,"pending_client_tool_ids_hash":${"1".repeat(64)}}""")).isEmpty())
    }

    @Test fun countsAndHandoffSequencesHaveIndependentBounds() {
        for (count in listOf(-1, 129, Int.MAX_VALUE)) {
            assertFalse(providerConflictEvidence(json("""{"pending_client_tool_count":$count}""")).containsKey("pending_client_tool_count"))
        }
        for (count in listOf(0, 1, 128)) {
            assertEquals(JsonPrimitive(count), providerConflictEvidence(json("""{"pending_client_tool_count":$count}"""))["pending_client_tool_count"])
        }
        for (sequence in listOf(0, -1, 16_000_001)) {
            assertFalse(providerConflictEvidence(json("""{"client_tool_handoff_sequence":$sequence}""")).containsKey("client_tool_handoff_sequence"))
        }
        for (sequence in listOf(1, 16_000_000)) {
            assertEquals(JsonPrimitive(sequence), providerConflictEvidence(json("""{"client_tool_handoff_sequence":$sequence}"""))["client_tool_handoff_sequence"])
        }
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
