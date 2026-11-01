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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChatProgressTest {
    @Test fun progressNeverEchoesTaskReasoningOrGatewayDiagnostics() {
        val progress = GitHubChatProgress()
        progress.observe(ApiState.Thinking("private reasoning must never appear in the public status"))
        assertEquals("Working through your request.", progress.heartbeat())
        progress.observe(ApiState.GatewayProgressChanged(GatewayProgress(message = "schema dispatch error secret", usefulToolCalls = 3, noProgress = 1)))
        progress.observe(ApiState.ProgressCheckpoint("raw protocol checkpoint"))
        assertFalse(progress.heartbeat().contains("private reasoning"))
        assertFalse(progress.heartbeat().contains("schema"))
        assertFalse(progress.heartbeat().contains("checkpoint"))
        assertFalse(progress.heartbeat().contains("elapsed"))
        progress.observe(ApiState.Success("The answer"))
        assertEquals("Putting the response together.", progress.heartbeat())
        assertFalse(GitHubChatProgress.milestone("github_read", false, false).contains("github_read"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun heartbeatContinuesEveryFiveSecondsWithoutProviderEvents() = kotlinx.coroutines.test.runTest {
        val progress = GitHubChatProgress()
        val received = mutableListOf<Long>()
        progress.updates().take(3).collect { received += testScheduler.currentTime }
        assertEquals(listOf(5_000L, 10_000L, 15_000L), received)
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
