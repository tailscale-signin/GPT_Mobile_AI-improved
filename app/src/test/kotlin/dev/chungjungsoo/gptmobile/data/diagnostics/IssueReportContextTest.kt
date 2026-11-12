package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import java.util.UUID
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportContextTest {
    @Test fun projectionKeepsExactFailureFactsAndSeparatesOwnedAndBlockingJobIdentities() {
        val fields = mapOf(
            "run_id" to "exact-run",
            "invocation_id" to "exact-invocation",
            "job_id" to "owned-job",
            "active_job_id" to "blocking-job",
            "active_job_state" to "waiting_for_client_tool",
            "failed_stage" to "conversation_admission",
            "http_status" to 409,
            "retryable" to false,
            "dispatched" to false,
            "recovery_action" to "return_client_tool_results",
            "prompt" to "private prompt",
            "configured_endpoint" to "https://private.example/secret",
            "token" to "private-token"
        )
        val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_700_000_000_000)
        val raw = IssueReport.json.encodeToString(IssueReportContext.serializer(), context)

        assertEquals(409, context.httpStatus)
        assertEquals(false, context.dispatched)
        assertEquals(false, context.retryable)
        assertEquals("RETURN_CLIENT_TOOL_RESULTS", context.recoveryAction)
        assertEquals("WAITING_FOR_CLIENT_TOOL", context.jobState)
        assertEquals(IssueReportContext.identifierHash("run", "exact-run"), context.runIdHash)
        assertEquals(IssueReportContext.identifierHash("job", "owned-job"), context.jobIdHash)
        assertEquals(IssueReportContext.identifierHash("job", "blocking-job"), context.activeJobIdHash)
        for (privateValue in listOf("exact-run", "exact-invocation", "owned-job", "blocking-job", "private prompt", "private.example", "private-token")) assertFalse(raw.contains(privateValue))
        assertTrue("operation" in context.unavailableFields)
        assertFalse("dispatched" in context.unavailableFields)
    }

    @Test fun unknownFieldsTypesAndAbsoluteDeadlinesRemainExplicitlyUnavailable() {
        val context = IssueReportContext.fromFields(
            "STRUCTURED_EVENT",
            mapOf(
                "message" to "HTTP 500 private details",
                "failed_stage" to "private-stage",
                "provider" to "https://private.example",
                "tool" to "private_custom_tool",
                "http_status" to "500",
                "dispatched" to "false",
                "retryAfterMillis" to 1_900_000_000_000L,
                "missing_fields" to listOf("profile_uid", "private_field"),
                "invocation_id" to "x".repeat(257)
            ),
            1_700_000_000_000
        )
        assertEquals(IssueReportContext.optionalFields, context.unavailableFields)
        assertNull(context.httpStatus)
        assertNull(context.retryAfterMillis)
        assertNull(context.missingFields)
    }

    @Test fun availabilityEnumeratesAllAndOnlyMissingProjectedFields() {
        val context = fullContext()
        val encoded = IssueReport.json.encodeToJsonElement(IssueReportContext.serializer(), context).jsonObject
        assertEquals(IssueReportContext.optionalFields.filterNot { it in encoded }, context.unavailableFields)
        assertEquals(emptyList<String>(), context.unavailableFields)
        assertTrue(context.exceptionFingerprint!!.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun explicitUnknownCommandOutcomeCannotBeFlattenedByAWeakerFalseFlag() {
        val context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("requires_reconciliation" to false, "command_outcome_unknown" to true))
        assertEquals(true, context.requiresReconciliation)
    }

    @Test fun engineeringGuardStageDoesNotInventProviderOrDispatchEvidence() {
        val context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("failed_stage" to "engineering_guard"))
        assertEquals("engineering_guard", context.stage)
        assertNull(context.dispatched)
        assertNull(context.provider)
        assertFalse("stage" in context.unavailableFields)
        assertTrue("dispatched" in context.unavailableFields)
    }

    @Test fun typedDispatchAndRecoveryAliasesRemainBoundToTheSuppliedIncident() {
        val context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("invocationId" to "failed-invocation", "dispatchState" to "DISPATCHED", "remoteAcceptance" to "YES", "failedStage" to "gateway_recovery", "nextAction" to "RECONCILE_ACTIVE_GATEWAY_JOB"))
        assertEquals(true, context.dispatched)
        assertEquals("YES", context.remoteAcceptance)
        assertEquals("gateway_recovery", context.stage)
        assertEquals("RECONCILE_ACTIVE_GATEWAY_JOB", context.recoveryAction)
        assertEquals(IssueReportContext.identifierHash("invocation", "failed-invocation"), context.invocationIdHash)
        assertFalse("dispatched" in context.unavailableFields)
        val uncertain = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("dispatch_state" to "UNKNOWN_OUTCOME", "dispatched" to false, "requires_reconciliation" to false))
        assertNull(uncertain.dispatched)
        assertEquals(true, uncertain.requiresReconciliation)
        val absent = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("dispatchState" to "private state", "httpStatus" to 503))
        assertNull(absent.dispatched)
        assertTrue("dispatched" in absent.unavailableFields)
    }

    @Test fun oldPersistedReportsKeepTheirIdentityAndOmitTheNewContext() {
        val old = IssueReport(UUID.randomUUID().toString(), "debug", "0.9.66.7", 141, "8877f0e", "14", 34, "Device", feature = "chat", errorCode = "CHAT_FAILED")
        val raw = old.approvedPayload()
        val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), raw)
        assertEquals(old, restored)
        assertNull(restored.context)
        assertFalse(raw.contains("context"))
        assertEquals(raw, restored.approvedPayload())
    }

    @Test fun maximalUnicodeDescriptionEventsFramesAndContextFitTheBrokerByteLimit() {
        val code = IssueReportCodes.allowed.maxBy { it.length }
        val events = List(IssueReport.EVENT_LIMIT) { "-60000ms E gateway $code" }
        val metadata = IssueReport.manual("界".repeat(IssueReport.TITLE_LIMIT), "界".repeat(IssueReport.DESCRIPTION_LIMIT), events).copy(
            version = "v".repeat(48),
            android = "a".repeat(48),
            device = "d".repeat(80),
            commit = "f".repeat(40),
            build = Int.MAX_VALUE,
            sdk = 999,
            context = fullContext()
        )
        val debug = IssueReport.debug(ReportFeature.TOOLS, code, events, context = fullContext()).copy(
            version = metadata.version,
            android = metadata.android,
            device = metadata.device,
            commit = metadata.commit,
            build = metadata.build,
            sdk = metadata.sdk,
            errorType = "E".repeat(64),
            frames = List(8) { "dev.chungjungsoo.gptmobile.${"a".repeat(140)}$it:999999" }
        )
        for (report in listOf(metadata, debug)) {
            val bytes = report.approvedPayload().encodeToByteArray()
            assertTrue("Projected report must fit the unchanged broker byte limit: ${bytes.size}", bytes.size <= IssueReport.MAX_PAYLOAD_BYTES)
            assertEquals(16, report.events.size)
            assertEquals(fullContext(), report.context)
        }
        assertEquals(IssueReport.DESCRIPTION_LIMIT, metadata.description!!.length)
    }

    private fun fullContext() = IssueReportContext.fromFields(
        "STRUCTURED_EVENT",
        mapOf(
            "failed_stage" to IssueReportContext.stages.maxBy { it.length },
            "provider" to "ANTHROPIC",
            "operation" to "github_engineering",
            "http_status" to 599,
            "retry_after_ms" to 86_400_000L,
            "dispatched" to false,
            "retryable" to false,
            "resume_allowed" to false,
            "requires_reconciliation" to true,
            "remote_acceptance" to "UNKNOWN",
            "active_job_state" to "WAITING_FOR_CLIENT_TOOL",
            "recovery_action" to "RETRY_AFTER_CONFIGURATION_CHANGE",
            "reasonCode" to "SELECTION_CREDENTIAL_REFERENCE_INVALID",
            "missing_fields" to IssueReportContext.selectionFields.toList(),
            "invalid_fields" to IssueReportContext.selectionFields.toList(),
            "run_id" to "r".repeat(256),
            "invocation_id" to "i".repeat(256),
            "call_id" to "c".repeat(256),
            "job_id" to "j".repeat(256),
            "active_job_id" to "a".repeat(256)
        ),
        9_999_999_999_999L
    ).withException("IllegalStateException", listOf("dev.chungjungsoo.gptmobile.Example.call:42"))
}
