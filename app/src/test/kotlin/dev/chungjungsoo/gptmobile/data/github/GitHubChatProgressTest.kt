package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
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
    @Test fun summaryExplainsObservedWorkWithoutEchoingTheQuestionOrInventingCompletion() {
        val progress = GitHubChatProgress("Why did this fail?")
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_read")))
        assertTrue(progress.heartbeat().contains("where the behavior originates"))
        assertFalse(progress.heartbeat().contains("Why did this fail"))
        assertFalse(progress.heartbeat().contains("passed"))
    }

    @Test fun webToolActivityKeepsTheGlobeHintThroughCompletion() = kotlinx.coroutines.test.runTest {
        for (tool in listOf("web_search__connection", "fetch_webpage")) {
            val progress = GitHubChatProgress("Investigate the issue")
            progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = tool)))
            val preview = progress.updates().take(1).toList().single().preview.orEmpty()
            assertEquals(GitHubProgressPresentation.PreviewKind.WEB, GitHubProgressPresentation.previewKind(preview))
            assertEquals(GitHubProgressPresentation.PreviewKind.WEB, GitHubProgressPresentation.previewKind(GitHubChatProgress.milestone(tool, false, false)))
        }
    }

    @Test fun progressNeverEchoesTaskReasoningOrGatewayDiagnostics() {
        val progress = GitHubChatProgress()
        progress.observe(ApiState.Thinking("private reasoning must never appear in the public status"))
        assertTrue(progress.heartbeat().contains("Examining the request"))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(message = "schema dispatch error secret", usefulToolCalls = 3, noProgress = 1)))
        progress.observe(ApiState.ProgressCheckpoint("raw protocol checkpoint"))
        assertFalse(progress.heartbeat().contains("private reasoning"))
        assertFalse(progress.heartbeat().contains("schema"))
        assertFalse(progress.heartbeat().contains("checkpoint"))
        assertFalse(progress.heartbeat().contains("elapsed"))
        progress.observe(ApiState.Success("The answer"))
        assertTrue(progress.heartbeat().contains("Putting the response together."))
        assertFalse(GitHubChatProgress.milestone("github_read", false, false).contains("github_read"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun updatesFollowEventsImmediatelyAndDoNotRepeatIdleHeartbeats() = kotlinx.coroutines.test.runTest {
        val progress = GitHubChatProgress()
        val received = mutableListOf<ApiState.ActivitySummary>()
        val collection = backgroundScope.launch { progress.updates().collect { received += it } }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, received.size)
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_ci", toolArgs = kotlinx.serialization.json.Json.parseToJsonElement("""{"action":"logs","job_id":42}""") as kotlinx.serialization.json.JsonObject)))
        runCurrent()
        assertEquals(2, received.size)
        assertTrue(received.last().text.contains("Job 42"))
        assertTrue(received.last().text.contains("failing step"))
        progress.observe(ApiState.Thinking("unrelated private text"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, received.size)
        collection.cancel()
    }

    @Test fun summariesBindTheTaskToObservedActivityAndKeepPreviewSeparate() = kotlinx.coroutines.test.runTest {
        val progress = GitHubChatProgress("Fix the loading bar")
        val first = progress.updates().take(1).toList().single()
        assertFalse(first.text.contains("Fix the loading bar"))
        assertTrue(first.text.contains("trace the affected code"))
        assertTrue(first.text.startsWith("I’ll"))
        assertTrue(first.preview.orEmpty().contains("Preparing"))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_read__account")))
        assertTrue(progress.heartbeat().contains("where the behavior originates"))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = "github_read__account")))
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = "github_ci__account")))
        val update = progress.updates().take(1).toList().single()
        assertFalse(update.text.contains("Repository information is ready to review"))
        assertTrue(update.text.contains("pending or skipped jobs"))
        assertEquals("Inspecting the build and test results.", update.preview)
        assertFalse(update.text.contains("passed") || update.text.contains("github_read"))
    }

    @Test fun failedWritesAndPrivateThinkingNeverBecomeCompletedWork() {
        val progress = GitHubChatProgress("Repair the settings screen")
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_failed", toolName = "github_write")))
        progress.observe(ApiState.Thinking("All changes succeeded; private reasoning"))
        assertTrue(progress.heartbeat().contains("A step needs attention"))
        assertFalse(progress.heartbeat().contains("succeeded") || progress.heartbeat().contains("private reasoning"))
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
}
