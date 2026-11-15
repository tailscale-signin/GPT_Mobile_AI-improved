package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubInvestigationProgressTest {
    private fun exchange(index: Int, failure: Boolean = false): AgentToolExchange {
        val call = ProviderEvent.ToolCall("call-$index", "github_read", buildJsonObject { put("path", "File$index.kt") })
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("source"), failure, errorCode = if (failure) "SAVED_VIEW_UNAVAILABLE" else null)))
    }

    @Test fun alternatingSuccessfulReadsDoNotHideRecurringPageFailures() {
        val progress = GitHubInvestigationProgress()
        repeat(6) { assertNull(progress.observe(exchange(it, it % 2 == 0))) }
        assertNotNull(progress.observe(exchange(6, true)))
    }

    @Test fun repeatedReceiptsAreNotNewEvidenceButDifferentReadsAre() {
        val progress = GitHubInvestigationProgress()
        repeat(20) { assertNull(progress.observe(exchange(it))) }
        repeat(3) { assertNull(progress.observe(exchange(19))) }
        assertNotNull(progress.observe(exchange(19)))
    }

    @Test fun repeatedPageFailureCanReplanOnceWithoutRemovingUntriedCiReads() {
        val progress = GitHubInvestigationProgress()
        val failed = (1..3).map { exchange(it, true) }
        failed.forEach(progress::observe)
        val replan = progress.replan(failed, listOf(GitHubOperationTool.definition("github_ci")))
        assertNotNull(replan)
        assertTrue(requireNotNull(replan).contains("github_ci action=runs"))
        assertTrue(replan.contains("or repeat a write", ignoreCase = true))
        repeat(3) { assertNull(progress.observe(exchange(10 + it, true))) }
        assertNotNull(progress.observe(exchange(20, true)))
        assertNull(progress.replan(failed, listOf(GitHubOperationTool.definition("github_ci"))))
    }

    @Test fun noAuthorizedAlternativeDoesNotRenewTheLoop() {
        val progress = GitHubInvestigationProgress()
        assertNull(progress.replan(listOf(exchange(1, true)), emptyList()))
        assertNull(progress.replan(listOf(exchange(1, true)), listOf(GitHubOperationTool.definition("github_write"))))
    }

    @Test fun theFirstSuccessfulRecoveryOfAFailedReadIsNewEvidence() {
        val progress = GitHubInvestigationProgress()
        repeat(3) { assertNull(progress.observe(exchange(it, true))) }
        assertNull(progress.observe(exchange(2)))
        repeat(3) { assertNull(progress.observe(exchange(2))) }
        assertNotNull(progress.observe(exchange(2)))
    }
}
