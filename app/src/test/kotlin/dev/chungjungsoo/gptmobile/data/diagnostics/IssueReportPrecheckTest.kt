package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportPrecheckTest {
    private val precheck = IssueReportContext(
        schemaVersion = 1,
        occurredAtMillis = 1L,
        source = "TOOL_RESULT",
        unavailableFields = emptyList(),
        operation = "github_read",
        stage = "tool_precheck",
        dispatched = false
    )

    @Test fun knownNativePrechecksUseExistingServiceCompatibleCategories() {
        val cases = mapOf(
            "GITHUB_REPOSITORY_TARGET_MISMATCH" to "REPOSITORY_TARGET_UNVERIFIED",
            "REPOSITORY_SCOPE_MISMATCH" to "REPOSITORY_TARGET_UNVERIFIED",
            "SAVED_VIEW_AUTHORIZATION_CHANGED" to "GITHUB_AUTHORIZATION_UNVERIFIED",
            "SAVED_VIEW_WRONG_OPERATION" to "INVALID_ARGUMENTS",
            "SAVED_VIEW_CURSOR_ALTERED" to "INVALID_ARGUMENTS",
            "WORKFLOW_SELECTION_REQUIRED" to "INVALID_ARGUMENTS",
            "SAVED_VIEW_PAGE_CAPACITY" to "OUTPUT_LIMIT",
            "EVIDENCE_UNAVAILABLE" to "GITHUB_EVIDENCE_REQUIRED",
            "GITHUB_TOOL_CONTRACT_UNSUPPORTED" to "TOOL_UNAVAILABLE",
            "GITHUB_STATE_UNAVAILABLE" to "GITHUB_OPERATION_FAILED"
        )
        for ((localCode, publicCode) in cases) {
            val projection = requireNotNull(IssueReportCodes.nativeGitHubPrecheck(IssueReportCodes.toolResult(localCode), precheck))
            assertEquals(publicCode, projection.code)
            assertTrue(projection.code in IssueReportCodes.allowed)
            assertTrue(projection.recoveryAction == null || projection.recoveryAction in IssueReportContext.recoveryActions)
        }
    }

    @Test fun unknownToolCodesAreNotClassifiedFromTheirNamesOrMessages() {
        for (code in listOf(null, "PRIVATE_REPOSITORY_FAILED", "GITHUB_REPOSITORY_TARGET_MISMATCH: private", "github_repository_target_mismatch")) {
            assertNull(IssueReportCodes.nativeGitHubPrecheck(code, precheck))
        }
    }

    @Test fun dispatchAndUncertainOutcomeCannotBeRewrittenAsAPrecheck() {
        for (context in listOf(
            precheck.copy(dispatched = true),
            precheck.copy(dispatched = null),
            precheck.copy(stage = "tool_execution"),
            precheck.copy(source = "STRUCTURED_EVENT"),
            precheck.copy(operation = "mcp"),
            precheck.copy(requiresReconciliation = true),
            precheck.copy(remoteAcceptance = "UNKNOWN"),
            precheck.copy(remoteAcceptance = "YES")
        )) {
            assertNull(IssueReportCodes.nativeGitHubPrecheck(IssueReportCodes.toolResult("GITHUB_REPOSITORY_TARGET_MISMATCH"), context))
        }
    }
}
