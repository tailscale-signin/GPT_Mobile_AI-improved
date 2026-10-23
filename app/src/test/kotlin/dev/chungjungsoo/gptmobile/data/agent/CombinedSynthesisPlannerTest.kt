package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSynthesisPlannerTest {
    private val chats = mockk<ChatRepository>()
    private val settings = mockk<SettingRepository>()
    private val research = mockk<ResearchSessionStore>()
    private val policy = CombinedRequestedPolicy(participantUids = listOf("p", "h"), requestedModels = mapOf("p" to "m", "h" to "m"), requestedPolicyDigest = "policy", userPrompt = "Request")
    private val context = CombinedExecutionContext(policy, rootBudgetId = "root", chatId = 1)
    private val accepted = CombinedAcceptedTurn(context = context, tools = ChatMcpToolConfig(combined = context))
    private val user = MessageV2(id = 2, chatId = 1, platformType = null, content = "Request")
    private val primary = MessageV2(id = 3, chatId = 1, linkedMessageId = 2, platformType = "p", content = "Useful contribution.", currentRunId = "p-run")
    private val helper = MessageV2(id = 4, chatId = 1, linkedMessageId = 2, platformType = "h", content = "", currentRunId = "h-run")
    private val planner = CombinedSynthesisPlanner(chats, settings, research)

    private fun fixture(helperState: String = "RUNNING") {
        coEvery { chats.fetchMessagesV2(1) } returns listOf(user, primary, helper)
        every { chats.observeAgentRuns(1) } returns flowOf(
            listOf(
                AgentRun("p-run", 1, 2, 3, "p", "CUSTOM", "m", "COMPLETED", synthesisSnapshot = accepted.encode()),
                AgentRun("h-run", 1, 2, 4, "h", "CUSTOM", "m", helperState, synthesisSnapshot = accepted.encode())
            )
        )
        coEvery { chats.isCombinedTurnCancelled("root") } returns false
    }

    @Test fun finalContributorCanPersistAndDispatchSynthesisWithoutAChatViewModel() = runTest {
        fixture("COMPLETED")
        coEvery { settings.fetchPlatformV2s() } returns listOf(dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "p", name = "Primary", enabled = true, model = "m"))
        coEvery { research.exportTurn(1, any(), any()) } returns emptyList()
        every { chats.observeToolEvents(1) } returns flowOf(emptyList())
        coEvery { chats.persistAgentRetry(any()) } answers {
            val request = firstArg<dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest>()
            assertTrue(request.freezeCombinedSources)
            val assistant = request.assistantMessage.copy(content = "", currentRunId = request.run.runId)
            dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryResult(
                assistant,
                AgentRun(request.run.runId, 1, 2, 3, "p", "CUSTOM", "m", synthesisKey = request.run.synthesisKey, synthesisSnapshot = request.run.synthesisSnapshot)
            )
        }
        val next = planner.prepare(1, 2, "root")
        assertNotNull(next)
        assertEquals("p", next!!.platform.uid)
        assertEquals(CombinedRunRole.SYNTHESIS, next.chatToolConfig?.combined?.role)
        assertTrue(next.assistantMessages.last().isEmpty())
        assertEquals(2, next.userMessages.last().id)
        coVerify(exactly = 1) { chats.persistAgentRetry(any()) }
    }

    @Test fun failedTerminalHelperCannotHoldUpUsableContributions() = runTest {
        fixture("FAILED")
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), listOf("p", "h"), mapOf("p" to "m", "h" to "m"), user.content, singlePass = true)
        val execution = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", chatId = 1)
        val accepted = CombinedAcceptedTurn(context = execution, tools = ChatMcpToolConfig(combined = execution))
        coEvery { chats.combinedActiveMembers(1) } returns setOf("p", "h")
        every { chats.observeAgentRuns(1) } returns flowOf(
            listOf(
                AgentRun("p-run", 1, 2, 3, "p", "CUSTOM", "m", "COMPLETED", synthesisSnapshot = accepted.encode()),
                AgentRun("h-run", 1, 2, 4, "h", "CUSTOM", "m", "FAILED", synthesisSnapshot = accepted.encode())
            )
        )
        coEvery { settings.fetchPlatformV2s() } returns listOf(dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "p", name = "Primary", enabled = true, model = "m"))
        coEvery { research.exportTurn(1, any(), any()) } returns emptyList()
        every { chats.observeToolEvents(1) } returns flowOf(emptyList())
        coEvery { chats.persistAgentRetry(any()) } answers {
            val request = firstArg<dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest>()
            assertTrue(request.freezeCombinedSources)
            val assistant = request.assistantMessage.copy(content = "", currentRunId = request.run.runId)
            dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryResult(
                assistant,
                AgentRun(request.run.runId, 1, 2, 3, "p", "CUSTOM", "m", synthesisKey = request.run.synthesisKey, synthesisSnapshot = request.run.synthesisSnapshot)
            )
        }
        val next = planner.prepare(1, 2, "root")
        assertNotNull(next)
        assertEquals("p", next!!.platform.uid)
        assertEquals(CombinedRunRole.SYNTHESIS, next.chatToolConfig?.combined?.role)
        assertTrue(next.assistantMessages.last().isEmpty())
        assertEquals(2, next.userMessages.last().id)
        coVerify(exactly = 1) { chats.persistAgentRetry(any()) }
    }

    @Test fun participantBarrierUsesPersistedRunsBeforeAnySynthesisPreparation() = runTest {
        fixture()
        assertNull(planner.prepare(1, 2, "root"))
        coVerify(exactly = 0) { settings.fetchPlatformV2s() }
        coVerify(exactly = 0) { chats.persistAgentRetry(any()) }
    }

    @Test fun cancelledAcceptedRootCannotDispatchAfterAllContributorsSettle() = runTest {
        fixture("COMPLETED")
        coEvery { chats.isCombinedTurnCancelled("root") } returns true
        assertNull(planner.prepare(1, 2, "root"))
        coVerify(exactly = 0) { chats.persistAgentRetry(any()) }
    }

    @Test fun persistedWriterMakesDuplicateHandoffANoOp() = runTest {
        fixture("COMPLETED")
        coEvery { chats.fetchMessagesV2(1) } returns listOf(user, primary.copy(currentRunId = "combined-synthesis:owner"), helper)
        assertNull(planner.prepare(1, 2, "root"))
        coVerify(exactly = 0) { chats.persistAgentRetry(any()) }
    }
}
