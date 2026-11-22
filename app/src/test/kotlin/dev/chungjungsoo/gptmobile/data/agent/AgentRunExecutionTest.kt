package dev.chungjungsoo.gptmobile.data.agent

import android.app.Application
import dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the real coordinator entry, coroutine scope, publication and durable terminal phases. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AgentRunExecutionTest {
    private class Fixture(gateway: GatewayChatSubmission? = null) {
        val chats = mockk<ChatRepository>()
        val settings = mockk<SettingRepository>()
        val completions = mockk<GenerationCompletionStore>(relaxed = true)
        val terminals = MutableStateFlow<Map<String, Triple<MessageV2, String, String?>>>(emptyMap())
        val coordinator: AgentRunCoordinator

        init {
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(backgroundGeneration = false)
            coEvery { chats.markAgentRunRunning(any(), any()) } returns true
            coEvery { chats.updateAgentMessage(any()) } returns Unit
            coEvery { chats.waitingRemoteAgentRuns() } returns emptyList()
            coEvery { chats.gatewayRunsForCancellation(any()) } returns emptyList()
            coEvery { chats.finishActiveAgentRun(any(), any(), any(), any()) } returns false
            coEvery { chats.commitAgentRun(any(), any(), any(), any(), any()) } coAnswers {
                val runId = secondArg<String>()
                val saved = Triple(firstArg<MessageV2>(), thirdArg<String>(), arg<String?>(4))
                terminals.update { it + (runId to saved) }
                true
            }
            coordinator = AgentRunCoordinator(RuntimeEnvironment.getApplication(), chats, mockk(), settings, completions, gatewayChatSubmission = gateway)
        }

        fun request(runId: String, messageId: Int, fence: Long = 1): AgentRunRequest {
            val platform = PlatformV2(uid = "profile-$runId", name = runId, compatibleType = ClientType.CUSTOM, model = "model", apiUrl = "https://$runId.example/v1", token = java.util.UUID.randomUUID().toString())
            val user = MessageV2(id = 1, chatId = 1, content = "Original task", platformType = null)
            val answer = MessageV2(id = messageId, chatId = 1, content = "", platformType = platform.uid, currentRunId = runId, ownershipFence = fence)
            return AgentRunRequest(runId, 1, answer, platform, listOf(user), listOf(listOf(answer)))
        }

        suspend fun terminal(runId: String): Triple<MessageV2, String, String?> = withTimeout(10_000) {
            terminals.first { runId in it }.getValue(runId)
        }

        suspend fun idle() = withTimeout(10_000) { coordinator.activeRuns.first { it.isEmpty() } }
    }

    @Test fun interleavedExecutionsKeepTheirOwnAnswerFenceAndCompletion() = runBlocking {
        val fixture = Fixture()
        val first = fixture.request("first", 2, 3)
        val second = fixture.request("second", 3, 7)
        val releaseFirst = CompletableDeferred<Unit>()
        coEvery { fixture.chats.completeChat(any(), any(), any(), "first", any(), any()) } returns flow {
            emit(ApiState.Success("First answer."))
            releaseFirst.await()
            emit(ApiState.Done)
        }
        coEvery { fixture.chats.completeChat(any(), any(), any(), "second", any(), any()) } returns flow {
            emit(ApiState.Success("Second answer."))
            emit(ApiState.Done)
        }
        try {
            fixture.coordinator.resumeProviderRequests(listOf(first, second))
            val (secondSaved, secondStatus) = fixture.terminal("second")
            val liveFirst = withTimeout(10_000) { fixture.coordinator.streamMessages.first { it[2]?.content == "First answer." }.getValue(2) }
            assertEquals("first", liveFirst.currentRunId)
            assertEquals(3L, liveFirst.ownershipFence)
            assertEquals("Second answer.", secondSaved.content)
            assertEquals(7L, secondSaved.ownershipFence)
            assertEquals(AgentRunStatus.COMPLETED, secondStatus)
            releaseFirst.complete(Unit)
            val (firstSaved, firstStatus) = fixture.terminal("first")
            fixture.idle()
            assertEquals("First answer.", firstSaved.content)
            assertEquals(3L, firstSaved.ownershipFence)
            assertEquals(AgentRunStatus.COMPLETED, firstStatus)
            coVerify(exactly = 1) { fixture.chats.commitAgentRun(any(), "first", AgentRunStatus.COMPLETED, any(), null) }
            coVerify(exactly = 1) { fixture.chats.commitAgentRun(any(), "second", AgentRunStatus.COMPLETED, any(), null) }
            coVerify(exactly = 1) { fixture.completions.record("first", 1, 2, any()) }
            coVerify(exactly = 1) { fixture.completions.record("second", 1, 3, any()) }
        } finally {
            releaseFirst.complete(Unit)
            fixture.coordinator.cancelChatAndJoin(1)
        }
    }

    @Test fun losingTheRunningClaimStopsBeforeDispatchOrTerminalPublication() = runBlocking {
        val fixture = Fixture()
        coEvery { fixture.chats.markAgentRunRunning("superseded", any()) } returns false
        fixture.coordinator.resumeProviderRequests(listOf(fixture.request("superseded", 2)))
        fixture.idle()
        assertTrue(fixture.terminals.value.isEmpty())
        assertTrue(fixture.coordinator.streamMessages.value.isEmpty())
        coVerify(exactly = 1) { fixture.chats.markAgentRunRunning("superseded", any()) }
        coVerify(exactly = 0) { fixture.chats.completeChat(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.chats.commitAgentRun(any(), any(), any(), any(), any()) }
    }

    @Test fun acceptedGatewayHandoffRetainsRemoteWaitWithoutStartingLocalGeneration() = runBlocking {
        val gateway = mockk<GatewayChatSubmission>()
        val fixture = Fixture(gateway)
        coEvery { gateway.submitIfSupported(any()) } returns GatewayChatHandoff(accepted = true)
        coEvery { gateway.refresh(1) } returns Unit
        fixture.coordinator.resumeProviderRequests(listOf(fixture.request("remote", 2)))
        val (_, status, reason) = fixture.terminal("remote")
        fixture.idle()
        assertEquals(AgentRunStatus.WAITING_REMOTE_JOB, status)
        assertEquals(null, reason)
        coVerify(exactly = 1) { gateway.submitIfSupported(any()) }
        coVerify(exactly = 1) { gateway.refresh(1) }
        coVerify(exactly = 0) { fixture.chats.completeChat(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.completions.record(any(), any(), any(), any()) }
    }

    @Test fun cancellationRetainsTheLatestPartialAndOriginalCancellationOrigin() = runBlocking {
        val fixture = Fixture()
        coEvery { fixture.chats.completeChat(any(), any(), any(), "cancelled", any(), any()) } returns flow {
            emit(ApiState.Success("Retained partial answer."))
            awaitCancellation()
        }
        try {
            fixture.coordinator.resumeProviderRequests(listOf(fixture.request("cancelled", 2, 9)))
            withTimeout(10_000) { fixture.coordinator.streamMessages.first { it[2]?.content == "Retained partial answer." } }
            fixture.coordinator.cancelChatAndJoin(1)
            val (saved, status, reason) = fixture.terminal("cancelled")
            fixture.idle()
            assertEquals("Retained partial answer.", saved.content)
            assertEquals(9L, saved.ownershipFence)
            assertEquals(AgentRunStatus.CANCELED, status)
            assertEquals("USER_CANCELLED", reason)
            assertTrue(fixture.coordinator.streamMessages.value.isEmpty())
            coVerify(exactly = 1) { fixture.chats.commitAgentRun(any(), "cancelled", AgentRunStatus.CANCELED, any(), "USER_CANCELLED") }
            coVerify(exactly = 0) { fixture.completions.record(any(), any(), any(), any()) }
        } finally {
            fixture.coordinator.cancelChatAndJoin(1)
        }
    }
}
