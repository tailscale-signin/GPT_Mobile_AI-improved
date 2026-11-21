package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.GatewayModelCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.GatewayReasoningProbeObservation
import dev.chungjungsoo.gptmobile.data.context.GatewayReasoningProbeOutcome
import dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityUnavailableException
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningProbeReportTest {
    @Test fun missingCapabilityObservationRetainsTheRequestedModeAndLeavesTheRouteUnknown() {
        val failure = requireNotNull(providerExceptionDetails(ReasoningCapabilityUnavailableException("REASONING_CAPABILITY_UNVERIFIED", requested = ReasoningMode.OFF), partialOutput = false))
        val facts = requireNotNull(failure.failureDetails)
        assertEquals(JsonPrimitive("OFF"), facts["reasoning_requested_mode"])
        assertEquals(JsonPrimitive("NO_VERIFIED_CONTROL"), facts["reasoning_admission_basis"])
        assertEquals(JsonPrimitive("UNVERIFIED"), facts["reasoning_control_state"])
        assertEquals(JsonPrimitive("NO_GATEWAY_OBSERVATION"), facts["reasoning_probe_observation"])
        assertNull(facts["reasoning_route_kind"])
    }

    @Test fun unsupportedAndUnverifiedExceptionsRetainDistinctProbeEvidenceThroughPublicReport() {
        val profile = PlatformV2(uid = "private-profile", name = "Private profile", model = "private-model", compatibleType = ClientType.LLAMA)
        for (unsupported in listOf(true, false)) {
            val outcome = if (unsupported) GatewayReasoningProbeOutcome.IDENTICAL_PROMPTS else GatewayReasoningProbeOutcome.PROBE_UNAVAILABLE
            val observation = GatewayModelCapabilityObservation(
                reasoningControlState = if (unsupported) "UNSUPPORTED" else "UNVERIFIED",
                observationAgeMillis = 8,
                modelState = "LOADED",
                reasoningProbe = GatewayReasoningProbeObservation(outcome, if (unsupported) 2 else 1, 8000)
            )
            val error = if (unsupported) {
                ReasoningAdmissionException(ReasoningMode.ON).apply {
                    gatewayObservation = observation
                    gatewayRoute = true
                }
            } else {
                ReasoningCapabilityUnavailableException("REASONING_CAPABILITY_UNVERIFIED", observation)
            }
            val detail = requireNotNull(providerExceptionDetails(error, partialOutput = true))
            val evidence = requireNotNull(detail.failureDetails)
            assertEquals(JsonPrimitive("NOT_DISPATCHED"), evidence["attempt_dispatch_state"])
            assertNull(evidence["dispatched"])
            assertNull(evidence["remote_acceptance"])
            assertEquals(false, detail.retryable)
            assertEquals(JsonPrimitive(true), evidence["partial_output_preserved"])
            val failure = ProviderEvent.Failed(detail.message.orEmpty(), errorCode = detail.code, failureDetails = evidence)
            val fields = ProviderFailureReport.fields("private-run", failure, ProviderFailureReceipt.from(profile, "primary", failure))
            val context = IssueReportContext.fromFields("CHAT_FAILURE", evidence + fields, 1000)
            val capture = requireNotNull(context.diagnostics)
            assertEquals(JsonPrimitive(outcome.name), capture.values["reasoning_probe_outcome"])
            assertEquals(JsonPrimitive(if (unsupported) 2 else 1), capture.values["reasoning_probe_attempts"])
            assertEquals(JsonPrimitive(8000), capture.values["reasoning_probe_age_ms"])
            assertEquals(JsonPrimitive(8), capture.values["reasoning_metadata_age_ms"])
            assertNull(capture.values["reasoning_capability_age_ms"])
            assertEquals(2, capture.requiredReporterVersion())
            assertTrue(capture.isValid())
            val report = IssueReport("11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture", feature = "chat", errorCode = detail.code, context = context)
            val payload = report.approvedPayload()
            assertTrue(payload.contains(outcome.name))
            for (privateValue in listOf("private-profile", "private-model", "private-run")) assertFalse(payload.contains(privateValue))
        }
    }
}
