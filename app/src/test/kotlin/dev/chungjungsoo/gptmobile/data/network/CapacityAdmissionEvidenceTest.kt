package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.CapacityAdmissionObservation
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot
import dev.chungjungsoo.gptmobile.data.context.PromptBudgetComponent
import dev.chungjungsoo.gptmobile.data.context.PromptBudgetReceipt
import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint
import dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityAdmissionEvidenceTest {
    private val runtime = RuntimeFingerprint("private-profile", "private-route", "private-credential", "private-model", "private-model", "private-runtime", "private-template", "private-allocation")

    @Test fun actualObservationsProjectCountsWithoutLeakingTheCapacityIdentity() {
        val capacity = CapacitySnapshot(runtime, 5632, inputTokens = 5500, outputTokens = 4096, provenance = CapacityProvenance.VERIFIED_ROUTE, observedAtMillis = 1000)
        val observation = CapacityAdmissionObservation(capacity, 5632, 32768, 8192)
        val budget = PromptBudgetReceipt(
            originalRequestLineage = "private-lineage",
            tokenizerIdentity = "private-tokenizer",
            templateIdentity = "private-template",
            toolSchemaHash = "private-schema",
            measurement = TokenMeasurementKind.UTF8_ESTIMATE,
            components = listOf(PromptBudgetComponent("system_instructions", 3000, 9000), PromptBudgetComponent("retained_history", 2000, 6000), PromptBudgetComponent("current_request", 1000, 3000), PromptBudgetComponent("tool_results", 0, 2), PromptBudgetComponent("response_schema", 20, 60), PromptBudgetComponent("serialization_and_template_estimate", 27, 81), PromptBudgetComponent("tool_schemas", 4414, 13242)),
            promptTokens = 10461,
            safetyTokens = 2615,
            toolResultReserveTokens = 0,
            attachmentReserveTokens = 0,
            outputAllowanceTokens = 4096,
            contextTokens = 5632
        )
        val failure = RequestCapacityException("CONTEXT_CAPACITY_EXCEEDED", "No inference sent", 10461, 2615, 4096, 5632, TokenMeasurementKind.UTF8_ESTIMATE, budget, observation)
        val fields = capacityAdmissionEvidence(failure, 1250)
        assertEquals(13, fields.size)
        assertEquals(JsonPrimitive(3000), fields["admission_system_tokens"])
        assertEquals(JsonPrimitive(2000), fields["admission_history_tokens"])
        assertEquals(JsonPrimitive(1000), fields["admission_current_request_tokens"])
        assertEquals(JsonPrimitive(0), fields["admission_tool_result_tokens"])
        assertEquals(JsonPrimitive(20), fields["admission_response_schema_tokens"])
        assertEquals(JsonPrimitive(27), fields["admission_envelope_tokens"])
        assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
        assertEquals(JsonPrimitive(32768), fields["admission_configured_context_tokens"])
        assertEquals(JsonPrimitive(8192), fields["admission_catalog_context_tokens"])
        assertEquals(JsonPrimitive(5500), fields["admission_input_ceiling_tokens"])
        assertEquals(JsonPrimitive(4096), fields["admission_output_ceiling_tokens"])
        assertEquals(JsonPrimitive(250), fields["admission_capacity_age_ms"])
        assertEquals(JsonPrimitive("VERIFIED_ROUTE"), fields["admission_capacity_provenance"])
        assertFalse(fields.toString().contains("private"))
    }

    @Test fun absentEvidenceIsNotReplacedWithZeroOrAClaimOfVerifiedCapacity() {
        val failure = RequestCapacityException("CONTEXT_CAPACITY_EXCEEDED", "No inference sent", 10461, 2615, 4096, 5632, TokenMeasurementKind.UTF8_ESTIMATE)
        assertTrue(capacityAdmissionEvidence(failure, 1000).isEmpty())
    }

    @Test fun futureUnknownAndOverBoundObservationsAreOmitted() {
        for ((observedAt, now) in listOf(0L to 1000L, 1001L to 1000L, 1L to 16_000_002L)) {
            val capacity = CapacitySnapshot(runtime, 5632, provenance = CapacityProvenance.UNKNOWN, observedAtMillis = observedAt)
            val observation = CapacityAdmissionObservation(capacity, configuredContextTokens = 16_000_001)
            val failure = RequestCapacityException("CONTEXT_CAPACITY_EXCEEDED", "No inference sent", 10461, 2615, 4096, 5632, TokenMeasurementKind.UTF8_ESTIMATE, capacityObservation = observation)
            assertEquals(mapOf("admission_capacity_provenance" to JsonPrimitive("UNKNOWN")), capacityAdmissionEvidence(failure, now))
        }
    }
}
