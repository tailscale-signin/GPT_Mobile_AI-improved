package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderResponseTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFinalRequestFailureReportTest {
    private val profile = PlatformV2(uid = "PRIVATE_PROFILE_CANARY", name = "Private fixture", model = "PRIVATE_MODEL_CANARY", compatibleType = ClientType.OPENROUTER)

    @Test fun allActualClosedRefusalsReachTheReceiptAndApprovedReportWithoutPrivateReceiptData() {
        for (outcome in PrivacyFinalRequestFailureEvidence.outcomes) {
            val event = refusal(outcome)
            val receipt = ProviderFailureReceipt.from(profile, "primary", event)
            assertTrue(receipt.valid())
            assertEquals(PrivacyFinalRequestFailureEvidence.CODE, receipt.errorCode)
            assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
            assertEquals(FailureDomain.APPLICATION_VERIFICATION, receipt.failureDomain)
            assertEquals(outcome, receipt.privacyDispatchOutcome)
            assertEquals(false, receipt.dispatched)
            assertEquals(RemoteRequestAcceptance.NO, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
            assertFalse(receipt.transportComplete)
            val encoded = IssueReport.json.encodeToString(ProviderFailureReceipt.serializer(), receipt)
            assertEquals(receipt, IssueReport.json.decodeFromString(ProviderFailureReceipt.serializer(), encoded))
            val context = projected(event, receipt)
            val diagnostic = requireNotNull(context.diagnostics)
            assertEquals(JsonPrimitive(outcome), diagnostic.values[PrivacyFinalRequestFailureEvidence.FIELD])
            assertTrue(PrivacyFinalRequestFailureEvidence.FEATURE in diagnostic.requiredServiceFeatures())
            assertFalse(PrivacyFailureEvidence.FEATURE in diagnostic.requiredServiceFeatures())
            assertEquals(diagnostic, IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), diagnostic)))
            val report = report(context)
            for (text in listOf(encoded, report.approvedPayload())) {
                assertFalse(text.contains("PRIVATE_"))
                for (field in listOf("privacy_receipt", "payload_hmac", "private_request_id", "known_private_values")) assertFalse(field, text.contains(field))
            }
        }
    }

    @Test fun absentMalformedSuccessOrMismatchedEvidenceNeverCreatesALocalBlockProof() {
        val original = refusal()
        val invalid = listOf(JsonPrimitive("KNOWN_VALUES_ABSENT"), JsonPrimitive("PRIVATE_OUTCOME"), JsonPrimitive(1), JsonPrimitive(false), JsonNull)
        val variants = invalid.map { original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + (PrivacyFinalRequestFailureEvidence.FIELD to it))) } +
            listOf(
                original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() - PrivacyFinalRequestFailureEvidence.FIELD)),
                original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + ("failed_stage" to JsonPrimitive("provider_generation")))),
                original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + ("request_replay_allowed" to JsonPrimitive("false")))),
                original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + ("logical_continuation_allowed" to JsonPrimitive("false")))),
                original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() - "logical_continuation_allowed"))
            )
        for (event in variants) {
            val receipt = ProviderFailureReceipt.from(profile, "primary", event)
            assertNull(receipt.privacyDispatchOutcome)
            assertEquals(FailureDomain.UNKNOWN, receipt.failureDomain)
            assertEquals(ProviderFailureStage.GENERATION, receipt.stage)
            assertTrue(receipt.valid())
            val fields = ProviderFailureReport.fields("run", event, receipt)
            assertFalse(PrivacyFinalRequestFailureEvidence.FIELD in fields)
            assertFalse(PrivacyFinalRequestFailureEvidence.FIELD in requireNotNull(projected(event, receipt).diagnostics).values)
        }
        val unrelated = original.copy(errorCode = "HTTP_ERROR")
        assertNull(ProviderFailureReceipt.from(profile, "primary", unrelated).privacyDispatchOutcome)
        assertNull(PrivacyFinalRequestFailureEvidence.observedOutcome("HTTP_ERROR", original.failureDetails))
    }

    @Test fun restoredReceiptAndDiagnosticCannotMoveTheOutcomeToAnotherCodeOrDomain() {
        val receipt = ProviderFailureReceipt.from(profile, "primary", refusal())
        assertFalse(receipt.copy(errorCode = "HTTP_ERROR").valid())
        assertFalse(receipt.copy(failureDomain = FailureDomain.PROVIDER).valid())
        assertFalse(receipt.copy(stage = ProviderFailureStage.GENERATION).valid())
        assertFalse(receipt.copy(privacyDispatchOutcome = "KNOWN_VALUES_ABSENT").valid())
        assertFalse(receipt.copy(requestReplayAllowed = true).valid())
        val capture = requireNotNull(projected(refusal(), receipt).diagnostics)
        for ((key, value) in listOf("provider_failure_code" to "HTTP_ERROR", "provider_failure_stage" to "GENERATION", "provider_failure_domain" to "PROVIDER")) {
            val altered = capture.copy(values = capture.values + (key to JsonPrimitive(value)))
            assertNull(altered.validated())
            assertNull(IssueReportDiagnostics.fromJson(IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), altered)))
        }
        for (area in listOf("TOOL", "VOICE", "APP", "GATEWAY", "ACCEPTANCE", "INCIDENT")) {
            val other = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to area, PrivacyFinalRequestFailureEvidence.FIELD to "KNOWN_VALUE_REINTRODUCED"), 1_000L))
            assertFalse(PrivacyFinalRequestFailureEvidence.FIELD in other.values)
        }
    }

    @Test fun unsentFinalRefusalDoesNotBorrowAnUnboundEarlierUsageFrame() {
        val receipt = ProviderFailureReceipt.from(profile, "primary", refusal(), ProviderEvent.Usage(outputTokens = 42, reasoningTokens = 11))
        assertNull(receipt.outputTokens)
        assertNull(receipt.reasoningTokens)
        assertEquals(ProviderFinishCategory.UNKNOWN, receipt.finishReason)
    }

    @Test fun receiptWrapperCannotMergeLaterFalseAndNoBackOverPriorUnknownWork() = runBlocking {
        for (unknown in listOf("TOOL_OUTCOME_UNKNOWN", "GATEWAY_OUTCOME_UNKNOWN", "RECONCILIATION_FLAG", "UNKNOWN_ACCEPTANCE")) {
            val event = refusal().copy(
                message = "$unknown: retained earlier work",
                failureDetails = buildJsonObject {
                    refusal().failureDetails.orEmpty().forEach { (key, value) -> put(key, value) }
                    if (unknown == "RECONCILIATION_FLAG") put("requires_reconciliation", true)
                    if (unknown == "UNKNOWN_ACCEPTANCE") put("remote_acceptance", "UNKNOWN")
                }
            )
            val observed = withProviderFailureReceipts(Session(listOf(event)), "run", profile, "primary")
                .streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
            val fields = requireNotNull(observed.failureDetails)
            assertEquals(JsonPrimitive("UNKNOWN"), fields["remote_acceptance"])
            assertEquals(if (unknown == "GATEWAY_OUTCOME_UNKNOWN") JsonPrimitive(true) else JsonNull, fields["dispatched"])
            val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
            assertEquals("UNKNOWN", context.remoteAcceptance)
            assertTrue(context.dispatched != false)
            if (unknown != "GATEWAY_OUTCOME_UNKNOWN") assertTrue("dispatched" in context.unavailableFields)
            assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
            assertEquals(JsonPrimitive(false), fields["logical_continuation_allowed"])
            assertFalse(report(context).approvedPayload().contains("PRIVATE_"))
        }
    }

    @Test fun receiptWrapperRetainsActualEarlierOwningStreamHeadersAndUsage() = runBlocking {
        for ((status, retainedAcceptance, acceptance) in listOf(
            Triple(200, "NO", "YES"),
            Triple(429, "NO", "NO"),
            Triple(429, "UNKNOWN", "UNKNOWN"),
            Triple(429, "YES", "YES"),
            Triple(200, "UNKNOWN", "YES")
        )) {
            val refusal = refusal().let {
                it.copy(failureDetails = JsonObject(it.failureDetails.orEmpty() + ("remote_acceptance" to JsonPrimitive(retainedAcceptance))))
            }
            val source = Session(
                listOf(
                    ProviderEvent.Usage(outputTokens = 13, providerResponseTiming = ProviderResponseTiming(httpStatus = status, serverRequestId = "earlier-owned-request")),
                    refusal
                )
            )
            val event = withContext(RequestDispatchContext(emptySet(), "run", "owned-invocation")) {
                withProviderFailureReceipts(source, "run", profile, "primary").streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
            }
            val fields = requireNotNull(event.failureDetails)
            assertEquals(JsonPrimitive(true), fields["dispatched"])
            assertEquals(JsonPrimitive(acceptance), fields["remote_acceptance"])
            assertEquals(JsonPrimitive(status), fields["http_status"])
            assertEquals(JsonPrimitive(13), fields["output_tokens"])
            assertEquals(JsonPrimitive("ADMISSION"), fields["provider_failure_stage"])
            assertEquals(JsonPrimitive("APPLICATION_VERIFICATION"), fields["provider_failure_domain"])
            assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
            assertEquals(JsonPrimitive(false), fields["logical_continuation_allowed"])
            assertFalse(event.transportComplete)
            assertNull(fields["generation_completed"])
            assertNull(fields["provider_wait_decision_state"])
            assertNull(fields["retry_delay_source"])
            val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
            assertEquals(true, context.dispatched)
            assertEquals(acceptance, context.remoteAcceptance)
            assertFalse(report(context).approvedPayload().contains("earlier-owned-request"))
        }
    }

    @Test fun receiptWrapperOmitsAClosedOutcomeWithTheWrongValidationStage() = runBlocking {
        val event = refusal().let { it.copy(failureDetails = JsonObject(it.failureDetails.orEmpty() + ("failed_stage" to JsonPrimitive("provider_generation")))) }
        val observed = withProviderFailureReceipts(Session(listOf(event)), "run", profile, "primary")
            .streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
        assertFalse(PrivacyFinalRequestFailureEvidence.FIELD in observed.failureDetails.orEmpty())
        assertEquals(JsonPrimitive("UNKNOWN"), observed.failureDetails?.get("provider_failure_domain"))
    }

    private fun refusal(outcome: String = "KNOWN_VALUE_REINTRODUCED") = ProviderEvent.Failed(
        "PRIVATE_MESSAGE_CANARY",
        errorCode = PrivacyFinalRequestFailureEvidence.CODE,
        taskOutcome = "BLOCKED",
        failureDetails = buildJsonObject {
            put("failed_stage", "request_validation")
            put(PrivacyFinalRequestFailureEvidence.FIELD, outcome)
            put("dispatched", false)
            put("remote_acceptance", "NO")
            put("dispatch_state", "PRECHECK_REJECTED")
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put(
                "privacy_receipt",
                buildJsonObject {
                    put("private_request_id", "PRIVATE_REQUEST_CANARY")
                    put("payload_hmac", "PRIVATE_HMAC_CANARY")
                    put("known_private_values", "PRIVATE_VALUE_CANARY")
                }
            )
        }
    )

    private fun projected(event: ProviderEvent.Failed, receipt: ProviderFailureReceipt): IssueReportContext =
        IssueReportContext.fromFields("CHAT_FAILURE", event.failureDetails.orEmpty() + ProviderFailureReport.fields("PRIVATE_RUN_CANARY", event, receipt), 1_000L)

    private fun report(context: IssueReportContext) = IssueReport(
        "11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
        feature = "chat", errorCode = PrivacyFinalRequestFailureEvidence.CODE, context = context
    )

    private class Session(private val events: List<ProviderEvent>) : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = events.asFlow()
    }
}
