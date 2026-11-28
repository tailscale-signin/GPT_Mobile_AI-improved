package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
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

class ToolAdmissionFailureReceiptTest {
    private val profile = PlatformV2(uid = "selected-profile", name = "Selected", model = "selected-model")
    private val requiredCodes = listOf(
        "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE",
        "REQUIRED_TOOL_ACQUISITION_PENDING",
        "REQUIRED_TOOL_ACQUISITION_TIMED_OUT",
        "REQUIRED_TOOL_ACQUISITION_FAILED",
        "REQUIRED_TOOL_AMBIGUOUS_CONFIGURATION",
        "REQUIRED_TOOL_AUTHORIZATION_REQUIRED"
    )

    @Test fun knownUndispatchedToolAdmissionFailuresRetainTheirExactCauseThroughSerialization() {
        for (code in requiredCodes) {
            for (stage in listOf("tool_admission", "tool_readiness")) {
                val failure = failure(code, stage)
                val receipt = ProviderFailureReceipt.from(profile, "github-delivery", failure)
                assertEquals(code, receipt.errorCode)
                assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
                assertEquals(FailureDomain.APPLICATION_VERIFICATION, receipt.failureDomain)
                assertEquals(stage, receipt.causeStage)
                assertEquals(false, receipt.dispatched)
                assertNull(receipt.invocationId)
                assertTrue(receipt.valid())
                val encoded = Json.encodeToString(ProviderFailureReceipt.serializer(), receipt)
                assertEquals(receipt, Json.decodeFromString<ProviderFailureReceipt>(encoded))
                assertFalse(encoded.contains("private-tool"))
                assertFalse(encoded.contains("private-host"))
            }
        }
    }

    @Test fun admissionFailureCannotBorrowUsageEvenFromAnOwningRunInvocation() {
        val failure = failure().copy(invocationId = "saved-primary")
        val receipt = ProviderFailureReceipt.from(
            profile,
            "primary",
            failure,
            ProviderEvent.Usage(outputTokens = 12_000, reasoningTokens = 1_000, finishReason = "length", invocationId = "saved-primary")
        )
        assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
        assertNull(receipt.outputTokens)
        assertNull(receipt.reasoningTokens)
        assertNull(receipt.usageInvocationId)
        assertNull(receipt.outputTokenSource)
        assertEquals(ProviderFinishCategory.UNKNOWN, receipt.finishReason)
        assertFalse(receipt.outputLimitReached)
    }

    @Test fun positiveOrUncertainTransportEvidenceDoesNotBecomeALocalAdmissionRefusal() {
        val contradictory = listOf(
            buildJsonObject { put("dispatched", true) },
            buildJsonObject { put("remote_acceptance", "YES") },
            buildJsonObject { put("remote_acceptance", "ACCEPTED") },
            buildJsonObject { put("remote_acceptance", "UNKNOWN") },
            buildJsonObject { put("http_request_dispatched", true) },
            buildJsonObject { put("upstream_inference_dispatched", true) },
            buildJsonObject { put("requires_reconciliation", true) },
            buildJsonObject { put("command_outcome_unknown", true) },
            buildJsonObject { put("dispatch_state", "UNKNOWN") },
            buildJsonObject { put("dispatch_state", "UNKNOWN_OUTCOME") },
            buildJsonObject { put("dispatch_state", "DISPATCHED") }
        )
        for (stage in listOf("tool_readiness", "tool_admission")) {
            for (details in contradictory) {
                val original = failure(stage = stage)
                val receipt = ProviderFailureReceipt.from(profile, "github-delivery", original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + details)))
                assertEquals(original.errorCode, receipt.errorCode)
                assertFalse("$stage: $details", receipt.stage == ProviderFailureStage.ADMISSION)
                assertFalse("$stage: $details", receipt.failureDomain == FailureDomain.APPLICATION_VERIFICATION)
                when (details["remote_acceptance"]) {
                    JsonPrimitive("UNKNOWN") -> assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
                    JsonPrimitive("YES"), JsonPrimitive("ACCEPTED") -> assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.YES, receipt.remoteAcceptance)
                    else -> Unit
                }
            }
        }
    }

    @Test fun uncertainToolOutcomeCannotAuthorizeReplayOrContinuationDespitePositiveHints() {
        val uncertain = listOf(
            buildJsonObject { put("remote_acceptance", "UNKNOWN") },
            buildJsonObject { put("dispatch_state", "UNKNOWN") },
            buildJsonObject { put("dispatch_state", "UNKNOWN_OUTCOME") },
            buildJsonObject { put("requires_reconciliation", true) },
            buildJsonObject { put("command_outcome_unknown", true) }
        )
        val permissions = buildJsonObject {
            put("request_replay_allowed", true)
            put("logical_continuation_allowed", true)
        }
        for (stage in listOf("tool_readiness", "tool_admission")) {
            for (details in uncertain) {
                val original = failure(stage = stage)
                val receipt = ProviderFailureReceipt.from(profile, "github-delivery", original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + details + permissions)))
                assertEquals("$stage: $details", dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
                assertEquals("$stage: $details", false, receipt.requestReplayAllowed)
                assertEquals("$stage: $details", false, receipt.logicalContinuationAllowed)
                assertFalse("$stage: $details", receipt.stage == ProviderFailureStage.ADMISSION)
            }
            val original = failure(stage = stage)
            val accepted = ProviderFailureReceipt.from(profile, "github-delivery", original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + permissions + ("remote_acceptance" to JsonPrimitive("YES")))))
            assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.YES, accepted.remoteAcceptance)
            assertEquals(true, accepted.requestReplayAllowed)
            assertEquals(true, accepted.logicalContinuationAllowed)
        }
    }

    @Test fun missingTypedDispatchAndUnrelatedStagesRemainUnclassified() {
        val missingDispatch = failure().let { it.copy(failureDetails = JsonObject(it.failureDetails.orEmpty() - "dispatched")) }
        val proseOnly = ProviderEvent.Failed("No request was dispatched", errorCode = "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE")
        val unrelatedStage = failure(stage = "response_stream")
        for (failure in listOf(missingDispatch, proseOnly, unrelatedStage)) {
            val receipt = ProviderFailureReceipt.from(profile, "github-delivery", failure)
            assertFalse(receipt.stage == ProviderFailureStage.ADMISSION)
            assertEquals(FailureDomain.UNKNOWN, receipt.failureDomain)
        }
    }

    @Test fun arbitraryToolCodesCannotEnterTheClosedDiagnosticVocabulary() {
        val receipt = ProviderFailureReceipt.from(profile, "github-delivery", failure(code = "REQUIRED_TOOL_PRIVATE_HOST_SECRET"))
        assertEquals("UNCLASSIFIED_PROVIDER_FAILURE", receipt.errorCode)
        assertEquals(FailureDomain.UNKNOWN, receipt.failureDomain)
        assertTrue(receipt.valid())
        assertFalse(Json.encodeToString(ProviderFailureReceipt.serializer(), receipt).contains("PRIVATE_HOST_SECRET"))
    }

    @Test fun publicIssueProjectionPreservesTypedAdmissionCodeAndDomainWithoutPrivateInputs() {
        for (code in requiredCodes) {
            val failure = failure(code)
            val receipt = ProviderFailureReceipt.from(profile, "github-delivery", failure)
            val fields = ProviderFailureReport.fields("private-run", failure, receipt)
            val diagnostics = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000))
            assertEquals(JsonPrimitive(code), diagnostics.values["provider_failure_code"])
            assertEquals(JsonPrimitive("ADMISSION"), diagnostics.values["provider_failure_stage"])
            assertEquals(JsonPrimitive("APPLICATION_VERIFICATION"), diagnostics.values["provider_failure_domain"])
            assertFalse(diagnostics.toString().contains("private-tool"))
            assertFalse(diagnostics.toString().contains("private-host"))
        }
    }

    private fun failure(code: String = "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE", stage: String = "tool_admission") = ProviderEvent.Failed(
        "private-tool on private-host is unavailable",
        errorCode = code,
        failureDetails = buildJsonObject {
            put("failed_stage", stage)
            put("dispatched", false)
            put("retryable", false)
            put("tool", "private-tool")
            put("recovery_action", "RESTORE_AUTHORIZED_CONFIGURATION")
        }
    )
}
