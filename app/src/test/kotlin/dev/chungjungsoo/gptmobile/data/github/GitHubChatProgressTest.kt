package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChatProgressTest {
    @Test fun readingAJobLogDoesNotInventAFailingStep() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        progress.tick()
        val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"action":"logs","job_id":123}""") as kotlinx.serialization.json.JsonObject
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_ci", toolCallId = "logs", toolArgs = args)))
        clock = 3000
        val preview = progress.tick().filterIsInstance<ApiState.ProgressCheckpoint>().single().text
        assertTrue(preview.contains("Job 123"))
        assertTrue(preview.length < 100)
        assertFalse(preview.contains("failing") || preview.contains("error"))
    }

    @Test fun dynamicCountsUpdateTheSameKeyIncludingARevisedEarlierCount() {
        val progress = GitHubChatProgress { 0L }
        progress.tick()
        listOf("Check 2/8", "Check 3/8", "Check 2/8").forEach { text ->
            val update = ApiState.ProgressCheckpoint(text)
            assertEquals(update, progress.forward(update))
            assertEquals("github-check-counts", GitHubProgressPresentation.liveKey(update.text))
            assertEquals(null, progress.forward(update))
            assertTrue(progress.tick().isEmpty())
        }
    }

    @Test fun selectedModelSummariesKeepAuthorshipAndUnchangedWorkDoesNotProduceFiller() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        assertFalse(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().modelAuthored)
        progress.observe(ApiState.ProgressCheckpoint("Reading the build file"))
        progress.observe(ApiState.ActivitySummary("The build file identifies the required checks. I am comparing those checks with the selected revision before reading the failed job output and preparing the next change.", modelAuthored = true))
        clock = 45_000
        assertTrue(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().modelAuthored)
        clock = 90_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
    }

    @Test fun summaryIsImmediateThenAtFortyFiveSecondsWithTwentyWords() {
        var clock = 0L
        val progress = GitHubChatProgress("status") { clock }
        val first = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertTrue(first.text.split(Regex("\\s+")).size >= 20)
        assertTrue(first.text.contains("Next"))
        clock = 44_999
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_read", toolArgs = json("""{"path":"src/Reader.kt"}"""))))
        clock = 45_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertEquals(1, progress.summarySnapshot().completed)
        assertTrue(summary.contains("source read", ignoreCase = true))
        assertTrue(summary.contains("Next"))
        assertTrue(summary.split(Regex("\\s+")).size >= 20)
    }

    @Test fun previewBurstsCoalesceButActualActivityCanReactivateAfterAnotherStage() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        progress.tick()
        repeat(100) { progress.observe(ApiState.ProgressCheckpoint("Reading files")) }
        clock = 2999
        assertTrue(progress.tick().isEmpty())
        clock = 3000
        assertEquals("Reading files", (progress.tick().single() as ApiState.ProgressCheckpoint).text)
        progress.observe(ApiState.ProgressCheckpoint("Checking tests"))
        clock = 5999
        assertTrue(progress.tick().isEmpty())
        clock = 6000
        assertEquals(1, progress.tick().size)
        progress.observe(ApiState.ProgressCheckpoint("Reading files."))
        clock = 9000
        assertEquals("Reading files.", (progress.tick().single() as ApiState.ProgressCheckpoint).text)
        clock = 12000
        assertTrue(progress.tick().isEmpty())
    }

    @Test fun noPrivateReasoningOrFailedWriteBecomesCompletedWork() {
        var clock = 0L
        val progress = GitHubChatProgress("Repair settings") { clock }
        progress.tick()
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_failed", toolName = "github_write")))
        progress.observe(ApiState.Thinking("All changes succeeded; private reasoning"))
        clock = 45_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertEquals(0, progress.summarySnapshot().completed)
        assertEquals(1, progress.summarySnapshot().failed)
        assertTrue(summary.contains("failed"))
        assertFalse(summary.contains("succeeded") || summary.contains("private reasoning"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun tickerPublishesWithoutFurtherProviderEventsAndCancelsCleanly() = kotlinx.coroutines.test.runTest {
        val progress = GitHubChatProgress("Review") { testScheduler.currentTime }
        val received = mutableListOf<ApiState>()
        val collection = backgroundScope.launch { progress.updates().collect { received += it } }
        runCurrent()
        assertEquals(1, received.size)
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "web_search")))
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(GitHubProgressPresentation.PreviewKind.WEB, GitHubProgressPresentation.previewKind((received.last() as ApiState.ProgressCheckpoint).text))
        advanceTimeBy(42000)
        runCurrent()
        assertEquals(2, received.filterIsInstance<ApiState.ActivitySummary>().size)
        collection.cancel()
        val count = received.size
        advanceTimeBy(30000)
        assertEquals(count, received.size)
    }

    @Test fun eachProviderRoundStartsAFreshPresentationAndPreservesEvents() = runBlocking {
        var starts = 0
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("current round"), ProviderEvent.Completed)
        }
        val session = GitHubProgressSession(delegate) { starts++ }
        repeat(2) { assertEquals(listOf(ProviderEvent.TextDelta("current round"), ProviderEvent.Completed), session.streamRound(emptyList(), emptyList()).toList()) }
        assertEquals(2, starts)
    }

    @Test fun notificationEligibilityIsSpecificToTheVisibleConversationAndForegroundState() {
        try {
            GitHubChatPresence.show("first", 1)
            GitHubChatPresence.show("second", 2)
            assertFalse(GitHubChatPresence.shouldNotify(1, foreground = true))
            assertTrue(GitHubChatPresence.shouldNotify(3, foreground = true))
            assertTrue(GitHubChatPresence.shouldNotify(1, foreground = false))
            GitHubChatPresence.hide("first")
            assertTrue(GitHubChatPresence.shouldNotify(1, foreground = true))
            assertFalse(GitHubChatPresence.shouldNotify(2, foreground = true))
        } finally {
            GitHubChatPresence.hide("first")
            GitHubChatPresence.hide("second")
        }
    }

    @Test fun replayedGatewayTerminalEventsAreCountedOnceWithoutCallIds() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        progress.tick()
        val completed = ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", jobId = "job", sequence = 7, toolName = "github_read"))
        repeat(3) { progress.observe(completed) }
        clock = 45_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        assertEquals(1, progress.summarySnapshot().completed)
    }

    @Test fun sameCallIdInDifferentJobsIsCountedSeparately() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        progress.tick()
        listOf("first", "second").forEach { job ->
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", jobId = job, sequence = 1, toolCallId = "call", toolName = "github_read")))
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_finished", jobId = job, sequence = 2, toolCallId = "call", toolName = "github_read")))
        }
        clock = 45_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        assertEquals(2, progress.summarySnapshot().completed)
    }

    @Test fun newerFailureReplacesPendingProseWithCurrentRecoveryFacts() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect the repository") { clock }
        progress.tick()
        progress.observe(ApiState.ActivitySummary("The source read is available. I will compare its implementation with the request before deciding what remains to inspect.", modelAuthored = true))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_failed", toolName = "github_read", toolCallId = "failed-read")))
        clock = 45_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertFalse(summary.modelAuthored)
        assertTrue(summary.text.contains("failed"))
        assertEquals(1, progress.summarySnapshot().failed)
    }

    @Test fun selectedProseIsDeliveredWhenItsSlotOpens() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect the repository") { clock }
        progress.tick()
        clock = 45_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        clock = 48_000
        val prose = ApiState.ActivitySummary("The workflow definition identifies the relevant validation jobs. I will now inspect the failed step and compare its output with the repository changes.", modelAuthored = true)
        assertEquals(prose, progress.forward(prose))
        val started = ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_ci", toolCallId = "job-log"))
        assertEquals(started, progress.forward(started))
        clock = 48_250
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        assertEquals(prose.text, progress.heartbeat())
        assertEquals(null, progress.forward(prose))
        val counter = ApiState.ProgressCheckpoint("Check 3/8")
        assertEquals(counter, progress.forward(counter))
    }

    @Test fun selectedSummariesCoalesceDuringCooldownAndRetainTheirInvocation() {
        var clock = 0L
        val progress = GitHubChatProgress("Investigate the build") { clock }
        progress.tick()
        val first = ApiState.ActivitySummary("I found the workflow.", modelAuthored = true, invocationId = "first")
        val latest = ApiState.ActivitySummary("The failing step is Kotlin compilation. I will inspect its diagnostic next.", modelAuthored = true, invocationId = "latest")
        assertEquals(null, progress.forward(first))
        clock = 15_000
        assertEquals(null, progress.forward(latest))
        assertEquals(null, progress.forward(ApiState.ActivitySummary("A generic preview arrived after the selected model.")))
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        clock = 45_000
        assertEquals(latest, progress.tick().filterIsInstance<ApiState.ActivitySummary>().single())
    }

    @Test fun gatewayCollectionCountsStayAheadOfTheOrdinaryPreviewInterval() {
        val progress = GitHubChatProgress { 0L }
        progress.tick()
        (1..3).forEach { complete ->
            val text = "Checks: $complete/103 complete"
            val gateway = ApiState.GatewayProgressChanged(GatewayProgress(event = "check_progress", jobId = "job", sequence = complete, message = text))
            assertEquals(gateway, progress.forward(gateway))
            assertEquals(ApiState.ProgressCheckpoint(text), progress.tick().single())
            progress.forward(gateway)
            assertTrue(progress.tick().isEmpty())
        }
    }

    @Test fun selectedPublicFindingsSurviveLaterToolTicksUntilNewFindingsOrAProblem() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect the build") { clock }
        progress.tick()
        val prose = ApiState.ActivitySummary("The workflow uses the same validation command as the local build. I’ll inspect the job log to identify the actual failing diagnostic before choosing a repair.", modelAuthored = true)
        clock = 45_000
        assertEquals(prose, progress.forward(prose))
        progress.forward(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_ci", toolCallId = "logs")))
        clock = 90_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        progress.forward(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_ci", toolCallId = "logs")))
        clock = 135_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        assertEquals(prose.text, progress.heartbeat())
        assertEquals(null, progress.forward(ApiState.ActivitySummary("A generic application summary cannot displace selected-model findings.")))
    }

    @Test fun deterministicFallbackIsAdmittedAndRepeatedResultCountersDoNotCreateNewProse() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect source") { clock }
        progress.tick()
        val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"ranges":[{"path":"src/Reader.kt"}]}""") as kotlinx.serialization.json.JsonObject
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_read", toolCallId = "first", toolArgs = args)))
        val fallback = ApiState.ActivitySummary(GitHubProgressNarrator.factualSummary(progress.summarySnapshot()))
        clock = 45_000
        assertEquals(fallback, progress.forward(fallback))
        assertTrue(fallback.text.contains("Reader.kt"))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_read", toolCallId = "second", toolArgs = args)))
        clock = 90_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        assertEquals(2, progress.summarySnapshot().completed)
        assertFalse(progress.heartbeat().contains("2 repository operations"))
    }

    @Test fun mutationResultsRemainUnverifiedAndRawBodiesNeverBecomeProgress() {
        var clock = 0L
        val progress = GitHubChatProgress("Fix the source") { clock }
        progress.tick()
        val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"branch":"repair","commit_sha":"${"a".repeat(40)}"}""") as kotlinx.serialization.json.JsonObject
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_commit", toolCallId = "proposal", toolArgs = args, toolResult = "private tool payload: all tests passed and committed")))
        clock = 45_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(summary.contains("repair") && summary.contains("verification"))
        assertFalse(summary.contains("private tool payload") || summary.contains("tests passed") || summary.contains("committed"))
        assertFalse(summary.contains("a".repeat(12)))
        assertTrue(GitHubChatProgress.milestone("github_commit", false, false, args).contains("verification pending"))
    }

    @Test fun skippedAndBlockedEventsReplaceStaleAuthoredProgressWithoutCountingSuccess() {
        listOf("tool_skipped", "tool_blocked").forEach { event ->
            var clock = 0L
            val progress = GitHubChatProgress("Validate change") { clock }
            progress.tick()
            progress.forward(ApiState.ActivitySummary("I’m examining the requested validation and will report its actual result when the current operation returns.", modelAuthored = true))
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_ci", toolCallId = "validate")))
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = event, toolName = "github_ci", toolCallId = "validate")))
            clock = 45_000
            val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
            assertFalse(summary.modelAuthored)
            assertTrue(summary.text.contains(if (event == "tool_skipped") "skipped" else "blocked"))
            assertEquals(0, progress.summarySnapshot().completed)
            assertEquals(0, progress.summarySnapshot().pending)
            assertEquals(1, progress.summarySnapshot().skipped + progress.summarySnapshot().blocked)
        }
    }

    @Test fun aLegacyFailedFinishAndEarlierFailureSurviveLaterSuccessfulReads() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect changes") { clock }
        progress.tick()
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_finished", status = "failed", toolName = "github_ci", toolCallId = "failed")))
        repeat(9) { index ->
            val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"path":"Source$index.kt"}""") as kotlinx.serialization.json.JsonObject
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_read", toolCallId = "read-$index", toolArgs = args)))
        }
        clock = 45_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(summary.contains("failed") && summary.contains("Source8.kt"))
        assertEquals(9, progress.summarySnapshot().completed)
        assertEquals(1, progress.summarySnapshot().failed)
    }

    @Test fun gatewayCounterTemplateCannotReplaceTheCurrentConcretePreview() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect source") { clock }
        progress.tick()
        val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"path":"src/Reader.kt"}""") as kotlinx.serialization.json.JsonObject
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_read", toolCallId = "read", toolArgs = args)))
        progress.observe(ApiState.ProgressCheckpoint("10 tool calls finished. Recent work: github read, github context."))
        clock = 3000
        val preview = progress.tick().filterIsInstance<ApiState.ProgressCheckpoint>().single().text
        assertTrue(preview.contains("Reader.kt"))
        assertFalse(preview.contains("tool calls") || preview.contains("10"))
    }

    @Test fun existingSemanticFactsReplaceLabelOnlyProgressWithoutExposingRawBodies() {
        var clock = 0L
        val progress = GitHubChatProgress("Test the GitHub integration") { clock }
        progress.tick()
        val checks = ProviderEvent.ToolCall("checks", "github_context", json("""{"action":"checks","pull_number":1089}"""))
        val facts = json("""{"schema":"github.semantic-summary.v1","operation":"github_context","check_counts":{"successful":8,"skipped":1,"in_progress":9,"failed":0,"other_completed":0},"check_inventory_complete":false}""")
        val result = AgentToolResult("checks", ToolResultContent.Text("Private body: all tests passed and task completed"), false, executionReceipt = kotlinx.serialization.json.JsonObject(mapOf("semantic_summary" to facts)))
        val preview = progress.observeResult(checks, result).orEmpty()
        assertTrue(preview.contains("8 successful") && preview.contains("9 in progress") && preview.contains("PR #1089"))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = checks.name, toolCallId = checks.callId, toolArgs = checks.arguments)))
        val workflows = ProviderEvent.ToolCall("workflows", "github_ci", json("""{"action":"workflows"}"""))
        val workflowFacts = json("""{"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_ci","workflow_definitions_observed":55,"workflow_inventory_complete":true}}""")
        assertTrue(progress.observeResult(workflows, AgentToolResult("workflows", ToolResultContent.Json(workflowFacts), false)).orEmpty().contains("55"))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = workflows.name, toolCallId = workflows.callId, toolArgs = workflows.arguments)))
        clock = 45_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(summary.contains("8 successful") && summary.contains("incomplete captured list"))
        assertTrue(summary.contains("55 definitions") && summary.contains("does not execute validation"))
        assertTrue(summary.contains("integration criteria"))
        assertFalse(summary.contains("Private body") || summary.contains("all tests passed") || summary.contains("available for review"))
        assertEquals(2, progress.summarySnapshot().completed)
    }

    @Test fun resultObservationRejectsUnboundMalformedAndPagedEvidence() {
        val progress = GitHubChatProgress { 0L }
        val call = ProviderEvent.ToolCall("status", "github_context", json("""{"action":"status"}"""))
        val facts = json("""{"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_context","open_issue_count":2,"open_pull_request_count":1}}""")
        val result = AgentToolResult("status", ToolResultContent.Json(facts), false)
        assertEquals(null, progress.observeResult(call, result.copy(callId = "another")))
        assertEquals(null, progress.observeResult(call, result.copy(isError = true)))
        assertEquals(null, progress.observeResult(call.copy(arguments = json("""{"view_cursor":"next"}""")), result))
        assertEquals(null, progress.observeResult(call.copy(name = "github_ci"), result))
        assertEquals(null, progress.observeResult(call, result.copy(content = ToolResultContent.Text(facts.toString()))))
        assertEquals(null, progress.observeResult(call, result.copy(content = ToolResultContent.Json(json("""{"open_issue_count":2}""")))))
        assertEquals(null, progress.observeResult(call, result.copy(content = ToolResultContent.Json(json("""{"semantic_summary":{"schema":"unknown","operation":"github_context","open_issue_count":2}}""")))))
        assertEquals(null, progress.observeResult(call, result.copy(content = ToolResultContent.Json(json("""{"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_context","open_issue_count":-2}}""")))))
        assertEquals("Repository status: 2 open issues and 1 open pull request", progress.observeResult(call, result))
    }

    @Test fun savedResultsAndUnknownPolicyKeepTheirVerificationLimits() {
        var clock = 0L
        val progress = GitHubChatProgress("Review the PR") { clock }
        progress.tick()
        val call = ProviderEvent.ToolCall("saved", "github_context", json("""{"action":"status"}"""))
        val facts = json("""{"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_context","open_issue_count":2,"open_pull_request_count":1}}""")
        val preview = progress.observeResult(call, AgentToolResult("saved", ToolResultContent.Json(facts), false, sharedResult = true))
        assertTrue(preview.orEmpty().startsWith("Saved results"))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = call.name, toolCallId = call.callId)))
        val policy = ProviderEvent.ToolCall("policy", "github_pr", json("""{"action":"inspect","pull_number":1089}"""))
        val policyFacts = json("""{"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_pr","required_checks_state":"UNKNOWN"}}""")
        progress.observeResult(policy, AgentToolResult("policy", ToolResultContent.Json(policyFacts), false))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = policy.name, toolCallId = policy.callId, toolArgs = policy.arguments)))
        clock = 45_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(summary.contains("From saved results") && summary.contains("Current state still needs verification"))
        assertTrue(summary.contains("merge readiness cannot be established"))
        assertFalse(summary.contains("ready to merge") || summary.contains("all checks passed"))
    }

    @Test fun responseAndTerminalEventsStopTickerWhileAnActualNewRoundCanResume() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        progress.tick()
        progress.observe(ApiState.ProgressCheckpoint("Reading files"))
        progress.forward(ApiState.Success("The captured findings are available."))
        clock = 45_000
        assertTrue(progress.tick().isEmpty())
        assertEquals(null, progress.forward(ApiState.ActivitySummary("A delayed update must not interrupt the response.", modelAuthored = true)))
        assertEquals(null, progress.forward(ApiState.ProgressCheckpoint("Check 2/8")))
        progress.forward(ApiState.GitHubRoundStarted)
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_read", toolCallId = "next")))
        clock = 48_000
        val resumed = progress.tick()
        assertTrue(resumed.any { it is ApiState.ProgressCheckpoint })
        assertFalse(resumed.filterIsInstance<ApiState.ActivitySummary>().any { it.text.contains("delayed update") })
        listOf(ApiState.Done, ApiState.Error("The task remains incomplete")).forEach { terminal ->
            val ended = GitHubChatProgress { clock }
            ended.tick()
            ended.forward(terminal)
            ended.forward(ApiState.GitHubRoundStarted)
            ended.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_read", toolCallId = "late")))
            clock += 45_000
            assertTrue(ended.tick().isEmpty())
            assertEquals(null, ended.forward(ApiState.ActivitySummary("A late summary")))
        }
    }

    @Test fun unfocusedSuccessfulReadsDoNotCreateRecurringNarrativeOrDisplaceFacts() {
        var clock = 0L
        val progress = GitHubChatProgress("Test the GitHub integration") { clock }
        progress.tick()
        repeat(8) { index ->
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_release", toolCallId = "read-$index")))
            clock += 45_000
            assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        }
        assertEquals(8, progress.summarySnapshot().completed)
        assertTrue(progress.summarySnapshot().recentSteps.isEmpty())
        assertTrue(progress.summarySnapshot().next.contains("integration criteria"))
        val call = ProviderEvent.ToolCall("facts", "github_context", json("""{"action":"status"}"""))
        progress.observeResult(call, AgentToolResult("facts", ToolResultContent.Json(json("""{"semantic_summary":{"schema":"github.semantic-summary.v1","operation":"github_context","open_issue_count":2}}""")), false))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = call.name, toolCallId = call.callId)))
        clock += 45_000
        assertTrue(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text.contains("2 open issues"))
        repeat(9) { index -> progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_release", toolCallId = "later-$index"))) }
        clock += 45_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        assertTrue(progress.summarySnapshot().recentSteps.single().contains("2 open issues"))
    }

    @Test fun anUnchangedCounterCanReactivateAfterAnotherActivity() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        progress.tick()
        val counter = ApiState.ProgressCheckpoint("Checks: 2/8 verified")
        assertEquals(counter, progress.forward(counter))
        assertEquals(null, progress.forward(counter))
        progress.observe(ApiState.ProgressCheckpoint("Reading source"))
        clock = 3000
        assertEquals("Reading source", (progress.tick().single() as ApiState.ProgressCheckpoint).text)
        assertEquals(counter, progress.forward(counter))
        assertEquals(null, progress.forward(counter))
        assertTrue(progress.tick().isEmpty())
    }

    private fun json(value: String) = kotlinx.serialization.json.Json.parseToJsonElement(value) as kotlinx.serialization.json.JsonObject
}
