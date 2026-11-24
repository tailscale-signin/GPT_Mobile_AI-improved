package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRetrospectiveSessionAuditTest {
    private val pendingTask = "Implement the accepted fixes on the selected PR; do not merge until all checks pass."
    private val reportedQuestion = "What issues have you discovered this session that prevented you from doing what I asked?"
    private fun wrapped(text: String) = "GitHub workspace: owner/repository\nBranch: repair-branch\n\n$text"

    @Test fun retrospectiveQuestionsUseSavedDiagnosticsWithoutBecomingExecutionControls() {
        val questions = listOf(
            reportedQuestion,
            "Which problems did you encounter in this conversation?",
            "What blockers have you encountered in our chat?",
            "What errors did you find so far?",
            "Please what failures have you observed during the current GitHub session?"
        )
        for (question in questions) {
            for (request in listOf(question, wrapped(question))) {
                assertTrue(request, GitHubTaskContinuity.sessionAudit(request))
                assertTrue(request, GitHubTaskContinuity.diagnostic(request))
                assertTrue(request, GitHubTaskContinuity.reuse(request, pendingTask))
                assertFalse(request, GitHubTaskContinuity.reuse(request, ""))
                assertFalse(request, GitHubTaskContinuity.control(request))
                assertFalse(request, GitHubTaskContinuity.followUp(request))
                assertNull(GitHubTaskContinuity.publishedCommit(request))
                assertEquals(question, GitHubTaskContinuity.resolved(request, pendingTask))
            }
        }
    }

    @Test fun additionalActionsAndRepositoryQuestionsKeepTheirOwnRequest() {
        val requests = listOf(
            "$reportedQuestion Then merge PR #2.",
            "Which errors did you encounter in this session? Fix them now.",
            "What issues have you discovered in owner/repository?",
            "What issues have you discovered in this repository this session?",
            "What failures have you discovered in the current CI run?",
            "Why did CI stop on PR #2?",
            "Audit this session and fix the bugs.",
            "\"$reportedQuestion\"",
            "Use the text: $reportedQuestion",
            "What${" ".repeat(301)}issues have you discovered this session?"
        )
        for (request in requests) {
            for (candidate in listOf(request, wrapped(request))) {
                assertFalse(candidate, GitHubTaskContinuity.sessionAudit(candidate))
                assertFalse(candidate, GitHubTaskContinuity.diagnostic(candidate))
                assertFalse(candidate, GitHubTaskContinuity.reuse(candidate, pendingTask))
                assertEquals(request, GitHubTaskContinuity.resolved(candidate, pendingTask))
            }
        }
    }

    @Test fun existingLocalSessionAuditFormsRemainAvailable() {
        for (request in listOf(
            "List all issues in this session",
            "Audit this GitHub session",
            "Review the failures from our conversation and suggest fixes"
        )) {
            assertTrue(request, GitHubTaskContinuity.sessionAudit(wrapped(request)))
            assertTrue(request, GitHubTaskContinuity.diagnostic(wrapped(request)))
            assertTrue(request, GitHubTaskContinuity.reuse(wrapped(request), pendingTask))
            assertFalse(request, GitHubTaskContinuity.control(wrapped(request)))
        }
    }
}
