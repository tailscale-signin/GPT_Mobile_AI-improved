package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRetrospectiveSessionAuditTest {
    private val pendingTask = "Implement the accepted fixes on the selected PR; do not merge until all checks pass."
    private val reportedQuestion = "What issues have you discovered this session that prevented you from doing what I asked?"
    private val originalReportedQuestion = "What issues have you discovered this session that prevented you from doing the work?"
    private fun wrapped(text: String) = "GitHub workspace: owner/repository\nBranch: repair-branch\n\n$text"

    @Test fun sessionRecommendationsAndTheirReportStayWithThePriorConversationTopic() {
        val question = "From this current session, what upgrades and changes would you make to the repo to make sure GitHub integration is a lot more thorough and intelligent?"
        fun user(id: Int, text: String) = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = id, chatId = 7, platformType = null, content = wrapped(text))
        val first = user(1, question)
        val followUp = user(2, "Please analyze and report.")
        assertTrue(GitHubTaskContinuity.sessionAudit(question))
        assertTrue(GitHubTaskContinuity.sessionAdvice("How would you improve GitHub integration based on our chat?"))
        assertEquals(first.content, GitHubTaskContinuity.auditTopic(listOf(first, followUp)))
        assertNull(GitHubTaskContinuity.auditTopic(listOf(first, user(2, "Review PR #9"), followUp)))
        assertNull(GitHubTaskContinuity.auditTopic(listOf(first, followUp.copy(content = followUp.content.replace("repair-branch", "other")))))
        assertNull(GitHubTaskContinuity.auditTopic(listOf(first, user(2, "Analyze and report on PR #12"))))
        assertFalse(GitHubTaskContinuity.sessionAudit("$question Then implement the changes."))
        assertFalse(GitHubTaskContinuity.sessionAudit("Based on this chat, what changes would you recommend to the app and merge them?"))
        assertTrue(GitHubTaskContinuity.reportOnly(followUp.content))
        assertFalse(GitHubTaskContinuity.reportOnly("Analyze and report on PR #12"))
        assertTrue(GitHubProgressPresentation.introduction(question).contains("this chat"))
        assertTrue(GitHubProgressPresentation.introduction(followUp.content).contains("available findings"))
    }

    @Test fun localReportEligibilityRespectsSelectedProfileAttachmentsAndAcceptedWorkflow() {
        val primary = "primary"
        val config = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(github = GitHubConversationContext("chosen", "owner", "repo", "main", "a".repeat(40), primary))
        val user = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = 8, chatId = 2, content = "Audit this GitHub session", platformType = null)
        val attached = user.copy(attachments = listOf(dev.chungjungsoo.gptmobile.data.model.ChatAttachment("/log", "/log", "log.txt", "text/plain", 2)))
        val repair = GitHubDraftRepair("owner/repo", 8, "a".repeat(40), "work", "main")
        assertEquals(user.content, GitHubTaskContinuity.localAuditTopic(listOf(user), config, primary))
        assertNull(GitHubTaskContinuity.localAuditTopic(listOf(user), config, "reviewer"))
        assertNull(GitHubTaskContinuity.localAuditTopic(listOf(attached), config, primary))
        assertNull(GitHubTaskContinuity.localAuditTopic(listOf(user.copy(platformType = primary)), config, primary))
        assertNull(GitHubTaskContinuity.localAuditTopic(listOf(user), config.copy(githubDraftRepair = repair), primary))
        assertNull(GitHubTaskContinuity.localAuditTopic(listOf(user), config, primary, staged = true))
    }

    @Test fun auditRecommendationsDependOnRecordedOutcomes() {
        fun run(status: String, error: String? = null) = dev.chungjungsoo.gptmobile.data.database.entity.AgentRun(
            runId = status, chatId = 7, userMessageId = 1, assistantMessageId = 2,
            profileUid = "test", providerSnapshot = "local", modelSnapshot = "test", status = status, terminalError = error
        )
        val healthy = listOf(run("COMPLETED"))
        val issues = run("EVIDENCE_MISSING").copy(startedAt = 100L, completedAt = 643L)
        val interrupted = healthy + run("PARTIAL", "CONTINUATION_CONTEXT_CAPACITY: private detail") + issues
        val tools = listOf(GitHubSessionToolCount("github_ci__selected", 31), GitHubSessionToolCount("github_issue__selected", 15), GitHubSessionToolCount("github_pr", 14), GitHubSessionToolCount("github_context", 15))
        assertEquals(issues, GitHubSessionAudit.longestInterruptedRun(interrupted))
        val report = GitHubSessionAudit.render(interrupted, emptyList(), null, suggestChanges = true, longestRunTools = tools)
        assertTrue(report.contains("9m 3s") && report.contains("75 recorded tool events") && report.contains("`github_ci` (31)"))
        assertFalse(report.contains("__selected"))
        assertTrue(report.contains("3 retained runs in this chat, 1 completed"))
        assertTrue(report.contains("Reserve space for the final report") && report.contains("first requirement"))
        assertFalse(report.contains("private detail"))
        assertFalse(GitHubSessionAudit.render(healthy, emptyList(), null, suggestChanges = true).contains("Reserve space"))
    }

    @Test fun retrospectiveQuestionsUseSavedDiagnosticsWithoutBecomingExecutionControls() {
        val questions = listOf(
            reportedQuestion,
            originalReportedQuestion,
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
            "$originalReportedQuestion Then merge PR #2.",
            "Which errors did you encounter in this session? Fix them now.",
            "What issues have you discovered in owner/repository?",
            "What issues have you discovered in this repository this session?",
            "What failures have you discovered in the current CI run?",
            "Why did CI stop on PR #2?",
            "Audit this session and fix the bugs.",
            "\"$reportedQuestion\"",
            "\"$originalReportedQuestion\"",
            "Use the text: $reportedQuestion",
            "Use the text: $originalReportedQuestion",
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
