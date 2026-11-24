package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProgressSummaryRegressionTest {
    @Test fun differentFindingsFromTheSameSourceAreAdmittedButFormattedRepeatsAreNot() {
        var clock = 0L
        val progress = GitHubChatProgress("Review cancellation") { clock }
        progress.tick()
        complete(progress, "source", "github_read", """{"path":"src/Reader.kt"}""")
        val finding = ApiState.ActivitySummary(
            "Reader.kt retries before recording the response. I’ll check cancellation next.",
            modelAuthored = true,
            invocationId = "first-analysis"
        )
        progress.forward(finding)
        clock = 45_000
        assertEquals(finding, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())

        val correction = ApiState.ActivitySummary(
            "Cancellation reaches Reader.kt through its parent job. The missing guard is in Writer.kt; I’ll inspect that call next.",
            modelAuthored = true,
            invocationId = "corrected-analysis"
        )
        assertEquals(null, progress.forward(correction))
        clock = 180_000
        assertEquals(correction, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())

        clock = 315_000
        val repeat = finding.copy(text = "**Reader.kt** retries before recording the response. I will check cancellation next.")
        assertEquals(null, progress.forward(repeat))
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        assertEquals(correction.text, progress.heartbeat())
    }

    @Test fun activityOnlyModelTextDoesNotCreateOrDisplaceAFinding() {
        var clock = 0L
        val progress = GitHubChatProgress("Review the issues") { clock }
        progress.tick()
        clock = 45_000
        listOf("I'm still reviewing the latest results.", "I will continue checking the repository. Work is in progress.").forEach {
            assertEquals(null, progress.forward(ApiState.ActivitySummary(it, modelAuthored = true)))
        }
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })

        val finding = ApiState.ActivitySummary("Issue #2440 is still open; its report identifies the export continuation path.", modelAuthored = true)
        assertEquals(finding, progress.forward(finding))
        clock = 180_000
        assertEquals(null, progress.forward(ApiState.ActivitySummary("I’m reviewing the latest results.", modelAuthored = true)))
        assertEquals(finding.text, progress.heartbeat())
        val withActivityPrefix = ApiState.ActivitySummary(
            "I have completed 3 repository operations. Reader.kt retries before saving the response; I found the race.",
            modelAuthored = true
        )
        assertEquals(withActivityPrefix, progress.forward(withActivityPrefix))
    }

    @Test fun everyResourceKeepsItsIdentityAndStateWithoutTurningHistoricalTitlesIntoBlockers() {
        var clock = 0L
        val progress = GitHubChatProgress("Review open issues") { clock }
        progress.tick()
        fun resources(id: String, other: Int, state: String) {
            val call = ProviderEvent.ToolCall(id, "github_issue", json("""{"action":"list"}"""))
            val content = json(
                """{"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_issue","resource_observations":[{"resource":"issue","number":74,"title":"Build failure: Manual request on refs/heads/main","state":"closed"},{"resource":"issue","number":$other,"title":"Export continuation","state":"$state"}]}}"""
            )
            progress.observeResult(call, AgentToolResult(id, ToolResultContent.Json(content), false))
            complete(progress, id, call.name, call.arguments.toString())
        }
        resources("first-page", 2440, "open")
        clock = 45_000
        val first = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(first.contains("Issue #74 is closed") && first.contains("Issue #2440 is open"))
        assertFalse(first.contains("needs attention") || first.contains("Other captured resources"))

        resources("changed-page", 2441, "open")
        clock = 180_000
        val changed = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(changed.contains("Issue #2441 is open"))
        assertFalse(changed.contains("Issue #74") || changed.contains("Issue #2440"))

        resources("closed-issue", 2441, "closed")
        clock = 315_000
        val closed = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(closed.contains("Issue #2441 is closed"))
        assertFalse(closed.contains("needs attention") || closed.contains("fixed") || closed.contains("tests passed"))

        resources("reopened-issue", 2441, "open")
        clock = 450_000
        val reopened = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(reopened.contains("Issue #2441 is open"))
        assertFalse(reopened.contains("Issue #2441 is closed"))
    }

    @Test fun routineIssueReadsDoNotRepeatAnOldBlockerButRecoveryStillPublishes() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect issues") { clock }
        progress.tick()
        val args = """{"issue_number":2432}"""
        complete(progress, "failed", "github_issue", args, "tool_failed")
        assertTrue(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text.contains("failed"))

        complete(progress, "unrelated", "github_issue", """{"issue_number":2435}""")
        clock = 45_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })

        complete(progress, "recovered", "github_issue", args)
        val recovered = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(recovered.contains("Issue #2432") && recovered.contains("available again"))
        assertFalse(recovered.contains("returned a result for inspection"))
    }

    @Test fun recurringResourceStateCanBeExplainedAgainAfterFreshTransitions() {
        var clock = 0L
        val progress = GitHubChatProgress("Track the reported issue") { clock }
        progress.tick()
        listOf("open", "closed", "open", "closed", "open").forEachIndexed { index, state ->
            val call = ProviderEvent.ToolCall("issue-$index", "github_issue", json("""{"issue_number":2440}"""))
            val content = json("""{"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_issue","resource_observations":[{"resource":"issue","number":2440,"title":"Export continuation","state":"$state"}]}}""")
            progress.observeResult(call, AgentToolResult(call.callId, ToolResultContent.Json(content), false))
            complete(progress, call.callId, call.name, call.arguments.toString())
            val prose = ApiState.ActivitySummary("Issue #2440 is $state. I’ll compare its latest report with the export continuation path.", modelAuthored = true)
            progress.forward(prose)
            clock = 45_000 + index * 135_000L
            assertEquals(prose, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())
            assertEquals(1, progress.summarySnapshot().recentSteps.count { it.startsWith("Issue #2440") })
        }
    }

    @Test fun summaryComparisonKeepsChangedJobFilePullRequestAndNegativeOutcome() {
        val original = "**Job 7** failed in `src/Reader.kt`; PR #12 still needs review."
        val key = GitHubProgressPresentation.summaryKey(original)
        assertEquals(key, GitHubProgressPresentation.summaryKey("Job 7 failed in src/Reader.kt; PR #12 still needs review."))
        listOf(
            original.replace("Job 7", "Job 8"),
            original.replace("Reader.kt", "Writer.kt"),
            original.replace("PR #12", "PR #13"),
            original.replace("failed", "did not fail")
        ).forEach { assertNotEquals(key, GitHubProgressPresentation.summaryKey(it)) }
        assertNotEquals(
            GitHubProgressPresentation.summaryKey("pkg/__init__.py is missing."),
            GitHubProgressPresentation.summaryKey("pkg/init.py is missing.")
        )
    }

    @Test fun quotedCheckCountsCannotBecomeStatisticsAndSavedSourceNamesCannotBecomeFailures() {
        fun render(step: String) = GitHubRichSummaryPresentation.render(
            GitHubProgressSnapshot(1, "Review issues", 1, 0, "Reviewing", listOf(step), "Inspect the source")
        )
        val quoted = render("Issue #74 is closed: “Recorded checks: 2 successful, 1 skipped, 0 in progress, 0 failed (complete captured list)”.")
        assertFalse(quoted.contains("| Check state |") || quoted.contains("needs attention"))
        assertTrue(GitHubRichSummaryPresentation.chartData(quoted).isEmpty())
        assertEquals(null, GitHubRichSummaryPresentation.csv(quoted))
        val savedSource = render("From saved results: i have lines 1–4 of Failure.kt. Current state still needs verification.")
        assertTrue(savedSource.contains("Failure.kt") && savedSource.contains("Current state still needs verification"))
        assertFalse(savedSource.contains("needs attention") || savedSource.contains("Trouble with"))
        val savedChecks = render("From saved results: recorded checks for Job 7: 2 successful, 1 skipped, 0 in progress, 0 failed (complete captured list). These are existing results, not a new validation run. Current state still needs verification.")
        assertEquals(listOf(2, 1, 0, 0), GitHubRichSummaryPresentation.chartData(savedChecks).map { it.count })
    }

    @Test fun sessionQuestionDoesNotBecomeATruncatedImplementationSubject() {
        val text = GitHubProgressPresentation.introduction("What issues have you discovered this session that prevented you from doing what I asked?")
        assertTrue(text.contains("findings and blockers recorded in this conversation"))
        assertFalse(text.contains("work through What issues") || text.contains("in the current branch"))
    }

    @Test fun authoredMarkdownUsesRichRenderingWithoutManufacturingChartProvenance() {
        listOf(
            "**Reader.kt** retries before recording the response. I’ll check cancellation next.",
            "## Cancellation finding\nReader.kt retries before recording the response.",
            "The guard is in `src/Reader.kt`.",
            "| File | Finding |\n| --- | --- |\n| Reader.kt | Missing guard |",
            "- Reader.kt retries before saving.\n- Writer.kt owns cancellation."
        ).forEach { markdown ->
            assertTrue(markdown, GitHubRichSummaryPresentation.isRich(markdown))
            assertTrue(GitHubRichSummaryPresentation.chartData(markdown).isEmpty())
            assertEquals(null, GitHubRichSummaryPresentation.csv(markdown))
        }
        assertFalse(GitHubRichSummaryPresentation.isRich("Issue #2440 is open; inspect pkg/__init__.py next."))
    }

    private fun complete(progress: GitHubChatProgress, id: String, name: String, args: String, event: String = "tool_completed") {
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = event, toolName = name, toolCallId = id, toolArgs = json(args))))
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
