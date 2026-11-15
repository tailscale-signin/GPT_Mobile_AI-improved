package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.ReplayAllowance
import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import dev.chungjungsoo.gptmobile.data.context.ToolEvidenceContextBudgetException
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import dev.chungjungsoo.gptmobile.data.network.toolEvidenceAdmissionFailure
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEvidenceAdmissionReportTest {
    private val profile = PlatformV2(uid = "PRIVATE_PROFILE_1227", name = "PRIVATE_SELECTED_PROFILE", model = "PRIVATE_MODEL_1227", compatibleType = ClientType.LLAMA)
    private val aggregateAliases = setOf(
        "dispatched", "dispatch_state", "dispatchState", "attempt_dispatch_state", "execution_dispatch_state", "executionState",
        "inference_dispatched", "generation_dispatched", "remote_acceptance", "remoteAcceptance", "http_status", "httpStatus", "statusCode"
    )
    private val unobservedSettingsAndMeasurement = setOf(
        "configured_output_limit", "effective_output_limit", "admission_configured_context_tokens", "admission_route_context_tokens",
        "admission_catalog_context_tokens", "admission_input_ceiling_tokens", "admission_output_ceiling_tokens", "measurement", "admission_measurement"
    )

    @Test fun ownedFallbackSnapshotRetainsConfigurationBesideZeroAllocatedReply() {
        val capacity = ProviderRouteCapacity.PrimaryContextDecision(Int.MAX_VALUE, null, null, null, CapacityProvenance.UNKNOWN)
        for (configuredOutput in listOf(0, 2048)) {
            val observed = ToolEvidenceContextBudgetException.Observation.capture(profile.uid, profile.model, configuredOutput, capacity, Int.MAX_VALUE)
            val (allocation, detail) = mappedObservation(Int.MAX_VALUE, 10431, 4096, observed)
            val fields = requireNotNull(detail.failureDetails)
            assertEquals(0, allocation.output)
            assertEquals(0, allocation.replay)
            assertEquals(4096, allocation.ceiling)
            assertEquals(JsonPrimitive("CONSERVATIVE_POLICY"), fields["admission_capacity_provenance"])
            assertEquals(JsonPrimitive(configuredOutput), fields["configured_output_limit"])
            assertEquals(JsonPrimitive(researchHash(profile.uid)), fields["profile_hash"])
            assertEquals(JsonPrimitive(researchHash(profile.model)), fields["model_hash"])
            assertTrue(listOf("admission_configured_context_tokens", "admission_catalog_context_tokens", "admission_route_context_tokens").none { it in fields })
            assertNoAggregateFacts(fields)
            val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1000)
            assertDispatchUnavailable(context)
            val values = approvedContext(requireNotNull(detail.code), context).getValue("diagnostics").jsonObject.getValue("values").jsonObject
            assertBudget(values, allocation, "CONSERVATIVE_POLICY")
            assertEquals(JsonPrimitive(configuredOutput), values["configured_output_limit"])
            assertEquals(JsonPrimitive(researchHash(profile.uid)), values["profile_hash"])
            assertEquals(JsonPrimitive(researchHash(profile.model)), values["model_hash"])
        }
    }

    @Test fun ownedFiniteSnapshotKeepsSourceCeilingsAfterLaterConfigurationChanges() {
        val consumed = ProviderRouteCapacity.PrimaryContextDecision(5632, 8192, 5632, 6144, CapacityProvenance.VERIFIED_ROUTE)
        val observed = ToolEvidenceContextBudgetException.Observation.capture(profile.uid, profile.model, 2048, consumed, 5632)
        val later = consumed.copy(tokens = 32768, configuredTokens = 65536, verifiedRouteTokens = 32768, catalogTokens = 49152)
        val laterProfile = profile.copy(uid = "PRIVATE_LATER_PROFILE", model = "PRIVATE_LATER_MODEL", maxTokens = 8192)
        val (allocation, detail) = mappedObservation(5632, 9043, 4096, observed)
        val fields = requireNotNull(detail.failureDetails)
        assertEquals(JsonPrimitive(8192), fields["admission_configured_context_tokens"])
        assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
        assertEquals(JsonPrimitive(6144), fields["admission_catalog_context_tokens"])
        assertEquals(JsonPrimitive(2048), fields["configured_output_limit"])
        assertFalse(fields["admission_route_context_tokens"] == JsonPrimitive(later.verifiedRouteTokens))
        assertFalse(fields["model_hash"] == JsonPrimitive(researchHash(laterProfile.model)))
        assertNoAggregateFacts(fields)
        val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1000)
        assertDispatchUnavailable(context)
        val values = approvedContext(requireNotNull(detail.code), context).getValue("diagnostics").jsonObject.getValue("values").jsonObject
        assertBudget(values, allocation, "VERIFIED_ROUTE")
        assertEquals(JsonPrimitive(8192), values["admission_configured_context_tokens"])
        assertEquals(JsonPrimitive(5632), values["admission_route_context_tokens"])
        assertEquals(JsonPrimitive(6144), values["admission_catalog_context_tokens"])
        assertEquals(JsonPrimitive(2048), values["configured_output_limit"])
        assertEquals(JsonPrimitive(researchHash(profile.uid)), values["profile_hash"])
        assertEquals(JsonPrimitive(researchHash(profile.model)), values["model_hash"])
    }

    @Test fun unboundOrMismatchedSnapshotCannotAddSettingsOrDispatchFacts() {
        val capacity = ProviderRouteCapacity.PrimaryContextDecision(4096, 4096, null, null, CapacityProvenance.DECLARED)
        val wrongBoundary = ToolEvidenceContextBudgetException.Observation.capture(profile.uid, profile.model, 2048, capacity, 2048)
        val enlargedBoundary = ToolEvidenceContextBudgetException.Observation.capture(profile.uid, profile.model, 2048, capacity.copy(tokens = 2048), 4096)
        for (observed in listOf(null, wrongBoundary, enlargedBoundary)) {
            val (allocation, detail) = mappedObservation(4096, 5000, 1024, observed)
            val fields = requireNotNull(detail.failureDetails)
            assertTrue(unobservedSettingsAndMeasurement.none { it in fields })
            assertTrue(setOf("profile_hash", "model_hash").none { it in fields })
            assertNoAggregateFacts(fields)
            val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1000)
            assertDispatchUnavailable(context)
            val serialized = approvedContext(requireNotNull(detail.code), context)
            val diagnostics = serialized.getValue("diagnostics").jsonObject
            assertBudget(diagnostics.getValue("values").jsonObject, allocation, "UNKNOWN")
            val unavailable = diagnostics.getValue("unavailableFields").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(unavailable.containsAll(setOf("profile_hash", "model_hash", "configured_output_limit")))
        }
    }

    @Test fun actualGuardExportsItsAllocationWithoutInventingDispatchOrSettings() {
        for (partial in listOf(false, true)) {
            for ((resolved, input, requested) in listOf(Triple(769, 0, 513), Triple(5632, 9043, 4096), Triple(Int.MAX_VALUE, 5000, 1024))) {
                val (allocation, detail) = mapped(resolved, input, requested, partial)
                val evidence = requireNotNull(detail.failureDetails)
                assertEquals("CONTEXT_CAPACITY_EXCEEDED", detail.code)
                assertEquals(false, detail.retryable)
                assertNull(evidence["partial_output_preserved"])
                assertEquals(JsonPrimitive(false), evidence["request_replay_allowed"])
                assertEquals(JsonPrimitive(false), evidence["logical_continuation_allowed"])
                assertNoAggregateFacts(evidence)
                assertTrue(unobservedSettingsAndMeasurement.none { it in evidence })
                assertTrue(setOf("profile_hash", "model_hash", "provider", "provider_kind").none { it in evidence })
                val provenance = if (resolved == Int.MAX_VALUE) "CONSERVATIVE_POLICY" else "UNKNOWN"
                assertBudget(evidence, allocation, provenance)
                val context = IssueReportContext.fromFields("CHAT_FAILURE", evidence, 1000)
                assertEquals("provider_admission", context.stage)
                assertEquals(false, context.retryable)
                assertDispatchUnavailable(context)
                val serialized = approvedContext(requireNotNull(detail.code), context)
                assertBudget(serialized.getValue("diagnostics").jsonObject.getValue("values").jsonObject, allocation, provenance)
            }
        }
    }

    @Test fun owningReceiptRetainsAdmissionWithoutFabricatingAggregateDispatch() {
        for (partial in listOf(false, true)) {
            val (allocation, detail) = mapped(769, 0, 513, partial)
            val failure = event(detail)
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
            assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
            assertNull(receipt.dispatched)
            assertNull(receipt.httpStatus)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
            assertNull(receipt.outputTokens)
            val fields = ProviderFailureReport.fields("PRIVATE_RUN_1227", failure, receipt)
            assertNoAggregateFacts(fields)
            assertTrue(unobservedSettingsAndMeasurement.none { it in fields })
            assertEquals(JsonPrimitive(researchHash(profile.uid)), fields["profile_hash"])
            assertEquals(JsonPrimitive(researchHash(profile.model)), fields["model_hash"])
            val context = IssueReportContext.fromFields("CHAT_FAILURE", requireNotNull(detail.failureDetails) + fields, 1000)
            assertDispatchUnavailable(context)
            val serialized = approvedContext(requireNotNull(detail.code), context)
            val values = serialized.getValue("diagnostics").jsonObject.getValue("values").jsonObject
            assertEquals(JsonPrimitive("ADMISSION"), values["provider_failure_stage"])
            assertBudget(values, allocation, "UNKNOWN")
            assertTrue(unobservedSettingsAndMeasurement.none { it in values })
        }
    }

    @Test fun retainedUnknownOutcomeDominatesAdmissionAndSurvivesTheApprovedReport() {
        for (partial in listOf(false, true)) {
            val (allocation, detail) = mapped(5632, 9043, 4096, partial)
            val retained = JsonObject(
                requireNotNull(detail.failureDetails) + buildJsonObject {
                    put("requires_reconciliation", true)
                    put("dispatched", true)
                    put("dispatch_state", "UNKNOWN_OUTCOME")
                    put("remote_acceptance", "UNKNOWN")
                }
            )
            val failure = event(detail).copy(message = "GATEWAY_OUTCOME_UNKNOWN: PRIVATE_RETAINED_OPERATION", failureDetails = retained)
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
            assertEquals(ProviderFailureStage.GENERATION, receipt.stage)
            assertEquals(true, receipt.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
            val fields = ProviderFailureReport.fields("PRIVATE_RUN_1227", failure, receipt)
            val context = IssueReportContext.fromFields("CHAT_FAILURE", retained + fields, 1000)
            assertEquals(true, context.dispatched)
            assertEquals(true, context.requiresReconciliation)
            assertEquals("UNKNOWN", context.remoteAcceptance)
            val serialized = approvedContext(requireNotNull(detail.code), context)
            assertEquals(JsonPrimitive(true), serialized["dispatched"])
            assertEquals(JsonPrimitive(true), serialized["requiresReconciliation"])
            assertEquals(JsonPrimitive("UNKNOWN"), serialized["remoteAcceptance"])
            val values = serialized.getValue("diagnostics").jsonObject.getValue("values").jsonObject
            assertEquals(JsonPrimitive("GENERATION"), values["provider_failure_stage"])
            assertBudget(values, allocation, "UNKNOWN")
        }
    }

    @Test fun zeroRequestedReplyPublishesOutputCapacityInsteadOfContextExhaustion() {
        val (allocation, detail) = mapped(4096, 0, 0, false)
        assertEquals(0, allocation.output)
        assertEquals(3277, allocation.replay)
        assertEquals("OUTPUT_CAPACITY_EXCEEDED", detail.code)
        val failure = event(detail)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
        assertEquals("OUTPUT_CAPACITY_EXCEEDED", receipt.errorCode)
        val fields = ProviderFailureReport.fields("PRIVATE_RUN_1227", failure, receipt)
        val context = IssueReportContext.fromFields("CHAT_FAILURE", requireNotNull(detail.failureDetails) + fields, 1000)
        assertDispatchUnavailable(context)
        val serialized = approvedContext("OUTPUT_CAPACITY_EXCEEDED", context)
        val values = serialized.getValue("diagnostics").jsonObject.getValue("values").jsonObject
        assertEquals(JsonPrimitive("OUTPUT_CAPACITY_EXCEEDED"), values["provider_failure_code"])
        assertBudget(values, allocation, "UNKNOWN")
    }

    @Test fun untypedIllegalStateTextCannotCreateToolEvidenceAdmission() {
        for (partial in listOf(false, true)) {
            for (prefix in listOf("CONTEXT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "ToolEvidenceContextBudgetException")) {
                assertNull(providerExceptionDetails(IllegalStateException("$prefix: PRIVATE_UNTYPED_TEXT"), partial))
            }
        }
    }

    @Test fun existingCapacityEventsKeepAdmissionWithoutAggregateDispatchAliases() {
        for (code in RequestCapacityException.CODES) {
            val evidence = buildJsonObject {
                put("failed_stage", "provider_admission")
                put("retryable", false)
                put("request_replay_allowed", false)
            }
            val failure = ProviderEvent.Failed("PRIVATE_TYPED_CAPACITY_EVENT", errorCode = code, failureDetails = evidence)
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
            assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
            assertNull(receipt.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
            val fields = ProviderFailureReport.fields("PRIVATE_RUN_1233", failure, receipt)
            assertNoAggregateFacts(fields)
            val context = IssueReportContext.fromFields("CHAT_FAILURE", evidence + fields, 1000)
            assertDispatchUnavailable(context)
            val serialized = approvedContext(code, context)
            assertEquals(JsonPrimitive("ADMISSION"), serialized.getValue("diagnostics").jsonObject.getValue("values").jsonObject["provider_failure_stage"])
        }
    }

    @Test fun capacityCodeAndUnboundStageHintCannotOverrideTheActualFailureStage() {
        for (code in RequestCapacityException.CODES) {
            for (stage in listOf(null, "provider_generation", "tool_execution")) {
                val failure = ProviderEvent.Failed(
                    "PRIVATE_TYPED_CAPACITY_EVENT",
                    errorCode = code,
                    failureDetails = buildJsonObject {
                        stage?.let { put("failed_stage", it) }
                        put("provider_failure_stage", "ADMISSION")
                    }
                )
                val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
                assertEquals(ProviderFailureStage.GENERATION, receipt.stage)
                val fields = ProviderFailureReport.fields("PRIVATE_RUN_1233", failure, receipt)
                assertEquals(JsonPrimitive("GENERATION"), fields["provider_failure_stage"])
                assertNoAggregateFacts(fields)
            }
        }
    }

    private fun mappedObservation(resolved: Int, input: Int, requested: Int, observation: ToolEvidenceContextBudgetException.Observation?): Pair<ReplayAllowance.Allocation, ErrorDetail> {
        val allocation = ReplayAllowance.allocate(resolved, input, requested, 4096)
        val failure = assertThrows(ToolEvidenceContextBudgetException::class.java) {
            ToolEvidenceContextBudgetException.requireAllocation(allocation, resolved, requested, observation)
        }
        return allocation to requireNotNull(toolEvidenceAdmissionFailure(failure))
    }

    private fun mapped(resolved: Int, input: Int, requested: Int, partial: Boolean): Pair<ReplayAllowance.Allocation, ErrorDetail> {
        val allocation = ReplayAllowance.allocate(resolved, input, requested, 4096)
        val failure = assertThrows(ToolEvidenceContextBudgetException::class.java) {
            ToolEvidenceContextBudgetException.requireAllocation(allocation, resolved, requested)
        }
        val detail = providerExceptionDetails(failure, partial)
        assertNotNull("Typed admission mapping must preserve the actual refusal", detail)
        return allocation to requireNotNull(detail)
    }

    private fun event(detail: ErrorDetail) = ProviderEvent.Failed(detail.message.orEmpty(), errorCode = detail.code, failureDetails = detail.failureDetails)

    private fun assertNoAggregateFacts(fields: Map<String, *>) {
        assertTrue("Unobserved aggregate facts: ${fields.keys.intersect(aggregateAliases)}", aggregateAliases.none { it in fields })
    }

    private fun assertDispatchUnavailable(context: IssueReportContext) {
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertNull(context.httpStatus)
        assertTrue(context.unavailableFields.containsAll(listOf("dispatched", "remoteAcceptance", "httpStatus")))
    }

    private fun assertBudget(values: Map<String, *>, allocation: ReplayAllowance.Allocation, provenance: String) {
        assertEquals(JsonPrimitive(allocation.input), values["admission_prompt_tokens"])
        assertEquals(JsonPrimitive(allocation.safety), values["admission_safety_tokens"])
        assertEquals(JsonPrimitive(allocation.output), values["admission_output_tokens"])
        assertEquals(JsonPrimitive(allocation.replay), values["admission_tool_result_reserve_tokens"])
        assertEquals(JsonPrimitive(allocation.ceiling), values["admission_capacity_tokens"])
        assertEquals(JsonPrimitive(provenance), values["admission_capacity_provenance"])
    }

    private fun approvedContext(code: String, context: IssueReportContext): JsonObject {
        assertTrue(requireNotNull(context.diagnostics).isValid())
        val report = IssueReport(
            "11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
            feature = "chat", errorCode = code, context = context
        )
        val payload = report.approvedPayload()
        assertFalse(payload.contains("PRIVATE_"))
        val serialized = IssueReport.json.parseToJsonElement(payload).jsonObject
        assertEquals(JsonPrimitive(code), serialized["errorCode"])
        return serialized.getValue("context").jsonObject.also {
            if (context.dispatched == null) assertFalse("dispatched" in it)
            if (context.remoteAcceptance == null) assertFalse("remoteAcceptance" in it)
            if (context.httpStatus == null) assertFalse("httpStatus" in it)
        }
    }
}
