package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProfileEligibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFailureReportTest {
    private val profile = PlatformV2(uid = "PRIVATE_PROFILE", name = "Private profile", model = "PRIVATE_MODEL", compatibleType = ClientType.OPENROUTER)

    private fun failure(stage: String = "provider_admission", dispatched: Boolean? = false, acceptance: String? = "NO") = ProviderEvent.Failed(
        "PRIVATE_EXPLANATION",
        errorCode = PrivacyFailureEvidence.CODE,
        failureDetails = buildJsonObject {
            put("privacy_reason_code", "NO_STRICT_ZDR_ENDPOINT")
            put("privacy_status", "INCOMPATIBLE")
            put("failed_stage", stage)
            dispatched?.let { put("dispatched", it) }
            acceptance?.let { put("remote_acceptance", it) }
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put("message", "PRIVATE_PROVIDER_BODY")
            put("api_key", "PRIVATE_CREDENTIAL")
        }
    )

    @Test fun everyClosedEligibilityReasonSurvivesTheBoundedReceiptAndApprovedProjection() {
        for (reason in PrivacyProfileEligibility.Reason.entries) {
            val status = if (reason.incompatible) "INCOMPATIBLE" else "NOT_VERIFIED"
            val event = failure().copy(
                failureDetails = buildJsonObject {
                    failure().failureDetails.orEmpty().forEach { (key, value) -> put(key, value) }
                    put("privacy_reason_code", reason.name)
                    put("privacy_status", status)
                }
            )
            val receipt = ProviderFailureReceipt.from(profile, "primary", event)
            assertTrue(receipt.valid())
            assertEquals(PrivacyFailureEvidence.CODE, receipt.errorCode)
            assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
            assertEquals(FailureDomain.APPLICATION_VERIFICATION, receipt.failureDomain)
            assertEquals(reason.name, receipt.privacyReasonCode)
            assertEquals(status, receipt.privacyStatus)
            assertEquals(false, receipt.dispatched)
            assertEquals(RemoteRequestAcceptance.NO, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
            val serializedReceipt = IssueReport.json.encodeToString(ProviderFailureReceipt.serializer(), receipt)
            assertEquals(receipt, IssueReport.json.decodeFromString(ProviderFailureReceipt.serializer(), serializedReceipt))
            val fields = event.failureDetails.orEmpty() + ProviderFailureReport.fields("PRIVATE_RUN", event, receipt)
            val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
            val diagnostic = requireNotNull(context.diagnostics)
            assertEquals(JsonPrimitive(reason.name), diagnostic.values["privacy_reason_code"])
            assertEquals(JsonPrimitive(status), diagnostic.values["privacy_status"])
            assertTrue(PrivacyFailureEvidence.FEATURE in diagnostic.requiredServiceFeatures())
            val report = IssueReport(
                "11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
                feature = "chat", errorCode = PrivacyFailureEvidence.CODE, context = context
            )
            assertFalse(serializedReceipt.contains("PRIVATE_"))
            assertFalse(report.approvedPayload().contains("PRIVATE_"))
        }
    }

    @Test fun unobservedDispatchAndPostDispatchUnknownAcceptanceAreNeverRewrittenAsUnsent() {
        for ((stage, dispatched, acceptance) in listOf(Triple("provider_admission", null, null), Triple("provider_generation", true, "UNKNOWN"))) {
            val event = failure(stage, dispatched, acceptance)
            val receipt = ProviderFailureReceipt.from(profile, "primary", event)
            assertEquals(dispatched, receipt.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
            assertEquals(if (stage == "provider_admission") ProviderFailureStage.ADMISSION else ProviderFailureStage.GENERATION, receipt.stage)
            val context = IssueReportContext.fromFields("CHAT_FAILURE", event.failureDetails.orEmpty() + ProviderFailureReport.fields("run", event, receipt), 1_000L)
            assertEquals(dispatched, context.dispatched)
            assertEquals(acceptance, context.remoteAcceptance)
            if (acceptance == null) assertTrue("remoteAcceptance" in context.unavailableFields)
            if (dispatched == null) assertTrue("dispatched" in context.unavailableFields)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
        }
    }

    @Test fun malformedPrivateReasonAndStatusAreNotCopiedOrStringified() {
        val allowed = ProviderFailureReceipt.from(profile, "primary", failure())
        for (invalid in listOf(JsonPrimitive("PRIVATE_DETAIL"), JsonPrimitive(1), JsonPrimitive(false), JsonNull)) {
            val event = failure().copy(
                failureDetails = buildJsonObject {
                    put("privacy_reason_code", invalid)
                    put("privacy_status", invalid)
                }
            )
            val receipt = ProviderFailureReceipt.from(profile, "primary", event)
            assertNull(receipt.privacyReasonCode)
            assertNull(receipt.privacyStatus)
            val fields = ProviderFailureReport.fields("run", event, receipt)
            assertFalse("privacy_reason_code" in fields)
            assertFalse("privacy_status" in fields)
        }
        assertFalse(allowed.copy(privacyReasonCode = "PRIVATE_DETAIL").valid())
        assertFalse(allowed.copy(privacyStatus = "AVAILABLE").valid())
        assertFalse(allowed.copy(errorCode = "HTTP_ERROR").valid())
        val unrelated = failure().copy(errorCode = "HTTP_ERROR")
        val other = ProviderFailureReceipt.from(profile, "primary", unrelated)
        assertNull(other.privacyReasonCode)
        assertNull(other.privacyStatus)
    }

    @Test fun sessionWrapperProjectsTheTypedEligibilityFailureWithoutBorrowingOutcomeFacts() = runBlocking {
        val event = failure("provider_generation", true, "UNKNOWN")
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(event)
        }
        val observed = withProviderFailureReceipts(delegate, "run", profile, "primary").streamRound(emptyList(), emptyList()).toList().single() as ProviderEvent.Failed
        val fields = requireNotNull(observed.failureDetails)
        assertEquals(JsonPrimitive("PROVIDER"), fields["diagnostic_area"])
        assertEquals(JsonPrimitive("GENERATION"), fields["provider_failure_stage"])
        assertEquals(JsonPrimitive("NO_STRICT_ZDR_ENDPOINT"), fields["privacy_reason_code"])
        assertEquals(JsonPrimitive(true), fields["dispatched"])
        assertEquals(JsonPrimitive("UNKNOWN"), fields["remote_acceptance"])
        val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
        assertEquals(true, context.dispatched)
        assertEquals("UNKNOWN", context.remoteAcceptance)
        assertFalse(IssueReport.json.encodeToString(IssueReportContext.serializer(), context).contains("PRIVATE_"))
    }
}
