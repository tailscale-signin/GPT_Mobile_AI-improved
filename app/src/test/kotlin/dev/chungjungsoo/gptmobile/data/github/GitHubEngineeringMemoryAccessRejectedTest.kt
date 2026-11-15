package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringMemoryAccessRejectedTest {
    @Test fun approvedReportRetainsTheActualClosedReasonWithoutInventingDispatchOrLeakingScope() {
        val hashes = mutableSetOf<String>()
        for (phase in GitHubEngineeringMemoryAccessRejected.Phase.entries) {
            val rejected = GitHubEngineeringMemoryAccessRejected(phase, GitHubEngineeringMemoryAccessRejected.Reason.CONNECTION_MISSING)
            val fields = rejected.reportFields() + mapOf("run_id" to "PRIVATE_RUN_CANARY", "provider_kind" to "OPENROUTER")
            val capsule = IssueReportIncidentCapsule.fields(ReportFeature.TOOLS, "STRUCTURED_EVENT", GitHubEngineeringMemoryAccessRejected.ERROR_CODE, fields)
            val context = IssueReportContext.fromFields("STRUCTURED_EVENT", capsule, occurredAtMillis = 1_700_000_000_000)
            val reasonHash = researchHash(rejected.reasonCode)
            assertTrue(hashes.add(reasonHash))
            assertEquals(JsonPrimitive(reasonHash), context.diagnostics?.values?.get("original_reason_code_hash"))
            assertEquals("repository_admission", context.stage)
            assertEquals("github_engineering", context.operation)
            assertEquals("REVIEW_AUTHORIZATION", context.recoveryAction)
            assertNull(context.httpStatus)
            assertNull(context.dispatched)
            assertNull(context.remoteAcceptance)
            assertNull(context.invocationIdHash)
            assertNull(context.callIdHash)
            val (type, frames) = IssueReport.exceptionMetadata(rejected)
            val report = IssueReport(
                "75d84431-c695-4ff4-973b-3a80d63d5993", "debug", "fixture", 1, "abcdef0", "16", 36, "Fixture",
                feature = "tools", errorCode = GitHubEngineeringMemoryAccessRejected.ERROR_CODE, errorType = type,
                frames = frames, context = context.withException(type, frames)
            )
            val approved = report.approvedPayload()
            assertTrue(approved.contains(reasonHash))
            assertFalse(approved.contains("PRIVATE_RUN_CANARY"))
            assertFalse(approved.contains(rejected.reasonCode))
            assertFalse(approved.contains("PRIVATE_HISTORY_CANARY"))
            assertEquals(approved, report.approvedPayload())
        }
    }
}
