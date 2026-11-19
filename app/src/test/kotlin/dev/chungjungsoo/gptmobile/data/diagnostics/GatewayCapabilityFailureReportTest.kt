package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityDiscoveryException
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityFailureEvidence
import dev.chungjungsoo.gptmobile.data.network.providerTransportFailureDetails
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCapabilityFailureReportTest {
    private val profile = PlatformV2(uid = "PRIVATE_PROFILE", name = "PRIVATE_NAME", model = "PRIVATE_MODEL", compatibleType = ClientType.LLAMA)

    private fun event(error: GatewayCapabilityDiscoveryException, unknownOutcome: Boolean = false): ProviderEvent.Failed = ProviderEvent.Failed(
        if (unknownOutcome) "GATEWAY_OUTCOME_UNKNOWN: reconcile saved work" else "Capability preflight failed",
        errorCode = if (unknownOutcome) "GATEWAY_OUTCOME_UNKNOWN" else error.code,
        invocationId = "invocation-1768",
        failureDetails = providerTransportFailureDetails(
            GatewayCapabilityFailureEvidence.failureDetails(error, partialOutput = false), error, null,
            dispatched = unknownOutcome, receivedPayload = false, receivedVisibleText = false, generationCompleted = false,
            replayAllowed = false, requiresReconciliation = unknownOutcome
        )
    )

    @Test fun capabilityCauseSurvivesActualReceiptProjectionAndApprovedPayload() {
        val errors = listOf(
            GatewayCapabilityDiscoveryException("PRIVATE_ENDPOINT", java.net.SocketTimeoutException("PRIVATE_TOKEN")),
            GatewayCapabilityDiscoveryException("PRIVATE_BODY", decodeReason = "EXPECTED_BOOLEAN", fieldPath = "PRIVATE_PATH"),
            GatewayCapabilityDiscoveryException("PRIVATE_BODY", httpStatus = 403),
            GatewayCapabilityDiscoveryException("PRIVATE_BODY", code = "GATEWAY_ISOLATION_UNAVAILABLE")
        )
        for (error in errors) {
            val failure = event(error)
            val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
            assertTrue(receipt.valid())
            assertEquals(error.code, receipt.errorCode)
            assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
            assertEquals(false, receipt.dispatched)
            assertEquals(RemoteRequestAcceptance.NO, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
            val fields = failure.failureDetails.orEmpty() + ProviderFailureReport.fields("PRIVATE_RUN", failure, receipt)
            val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
            val diagnostic = requireNotNull(context.diagnostics)
            assertEquals(JsonPrimitive(error.code), diagnostic.values["provider_failure_code"])
            assertEquals(failure.failureDetails?.get(GatewayCapabilityFailureEvidence.KIND), diagnostic.values[GatewayCapabilityFailureEvidence.KIND])
            assertEquals(failure.failureDetails?.get(GatewayCapabilityFailureEvidence.DECODE_REASON), diagnostic.values[GatewayCapabilityFailureEvidence.DECODE_REASON])
            assertTrue(GatewayCapabilityFailureEvidence.FEATURE in diagnostic.requiredServiceFeatures())
            assertEquals(false, context.dispatched)
            assertEquals("NO", context.remoteAcceptance)
            val report = IssueReport("11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture", feature = "chat", errorCode = error.code, context = context)
            assertFalse(report.approvedPayload().contains("PRIVATE_"))
        }
    }

    @Test fun earlierUnknownOutcomeCannotBeReplacedByCurrentCapabilityRejection() {
        val failure = event(GatewayCapabilityDiscoveryException("PRIVATE", java.net.SocketTimeoutException()), unknownOutcome = true)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        assertEquals(true, receipt.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
        assertEquals(false, receipt.requestReplayAllowed)
        assertEquals(false, receipt.logicalContinuationAllowed)
        val projected = ProviderFailureReport.fields("run", failure, receipt)
        assertTrue(GatewayCapabilityFailureEvidence.fields.none { it in projected })
        val context = IssueReportContext.fromFields("CHAT_FAILURE", failure.failureDetails.orEmpty() + projected, 1_000L)
        assertEquals("UNKNOWN", context.remoteAcceptance)
        assertTrue(GatewayCapabilityFailureEvidence.fields.none { it in context.diagnostics?.values.orEmpty() })
    }

    @Test fun bareCapabilityFailureCannotClassifyTheRetainedRunAsUndispatched() {
        val error = GatewayCapabilityDiscoveryException("Capability read failed", java.net.SocketTimeoutException())
        val failure = ProviderEvent.Failed("Capability read failed", errorCode = error.code, failureDetails = GatewayCapabilityFailureEvidence.failureDetails(error, partialOutput = true))
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        assertNull(receipt.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
        val context = IssueReportContext.fromFields("CHAT_FAILURE", failure.failureDetails.orEmpty() + ProviderFailureReport.fields("run", failure, receipt), 1_000L)
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertTrue("dispatched" in context.unavailableFields)
        assertTrue("remoteAcceptance" in context.unavailableFields)
    }

    @Test fun restoredDiagnosticsRejectWrongCodeKindAndUnpairedDecodeEvidence() {
        val fields = mapOf("diagnostic_area" to "PROVIDER", "provider_failure_code" to "GATEWAY_CAPABILITY_UNAVAILABLE", GatewayCapabilityFailureEvidence.KIND to "DECODE_REJECTED", GatewayCapabilityFailureEvidence.DECODE_REASON to "EXPECTED_BOOLEAN")
        val sample = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1_000L))
        assertTrue(sample.isValid())
        val invalid = listOf(
            sample.values - "provider_failure_code",
            sample.values - GatewayCapabilityFailureEvidence.KIND,
            sample.values + ("provider_failure_code" to JsonPrimitive("HTTP_ERROR")),
            sample.values + (GatewayCapabilityFailureEvidence.KIND to JsonPrimitive("TIMEOUT")),
            sample.values + (GatewayCapabilityFailureEvidence.DECODE_REASON to JsonPrimitive("PRIVATE")),
            sample.values + (GatewayCapabilityFailureEvidence.KIND to JsonPrimitive("ISOLATION_UNVERIFIED"))
        )
        for (values in invalid) {
            assertNull(sample.copy(values = values).validated())
            val sanitized = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", values + ("diagnostic_area" to "PROVIDER"), 1_000L))
            assertTrue(GatewayCapabilityFailureEvidence.fields.none { it in sanitized.values })
        }
        val omitted = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER"), 1_000L)).copy(omittedFields = GatewayCapabilityFailureEvidence.fields.toList())
        assertTrue(omitted.isValid())
        assertTrue(GatewayCapabilityFailureEvidence.FEATURE in omitted.requiredServiceFeatures())
        for (code in GatewayCapabilityFailureEvidence.codes) {
            val codeOnly = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER", "provider_failure_code" to code), 1_000L))
            assertEquals(JsonObject(mapOf("provider_failure_code" to JsonPrimitive(code))), JsonObject(codeOnly.values))
            assertTrue(GatewayCapabilityFailureEvidence.FEATURE in codeOnly.requiredServiceFeatures())
        }
    }
}
