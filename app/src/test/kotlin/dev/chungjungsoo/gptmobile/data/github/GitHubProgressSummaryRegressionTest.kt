package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        assertPublishedSummary(finding, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())

        val correction = ApiState.ActivitySummary(
            "Cancellation reaches Reader.kt through its parent job. The missing guard is in Writer.kt; I’ll inspect that call next.",
            modelAuthored = true,
            invocationId = "corrected-analysis"
        )
        assertEquals(null, progress.forward(correction))
        clock = 180_000
        assertPublishedSummary(correction, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())

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
        assertPublishedSummary(finding, progress.forward(finding))
        clock = 180_000
        assertEquals(null, progress.forward(ApiState.ActivitySummary("I’m reviewing the latest results.", modelAuthored = true)))
        assertEquals(finding.text, progress.heartbeat())
        val withActivityPrefix = ApiState.ActivitySummary(
            "I have completed 3 repository operations. Reader.kt retries before saving the response; I found the race.",
            modelAuthored = true
        )
        assertPublishedSummary(withActivityPrefix, progress.forward(withActivityPrefix))
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
            assertPublishedSummary(prose, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())
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
        assertTrue(text.contains("earlier runs"))
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

    @Test fun changedCapturedCommitAllowsEarlierExplanationWithoutPromotingModelProse() {
        listOf(false, true).forEach { selectedRepositoryDefault ->
            val fixture = SourcePreviewFixture()
            val (call, result) = capturedRead("changed-commit", commit = "b".repeat(40))
            val request = if (selectedRepositoryDefault) call.copy(arguments = JsonObject(call.arguments - "owner" - "repo")) else call
            val priorFacts = GitHubRichSummaryPresentation.factKeys(fixture.progress.summarySnapshot())
            fixture.observe(request to result)
            assertEquals(priorFacts, GitHubRichSummaryPresentation.factKeys(fixture.progress.summarySnapshot()))
            val explanation = fixture.repeatedExplanation()
            assertPublishedSummary(explanation, fixture.progress.forward(explanation))
            assertTrue(explanation.modelAuthored)
            val source = fixture.progress.summarySnapshot()
            assertFalse(source.recentSteps.any { it.contains("retries before") || it.contains("Writer.kt") })
            assertTrue(GitHubRichSummaryPresentation.chartData(GitHubRichSummaryPresentation.render(source)).isEmpty())
        }
    }

    @Test fun changedCapturedBytesAtTheSameCommitAllowEarlierExplanation() {
        val fixture = SourcePreviewFixture()
        fixture.observe(capturedRead("changed-result", content = "The captured source now includes a cancellation guard."))
        val explanation = fixture.repeatedExplanation()
        assertPublishedSummary(explanation, fixture.progress.forward(explanation))
    }

    @Test fun sameCapturedSourceOnAnotherCallStillSuppressesFormattedRepeats() {
        val fixture = SourcePreviewFixture()
        fixture.observe(capturedRead("same-source-new-call"))
        fixture.assertRepeatedExplanationSuppressed()
    }

    @Test fun capturedRepositoryAndConnectionKeepSameLookingSourceRangesDistinct() {
        listOf(
            capturedRead("other-repository", repo = "other-project"),
            capturedRead("other-connection", tool = "github_read__second_connection")
        ).forEach { read ->
            val fixture = SourcePreviewFixture()
            val priorFacts = GitHubRichSummaryPresentation.factKeys(fixture.progress.summarySnapshot())
            fixture.observe(read)
            assertEquals(priorFacts, GitHubRichSummaryPresentation.factKeys(fixture.progress.summarySnapshot()))
            val explanation = fixture.repeatedExplanation()
            assertPublishedSummary(explanation, fixture.progress.forward(explanation))
        }
    }

    @Test fun savedSourceIdentitiesCannotRefreshOrReplaceTheCurrentCapture() {
        listOf("shared", "retained").forEach { mode ->
            val fixture = SourcePreviewFixture(savedBeforeNarration = mode)
            val (call, result) = capturedRead("new-saved-source", commit = "c".repeat(40))
            fixture.observe(call to asSaved(result, mode))
            fixture.assertRepeatedExplanationSuppressed()
            fixture.observe(capturedRead("same-current-source"))
            fixture.assertRepeatedExplanationSuppressed()
            assertTrue(fixture.progress.summarySnapshot().recentSteps.any { it.startsWith("From saved results:") })
        }
    }

    @Test fun missingNegativeAndMismatchedCaptureReceiptsCannotRefreshAuthoredHistory() {
        listOf(
            "content-only", "missing-call", "wrong-call", "missing-digest", "invalid-digest", "digest-mismatch",
            "missing-commit", "invalid-commit", "missing-scope", "wrong-scope", "negative-verification", "unverified-read",
            "unknown-read-source", "failed-parent", "malformed-parent", "reconciliation", "malformed-context", "wrong-repository"
        ).forEach { kind ->
            val fixture = SourcePreviewFixture()
            val (call, result) = capturedRead("rejected-$kind", commit = "b".repeat(40))
            val receipt = requireNotNull(result.executionReceipt)
            val facts = receipt.getValue("semantic_summary") as JsonObject
            fun withFact(key: String, value: String) = JsonObject(receipt + ("semantic_summary" to JsonObject(facts + (key to JsonPrimitive(value)))))
            val rejected = when (kind) {
                "content-only" -> null
                "missing-call" -> JsonObject(receipt - "saved_view_source_call_id")
                "wrong-call" -> JsonObject(receipt + ("saved_view_source_call_id" to JsonPrimitive("another-call")))
                "missing-digest" -> JsonObject(receipt + ("semantic_summary" to JsonObject(facts - "source_result_sha256")))
                "invalid-digest" -> withFact("source_result_sha256", "not-a-digest")
                "digest-mismatch" -> JsonObject(receipt + ("saved_view_source_sha256" to JsonPrimitive("f".repeat(64))))
                "missing-commit" -> JsonObject(receipt + ("semantic_summary" to JsonObject(facts - "commit_sha")))
                "invalid-commit" -> withFact("commit_sha", "main")
                "missing-scope" -> JsonObject(receipt + ("semantic_summary" to JsonObject(facts - "hash_scope")))
                "wrong-scope" -> withFact("hash_scope", "DISPLAY_PAGE")
                "negative-verification" -> JsonObject(receipt + ("semantic_summary" to JsonObject(facts + ("source_read_verified" to JsonPrimitive(false)))))
                "unverified-read" -> JsonObject(receipt + ("read_source" to JsonPrimitive("UNVERIFIED")))
                "unknown-read-source" -> JsonObject(receipt + ("read_source" to JsonPrimitive("UNKNOWN")))
                "failed-parent" -> JsonObject(receipt + ("parent_result_successful" to JsonPrimitive(false)))
                "malformed-parent" -> JsonObject(receipt + ("parent_result_successful" to JsonPrimitive("true")))
                "reconciliation" -> JsonObject(receipt + ("requires_reconciliation" to JsonPrimitive(true)))
                "malformed-context" -> JsonObject(receipt + ("resource_context" to JsonPrimitive("sample-owner/project")))
                "wrong-repository" -> JsonObject(receipt + ("resource_context" to json("""{"owner":"another-owner","repo":"project"}""")))
                else -> error("Unexpected negative control")
            }
            fixture.observe(call to result.copy(executionReceipt = rejected))
            fixture.assertRepeatedExplanationSuppressed()
        }
    }

    @Test fun absentRepositoryIdentityAndMismatchedRequestedSourceCannotRefreshTheCapture() {
        listOf("missing-repository", "wrong-commit", "wrong-path").forEach { kind ->
            val fixture = SourcePreviewFixture()
            val (call, result) = capturedRead("rejected-$kind", commit = "b".repeat(40))
            val changed = if (kind == "missing-repository") {
                call.copy(arguments = JsonObject(call.arguments - "owner" - "repo")) to
                    result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) - "resource_context"))
            } else if (kind == "wrong-commit") {
                call.copy(arguments = JsonObject(call.arguments + ("commit_sha" to JsonPrimitive("a".repeat(40))))) to result
            } else {
                call.copy(arguments = JsonObject(call.arguments + ("path" to JsonPrimitive("src/Other.kt")))) to result
            }
            fixture.observe(changed)
            fixture.assertRepeatedExplanationSuppressed()
        }
    }

    @Test fun changedCaptureAllowsConsecutiveIdenticalExplanationAtTheOriginalCadence() {
        listOf(
            capturedRead("direct-new-commit", commit = "b".repeat(40)),
            capturedRead("direct-new-bytes", content = "The captured source now includes a cancellation guard.")
        ).forEach { changed ->
            val fixture = ConsecutiveSourcePreviewFixture(capturedRead("direct-initial"))
            val priorFacts = GitHubRichSummaryPresentation.factKeys(fixture.progress.summarySnapshot())
            fixture.observe(changed)
            assertEquals(priorFacts, GitHubRichSummaryPresentation.factKeys(fixture.progress.summarySnapshot()))
            fixture.clock = 179_999
            assertEquals(null, fixture.progress.forward(fixture.explanation))
            assertTrue(fixture.progress.tick().none { it is ApiState.ActivitySummary })
            fixture.clock = 180_000
            val repeated = assertPublishedSummary(fixture.explanation, fixture.progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())
            assertNotEquals(fixture.initialPublication.publicationId, repeated.publicationId)
            assertEquals("supplied-id-must-not-survive", fixture.explanation.publicationId)
            assertTrue(repeated.modelAuthored)
            assertEquals("direct-analysis", repeated.invocationId)
            val source = fixture.progress.summarySnapshot()
            assertFalse(source.recentSteps.any { it.contains("retries before") })
            assertTrue(GitHubRichSummaryPresentation.chartData(GitHubRichSummaryPresentation.render(source)).isEmpty())
        }
    }

    @Test fun unchangedCaptureCannotRepeatConsecutiveExplanationThroughANewCallOrInvocation() {
        val fixture = ConsecutiveSourcePreviewFixture(capturedRead("unchanged-initial", commit = "b".repeat(40)))
        fixture.observe(capturedRead("unchanged-new-call", commit = "b".repeat(40)))
        fixture.clock = 180_000
        fixture.assertSuppressed(fixture.explanation.copy(invocationId = "another-invocation"))
        fixture.clock = 315_000
        fixture.assertSuppressed(fixture.explanation)
    }

    @Test fun formattingOnlyCannotRepeatConsecutiveExplanationAtTheSameCapture() {
        val fixture = ConsecutiveSourcePreviewFixture(capturedRead("format-initial", commit = "b".repeat(40)))
        fixture.observe(capturedRead("format-new-call", commit = "b".repeat(40)))
        fixture.clock = 180_000
        val formatted = fixture.explanation.copy(text = "**Reader.kt** retries before recording the response. I will check cancellation next.")
        assertEquals(GitHubProgressPresentation.summaryKey(fixture.explanation.text), GitHubProgressPresentation.summaryKey(formatted.text))
        fixture.assertSuppressed(formatted)
    }

    @Test fun immediateAndRoutinePublicationsHaveDistinctLocalIdsWithoutChangingCadenceOrInvocation() {
        var clock = 0L
        val progress = GitHubChatProgress("Review cancellation") { clock }
        val introduction = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertOpaquePublicationId(introduction)
        clock = 10_000
        complete(progress, "blocked-read", "github_read", """{"path":"src/Reader.kt"}""", "tool_failed")
        val transition = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertOpaquePublicationId(transition)
        assertNotEquals(introduction.publicationId, transition.publicationId)
        assertFalse(transition.modelAuthored)
        assertEquals(null, transition.invocationId)
        assertTrue(transition.text.contains("failed"))
        val selected = ApiState.ActivitySummary(
            "Reader.kt still needs its source read before I can inspect cancellation.",
            modelAuthored = true,
            preview = "selected preview",
            invocationId = "selected-invocation",
            publicationId = "provider-supplied-id"
        )
        clock = 44_999
        assertEquals(null, progress.forward(selected))
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        clock = 45_000
        val routine = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertEquals(selected.copy(publicationId = null), routine.copy(publicationId = null))
        assertOpaquePublicationId(routine)
        assertNotEquals(selected.publicationId, routine.publicationId)
        assertNotEquals(transition.publicationId, routine.publicationId)
        assertEquals("provider-supplied-id", selected.publicationId)
        assertEquals(null, progress.forward(routine))
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
    }

    private inner class ConsecutiveSourcePreviewFixture(initialRead: Pair<ProviderEvent.ToolCall, AgentToolResult>) {
        var clock = 0L
        val progress = GitHubChatProgress("Review cancellation") { clock }
        val explanation = ApiState.ActivitySummary(
            "Reader.kt retries before recording the response. I’ll check cancellation next.",
            modelAuthored = true,
            preview = "captured source preview",
            invocationId = "direct-analysis",
            publicationId = "supplied-id-must-not-survive"
        )
        val initialPublication: ApiState.ActivitySummary

        init {
            progress.tick()
            observe(initialRead)
            assertEquals(null, progress.forward(explanation))
            clock = 44_999
            assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
            clock = 45_000
            initialPublication = assertPublishedSummary(explanation, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())
        }

        fun observe(read: Pair<ProviderEvent.ToolCall, AgentToolResult>) {
            val (call, result) = read
            assertTrue(progress.observeResult(call, result) != null)
            complete(progress, call.callId, call.name, call.arguments.toString())
        }

        fun assertSuppressed(candidate: ApiState.ActivitySummary) {
            assertEquals(null, progress.forward(candidate))
            assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
            assertEquals(explanation.text, progress.heartbeat())
        }
    }

    private inner class SourcePreviewFixture(savedBeforeNarration: String? = null) {
        var clock = 0L
        val progress = GitHubChatProgress("Review cancellation") { clock }
        private val first = ApiState.ActivitySummary("Reader.kt retries before recording the response. I’ll check cancellation next.", modelAuthored = true, invocationId = "analysis-a")
        private val second = ApiState.ActivitySummary("The cancellation guard is in Writer.kt; I’ll inspect that call next.", modelAuthored = true, invocationId = "analysis-b")

        init {
            progress.tick()
            observe(capturedRead("initial-source"))
            if (savedBeforeNarration != null) {
                val (call, result) = capturedRead("initial-saved-source", commit = "b".repeat(40))
                observe(call to asSaved(result, savedBeforeNarration))
            }
            progress.forward(first)
            clock = 45_000
            assertPublishedSummary(first, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())
            assertEquals(null, progress.forward(second))
            clock = 180_000
            assertPublishedSummary(second, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())
            clock = 315_000
        }

        fun observe(read: Pair<ProviderEvent.ToolCall, AgentToolResult>) {
            val (call, result) = read
            assertTrue(progress.observeResult(call, result) != null)
            complete(progress, call.callId, call.name, call.arguments.toString())
        }

        fun repeatedExplanation() = first.copy(text = "**Reader.kt** retries before recording the response. I will check cancellation next.", invocationId = "analysis-a-again")

        fun assertRepeatedExplanationSuppressed() {
            assertEquals(null, progress.forward(repeatedExplanation()))
            assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
            assertEquals(second.text, progress.heartbeat())
        }
    }

    private fun capturedRead(
        id: String,
        commit: String = "a".repeat(40),
        content: String = "The captured source records a response.",
        repo: String = "project",
        tool: String = "github_read__first_connection"
    ): Pair<ProviderEvent.ToolCall, AgentToolResult> {
        val call = ProviderEvent.ToolCall(id, tool, json("""{"owner":"sample-owner","repo":"$repo","path":"src/Reader.kt","commit_sha":"$commit","start_line":1,"end_line":4}"""))
        val source = json("""{"commit_sha":"$commit","items":[{"path":"src/Reader.kt","start_line":1,"end_line":4,"range_complete":true,"content":"$content"}]}""")
        val upstream = AgentToolResult(
            id,
            ToolResultContent.Json(source),
            false,
            executionReceipt = json("""{"read_source":"LIVE","parent_result_successful":true,"requires_reconciliation":false,"resource_context":{"owner":"sample-owner","repo":"$repo"}}""")
        )
        return call to GitHubResultViews().present("progress-source-test", "github_read", tool, upstream, call.arguments)
    }

    private fun asSaved(result: AgentToolResult, mode: String) = if (mode == "shared") {
        result.copy(sharedResult = true)
    } else {
        result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) + ("read_source" to JsonPrimitive("RETAINED"))))
    }

    private fun complete(progress: GitHubChatProgress, id: String, name: String, args: String, event: String = "tool_completed") {
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = event, toolName = name, toolCallId = id, toolArgs = json(args))))
    }

    private fun assertPublishedSummary(expected: ApiState.ActivitySummary, actual: ApiState?): ApiState.ActivitySummary {
        assertTrue(actual is ApiState.ActivitySummary)
        val published = actual as ApiState.ActivitySummary
        assertEquals(expected.copy(publicationId = null), published.copy(publicationId = null))
        assertTrue(expected !== published)
        assertOpaquePublicationId(published)
        assertFalse(expected.publicationId == published.publicationId)
        return published
    }

    private fun assertOpaquePublicationId(summary: ApiState.ActivitySummary) {
        val identity = requireNotNull(summary.publicationId)
        assertEquals(identity, UUID.fromString(identity).toString())
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
