package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
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
        assertTrue(preview.contains("observed result"))
        assertFalse(preview.contains("failing") || preview.contains("error"))
    }

    @Test fun dynamicCountsUpdateTheSameKeyIncludingARevisedEarlierCount() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        progress.tick()
        listOf("Check 2/8", "Check 3/8", "Check 2/8").forEach { text ->
            progress.observe(ApiState.ProgressCheckpoint(text))
            clock += 3000
            val update = progress.tick().filterIsInstance<ApiState.ProgressCheckpoint>().single()
            assertEquals(text, update.text)
            assertEquals("github-check-counts", GitHubProgressPresentation.liveKey(update.text))
        }
    }

    @Test fun selectedModelSummariesKeepAuthorshipAndUnchangedWorkDoesNotProduceFiller() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        assertFalse(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().modelAuthored)
        progress.observe(ApiState.ProgressCheckpoint("Reading the build file"))
        progress.observe(ApiState.ActivitySummary("The build file identifies the required checks. I am comparing those checks with the selected revision before reading the failed job output and preparing the next change.", modelAuthored = true))
        clock = 15_000
        assertTrue(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().modelAuthored)
        clock = 30_000
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
    }

    @Test fun summaryIsImmediateThenAtFifteenSecondsWithTwentyWords() {
        var clock = 0L
        val progress = GitHubChatProgress("status") { clock }
        val first = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertTrue(first.text.split(Regex("\\s+")).size >= 20)
        assertTrue(first.text.contains("Next"))
        clock = 14_999
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_read")))
        clock = 15_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(summary.contains("completed 1"))
        assertTrue(summary.contains("Next"))
        assertTrue(summary.split(Regex("\\s+")).size >= 20)
    }

    @Test fun previewBurstsCoalesceAndNeverRepeatAcrossInterveningLines() {
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
        assertTrue(progress.tick().isEmpty())
    }

    @Test fun noPrivateReasoningOrFailedWriteBecomesCompletedWork() {
        var clock = 0L
        val progress = GitHubChatProgress("Repair settings") { clock }
        progress.tick()
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_failed", toolName = "github_write")))
        progress.observe(ApiState.Thinking("All changes succeeded; private reasoning"))
        clock = 15_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(summary.contains("no repository operation has completed"))
        assertTrue(summary.contains("1 operations need recovery"))
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
        advanceTimeBy(12000)
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
        clock = 15_000
        assertTrue(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text.contains("completed 1 repository operations"))
    }

    @Test fun sameCallIdInDifferentJobsIsCountedSeparately() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        progress.tick()
        listOf("first", "second").forEach { job ->
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", jobId = job, sequence = 1, toolCallId = "call", toolName = "github_read")))
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_finished", jobId = job, sequence = 2, toolCallId = "call", toolName = "github_read")))
        }
        clock = 15_000
        assertTrue(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().text.contains("completed 2 repository operations"))
    }

    @Test fun newerFailureReplacesPendingProseWithCurrentRecoveryFacts() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect the repository") { clock }
        progress.tick()
        progress.observe(ApiState.ActivitySummary("The source read is available. I will compare its implementation with the request before deciding what remains to inspect.", modelAuthored = true))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_failed", toolName = "github_read", toolCallId = "failed-read")))
        clock = 15_000
        val summary = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertFalse(summary.modelAuthored)
        assertTrue(summary.text.contains("1 operations need recovery"))
    }

    @Test fun selectedProseIsDeliveredBeforeTheNextToolEventWithoutWaitingForTheTicker() {
        var clock = 0L
        val progress = GitHubChatProgress("Inspect the repository") { clock }
        progress.tick()
        clock = 15_000
        progress.tick()
        clock = 18_000
        val prose = ApiState.ActivitySummary("The workflow definition identifies the relevant validation jobs. I will now inspect the failed step and compare its output with the repository changes.", modelAuthored = true)
        assertEquals(prose, progress.forward(prose))
        val started = ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_ci", toolCallId = "job-log"))
        assertEquals(started, progress.forward(started))
        clock = 18_250
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
        assertEquals(prose.text, progress.heartbeat())
        assertEquals(null, progress.forward(prose))
        assertEquals(null, progress.forward(ApiState.ProgressCheckpoint("Check 3/8")))
    }
}
