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

    private fun resolved(index: Int, connection: String = "github_api", repository: String = "repo", head: String = "a".repeat(40), tree: String = "b".repeat(40)): AgentToolExchange {
        val name = if (index % 2 == 0) "github_context__$connection" else "github_context_inspect__$connection"
        val call = ProviderEvent.ToolCall(
            "resolve-$index",
            name,
            buildJsonObject {
                put("action", "resolve")
                put(if (index % 2 == 0) "ref" else "branch", "main")
                put("verify_live", index % 2 == 0)
                put("response_detail", if (index % 3 == 0) "full" else "compact")
            }
        )
        val data = buildJsonObject {
            put("owner", "owner")
            put("repo", repository)
            put("ref", "main")
            put("commit_sha", head)
            put("tree_sha", tree)
            put("observation_sequence", index)
        }
        val receipt = buildJsonObject { put("source_result_sha256", index.toString(16).padStart(64, '0')) }
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(data), false, executionReceipt = receipt)))
    }

    @Test fun sameResolvedRevisionCannotRenewProgressThroughNewReceiptsAliasesOrDisplayOptions() {
        val progress = GitHubInvestigationProgress()
        repeat(4) { assertNull(progress.observe(resolved(it))) }
        assertNotNull(progress.observe(resolved(4)))
    }

    @Test fun changedSourceRepositoryAndConnectionRemainIndependentProgress() {
        val progress = GitHubInvestigationProgress()
        repeat(4) { assertNull(progress.observe(resolved(it))) }
        assertNull(progress.observe(resolved(4, head = "c".repeat(40))))
        repeat(3) { assertNull(progress.observe(resolved(5 + it, head = "c".repeat(40)))) }
        assertNull(progress.observe(resolved(8, repository = "another", head = "c".repeat(40))))
        repeat(3) { assertNull(progress.observe(resolved(9 + it, repository = "another", head = "c".repeat(40)))) }
        assertNull(progress.observe(resolved(12, connection = "other", repository = "another", head = "c".repeat(40))))
        assertNull(progress.observe(resolved(13, connection = "other", repository = "another", head = "c".repeat(40), tree = "d".repeat(40))))
    }

    @Test fun replanDoesNotTreatAnInspectAliasAsAnUnreadResolveAction() {
        val progress = GitHubInvestigationProgress()
        val replan = requireNotNull(progress.replan(listOf(resolved(0)), listOf(GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__github_api"))))
        assertTrue(!replan.contains("action=resolve"))
        assertTrue(replan.contains("action=checks"))
    }
}
