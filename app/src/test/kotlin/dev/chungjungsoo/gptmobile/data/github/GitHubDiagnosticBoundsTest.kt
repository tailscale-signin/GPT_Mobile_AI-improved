package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDiagnosticBoundsTest {
    @Test fun plainInterruptionDiagnosisAndRecoveryAreBoundedButImplementationKeepsItsBudget() {
        assertTrue(GitHubTaskCoverage.boundedDiagnostic("Try to analyze why these interruptions are happening."))
        assertTrue(GitHubTaskCoverage.boundedDiagnostic("Original task: analyze why these interruptions are happening. Recovery action: RETRY_FAILED_STAGE"))
        assertFalse(GitHubTaskCoverage.boundedDiagnostic("Implement cancellation tracing across all workers"))
    }

    @Test fun sourcePathDoesNotBecomeARedundantInvestigationDetailsTable() {
        val text = GitHubAnswerPresentation.render("Analyze why interruptions happen", listOf("| Source.kt · Path | Source.kt |"), emptyList(), listOf("Source excerpt"), emptyList())
        assertFalse(text.contains("Repository details"))
        assertTrue(text.contains("Source excerpt"))
    }
}
