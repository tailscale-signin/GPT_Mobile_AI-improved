package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivacyFinalRequestIncidentTest {
    @Before
    @After
    fun reset() {
        IssueReportIncidents.initialize(ApplicationProvider.getApplicationContext<Context>())
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
    }

    @Test fun finalRequestEvidenceStillRequiresTheExistingCapturePermission() {
        val fields = fields()
        IssueReportIncidents.chatFailure(PrivacyFinalRequestFailureEvidence.CODE, false, null, fields)
        assertTrue(IssueReportIncidents.pending.value.isEmpty())
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.privacyChanged(true)
        IssueReportIncidents.chatFailure(PrivacyFinalRequestFailureEvidence.CODE, false, null, fields)
        assertTrue(IssueReportIncidents.pending.value.isEmpty())
        assertTrue(IssueReportIncidents.recentEvents().isEmpty())
        assertNull(IssueReportIncidents.diagnosticSnapshot("PROVIDER"))
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.chatFailure(PrivacyFinalRequestFailureEvidence.CODE, false, null, fields)
        val report = IssueReportIncidents.pending.value.single()
        assertEquals(PrivacyFinalRequestFailureEvidence.CODE, report.errorCode)
        val context = requireNotNull(report.context)
        assertEquals("request_validation", context.stage)
        assertEquals(false, context.dispatched)
        assertEquals("NO", context.remoteAcceptance)
        val capture = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), capture.values[PrivacyFinalRequestFailureEvidence.FIELD])
        assertEquals(JsonPrimitive("ADMISSION"), capture.values["provider_failure_stage"])
        assertEquals(JsonPrimitive("APPLICATION_VERIFICATION"), capture.values["provider_failure_domain"])
        assertTrue(PrivacyFinalRequestFailureEvidence.FEATURE in capture.requiredServiceFeatures())
        assertFalse(PrivacyFailureEvidence.FEATURE in capture.requiredServiceFeatures())
        assertFalse(report.approvedPayload().contains("PRIVATE_"))
        assertFalse(report.approvedPayload().contains("privacy_receipt"))
    }

    @Test fun incidentProjectionDoesNotPromoteAMismatchedStageOrStringBoolean() {
        for (invalid in listOf(
            mapOf("failed_stage" to "provider_generation"),
            mapOf("logical_continuation_allowed" to "false"),
            mapOf(PrivacyFinalRequestFailureEvidence.FIELD to "KNOWN_VALUES_ABSENT", "provider_failure_stage" to "ADMISSION", "provider_failure_domain" to "APPLICATION_VERIFICATION", "failure_origin" to "APPLICATION")
        )) {
            reset()
            LocalDiagnosticsPolicy.setEnabled(true)
            IssueReportIncidents.chatFailure(PrivacyFinalRequestFailureEvidence.CODE, false, null, fields() + invalid)
            val capture = requireNotNull(IssueReportIncidents.pending.value.single().context?.diagnostics)
            assertFalse(PrivacyFinalRequestFailureEvidence.FIELD in capture.values)
            assertFalse(capture.values["provider_failure_domain"] == JsonPrimitive("APPLICATION_VERIFICATION"))
            assertFalse(capture.values["provider_failure_stage"] == JsonPrimitive("ADMISSION"))
        }
    }

    private fun fields(): Map<String, Any?> = mapOf(
        "run_id" to "PRIVATE_RUN_CANARY", "failed_stage" to "request_validation", "provider_kind" to "OPENROUTER",
        PrivacyFinalRequestFailureEvidence.FIELD to "KNOWN_VALUE_REINTRODUCED", "dispatched" to false, "remote_acceptance" to "NO",
        "request_replay_allowed" to false, "logical_continuation_allowed" to false,
        "privacy_receipt" to mapOf("private_request_id" to "PRIVATE_REQUEST_CANARY", "payload_hmac" to "PRIVATE_HMAC_CANARY", "known_private_values" to "PRIVATE_VALUE_CANARY")
    )
}
