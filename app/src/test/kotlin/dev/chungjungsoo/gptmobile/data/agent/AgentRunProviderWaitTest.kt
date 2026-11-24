package dev.chungjungsoo.gptmobile.data.agent

import android.app.Application
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchDeferredStage
import dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AgentRunProviderWaitTest {
    @Test fun preflightPersistenceExceptionRecordsItsUnknownOutcomeBeforeAnyModelDispatch() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val chats = mockk<ChatRepository>().also { repository -> coEvery { repository.conversationPrivacyLocked(any()) } returns false }
        val settings = mockk<SettingRepository>()
        val waits = mockk<ProviderWaitStore>()
        val platform = PlatformV2(uid = "p", name = "Primary", compatibleType = ClientType.CUSTOM, model = "model", apiUrl = "https://writer.example/v1", token = "preflight-${java.util.UUID.randomUUID()}")
        val user = MessageV2(id = 1, chatId = 1, content = "Original task", platformType = null)
        val answer = MessageV2(id = 2, chatId = 1, content = "", platformType = "p", currentRunId = "run", ownershipFence = 1)
        val request = AgentRunRequest("run", 1, answer, platform, listOf(user), listOf(listOf(answer)))
        dev.chungjungsoo.gptmobile.data.network.ProviderCooldown.defer(dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig(platform.apiUrl, platform.token), "60")
        val committed = CompletableDeferred<MessageV2>()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(backgroundGeneration = false)
        coEvery { waits.deferResult(any()) } returns ProviderWaitSchedulingResult.uncertain(IOException("unconfirmed persistence"))
        coEvery { chats.commitAgentRun(any(), "run", "BLOCKED", any(), any()) } coAnswers {
            committed.complete(firstArg())
            true
        }
        val coordinator = AgentRunCoordinator(context, chats, mockk(), settings, mockk(), providerWaits = waits)
        coordinator.resumeProviderRequests(listOf(request))
        val saved = withTimeout(10_000) { committed.await() }
        val failure = requireNotNull(saved.timeline.last().profileFailure)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("PERSISTENCE_OUTCOME_UNKNOWN"), failure.recoveryDetails?.get("provider_wait_no_wait_reason"))
        assertEquals(false, failure.retryAllowed)
        assertEquals(false, failure.requestReplayAllowed)
        assertTrue(coordinator.activeRuns.value.isEmpty())
        coVerify(exactly = 0) { chats.markAgentRunRunning(any(), any()) }
        coVerify(exactly = 0) { chats.completeChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun terminalQuotaPathsRetainTheirExactNoWaitReasonWithTheOriginalFailure() = runBlocking {
        val transportRefusals = listOf("GATEWAY_COMMAND_PRESENT", "REMOTE_OUTCOME_UNKNOWN", "RETRY_DEADLINE_UNAVAILABLE", "REMOTE_JOB_PRESENT", "PROFILE_UNAVAILABLE", "ROUTE_MISMATCH")
        for (reason in listOf("DECISION_UNAVAILABLE", "REJECTION_NOT_REPLAY_SAFE", "WAIT_STORE_UNAVAILABLE") + transportRefusals) {
            val context = RuntimeEnvironment.getApplication()
            val chats = mockk<ChatRepository>().also { repository -> coEvery { repository.conversationPrivacyLocked(any()) } returns false }
            val settings = mockk<SettingRepository>()
            val platform = PlatformV2(uid = "p", name = "Primary", compatibleType = ClientType.CUSTOM, model = "model", apiUrl = "https://writer.example/v1")
            val user = MessageV2(id = 1, chatId = 1, content = "Original task", platformType = null)
            val answer = MessageV2(id = 2, chatId = 1, content = "", platformType = "p", currentRunId = "run", ownershipFence = 1)
            val request = AgentRunRequest("run", 1, answer, platform, listOf(user), listOf(listOf(answer)))
            val decision = ProviderLimitDecision("TEST", "p", "model", 1000, "scope", "PROVIDER_RATE_LIMITED", reason != "REJECTION_NOT_REPLAY_SAFE")
                .takeUnless { reason == "DECISION_UNAVAILABLE" || reason in transportRefusals }
            val committed = CompletableDeferred<MessageV2>()
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(backgroundGeneration = false)
            coEvery { chats.markAgentRunRunning("run", any()) } returns true
            coEvery { chats.updateAgentMessage(any()) } returns Unit
            coEvery { chats.completeChat(any(), any(), any(), "run", any(), any()) } returns flow {
                emit(ApiState.Success("Retained source findings."))
                emit(
                    ApiState.Error(
                        "Provider request limit reached.",
                        providerLimit = decision,
                        errorCode = "429",
                        failureDetails = kotlinx.serialization.json.buildJsonObject {
                            put("http_status", kotlinx.serialization.json.JsonPrimitive(200))
                            put("provider_failure_kind", kotlinx.serialization.json.JsonPrimitive("RATE_LIMIT"))
                            put("provider_semantic_status", kotlinx.serialization.json.JsonPrimitive(429))
                            if (reason in transportRefusals) put("provider_wait_no_wait_reason", kotlinx.serialization.json.JsonPrimitive(reason))
                        }
                    )
                )
            }
            coEvery { chats.commitAgentRun(any(), "run", any(), any(), any()) } coAnswers {
                committed.complete(firstArg())
                true
            }
            val coordinator = AgentRunCoordinator(context, chats, mockk(), settings, mockk())
            coordinator.resumeProviderRequests(listOf(request))
            val saved = withTimeout(10_000) { committed.await() }
            withTimeout(10_000) { coordinator.activeRuns.first { it.isEmpty() } }
            assertEquals("Retained source findings.", saved.content)
            val failure = requireNotNull(saved.timeline.lastOrNull { it.profileFailure != null }?.profileFailure)
            assertEquals("RATE_LIMIT", failure.category)
            assertEquals(kotlinx.serialization.json.JsonPrimitive(reason), failure.recoveryDetails?.get("provider_wait_no_wait_reason"))
            assertEquals(kotlinx.serialization.json.JsonPrimitive(false), failure.recoveryDetails?.get("provider_wait_persisted"))
            assertEquals(false, failure.retryAllowed)
            coVerify(exactly = 1) { chats.completeChat(any(), any(), any(), "run", any(), any()) }
        }
    }

    @Test fun localRecoveryRefusalPublishesTypedBlockedStateBeforeAnyRetainedCombinedFallback() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val chats = mockk<ChatRepository>().also { repository -> coEvery { repository.conversationPrivacyLocked(any()) } returns false }
        val settings = mockk<SettingRepository>()
        val platform = PlatformV2(uid = "p", name = "Primary", compatibleType = ClientType.CUSTOM, model = "model", apiUrl = "https://writer.example/v1")
        val user = MessageV2(id = 1, chatId = 1, content = "Original task", platformType = null)
        val answer = MessageV2(id = 2, chatId = 1, content = "", platformType = "p", currentRunId = "run", ownershipFence = 1)
        val request = AgentRunRequest("run", 1, answer, platform, listOf(user), listOf(listOf(answer)))
        val committed = CompletableDeferred<Pair<MessageV2, String>>()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(backgroundGeneration = false)
        coEvery { chats.markAgentRunRunning("run", any()) } returns true
        coEvery { chats.completeChat(any(), any(), any(), "run", any(), any()) } returns flow { throw ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED") }
        coEvery { chats.commitAgentRun(any(), "run", any(), any(), any()) } coAnswers {
            committed.complete(firstArg<MessageV2>() to thirdArg<String>())
            true
        }
        val coordinator = AgentRunCoordinator(context, chats, mockk(), settings, mockk())
        coordinator.resumeProviderRequests(listOf(request))
        val (saved, status) = withTimeout(10_000) { committed.await() }
        withTimeout(10_000) { coordinator.activeRuns.first { it.isEmpty() } }
        assertEquals("BLOCKED", status)
        val failure = requireNotNull(saved.timeline.last().profileFailure)
        assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", failure.category)
        assertEquals(false, failure.dispatched)
        assertEquals("lifecycle", failure.phase)
        coVerify(exactly = 0) { chats.publishCombinedRetainedFallback(any(), any()) }
    }

    @Test fun resumedWaitCannotAdoptPermissionChangesAfterItsIndependentLaunch() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val chats = mockk<ChatRepository>().also { repository -> coEvery { repository.conversationPrivacyLocked(any()) } returns false }
        val settings = mockk<SettingRepository>()
        val platform = PlatformV2(id = 3, uid = "p", name = "Primary", compatibleType = ClientType.CUSTOM, model = "model", apiUrl = "https://writer.example/v1")
        var permission = "accepted-wait"
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(backgroundGeneration = false)
        coEvery { settings.fetchPlatformV2s() } returns listOf(platform)
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } answers { permission }
        val authority = requireNotNull(ConfiguredDispatchAuthority.capture(settings, platform, 1).authority)
        val user = MessageV2(id = 1, chatId = 1, content = "Original task", platformType = null)
        val answer = MessageV2(id = 2, chatId = 1, content = "Retained paragraph.", platformType = "p", currentRunId = "run", ownershipFence = 1)
        val request = AgentRunRequest("run", 1, answer, platform, listOf(user), listOf(listOf(answer)), providerWaitAuthority = authority)
        val handedOff = CompletableDeferred<Unit>()
        val continuePreparation = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Triple<MessageV2, String, String?>>()
        var physicalAttempts = 0
        coEvery { chats.markAgentRunRunning("run", any()) } returns true
        coEvery { chats.commitAgentRun(any(), "run", any(), any(), any()) } coAnswers {
            committed.complete(Triple(firstArg(), thirdArg(), arg(4)))
            true
        }
        coEvery { chats.completeChat(any(), any(), any(), "run", any(), any()) } returns flow {
            handedOff.complete(Unit)
            continuePreparation.await()
            // This fresh guard must inherit the accepted wait's authority. Otherwise
            // it adopts the new permission revision and sends a stale queued request.
            val prepared = ConfiguredDispatchAuthority.capture(settings, platform, 1)
            withContext(prepared + PhysicalAttemptBoundary { physicalAttempts++ }) {
                PhysicalAttemptBoundary.dispatch()
            }
            emit(ApiState.Success("A stale request was sent."))
            emit(ApiState.Done)
        }
        val coordinator = AgentRunCoordinator(context, chats, mockk(), settings, mockk())
        coordinator.resumeProviderRequests(listOf(request))
        withTimeout(10_000) { handedOff.await() }
        permission = "revoked-after-handoff"
        continuePreparation.complete(Unit)
        val (saved, status, reason) = withTimeout(10_000) { committed.await() }
        withTimeout(10_000) { coordinator.activeRuns.first { it.isEmpty() } }
        assertEquals(0, physicalAttempts)
        assertEquals("INTERRUPTED", status)
        assertEquals("PROVIDER_WAIT_PERMISSION_CHANGED", reason)
        assertEquals("Retained paragraph.", saved.content)
    }

    @Test fun failedDurablePauseStillTerminalizesTheRunWithItsRetainedPartial() = runBlocking {
        for (pauseFailure in listOf(IllegalStateException("PROVIDER_WAIT_AUTHORITY_CHANGED"), IOException("Checkpoint unavailable"))) {
            val context = RuntimeEnvironment.getApplication()
            val chats = mockk<ChatRepository>().also { repository -> coEvery { repository.conversationPrivacyLocked(any()) } returns false }
            val settings = mockk<SettingRepository>()
            val waits = mockk<ProviderWaitStore>()
            val platform = PlatformV2(uid = "p", name = "Primary", compatibleType = ClientType.CUSTOM, model = "model", apiUrl = "https://writer.example/v1")
            val user = MessageV2(id = 1, chatId = 1, content = "Original task", platformType = null)
            val answer = MessageV2(id = 2, chatId = 1, content = "", platformType = "p", currentRunId = "run", ownershipFence = 1)
            val request = AgentRunRequest("run", 1, answer, platform, listOf(user), listOf(listOf(answer)))
            val decision = ProviderLimitDecision("TEST", "p", "model", 1000L, "scope", "PROVIDER_RATE_LIMITED", true)
            val deferred = ResearchProviderDeferred(ResearchDeferredStage("1:1", "EXTRACT", "prompt-digest", decision))
            val committed = CompletableDeferred<Triple<MessageV2, String, String?>>()
            val reconciled = CompletableDeferred<Unit>()
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(backgroundGeneration = false)
            coEvery { chats.markAgentRunRunning("run", any()) } returns true
            coEvery { chats.updateAgentMessage(any()) } returns Unit
            coEvery { chats.completeChat(any(), any(), any(), "run", any(), any()) } returns flow {
                emit(ApiState.Success("Previously completed research paragraph."))
                throw deferred
            }
            coEvery { waits.deferRunningResult(any(), decision, deferred.checkpoint) } throws pauseFailure
            coEvery { waits.reconcileTerminalCheckpointSafely("run") } coAnswers {
                reconciled.complete(Unit)
                false
            }
            coEvery { chats.commitAgentRun(any(), "run", any(), any(), any()) } coAnswers {
                committed.complete(Triple(firstArg(), thirdArg(), arg(4)))
                true
            }
            val coordinator = AgentRunCoordinator(context, chats, mockk(), settings, mockk(), providerWaits = waits)
            coordinator.resumeProviderRequests(listOf(request))
            val (saved, status, reason) = withTimeout(10_000) { committed.await() }
            withTimeout(10_000) { reconciled.await() }
            withTimeout(10_000) { coordinator.activeRuns.first { it.isEmpty() } }
            assertEquals("BLOCKED", status)
            assertEquals(pauseFailure.message, reason)
            assertEquals("Previously completed research paragraph.", saved.content)
            assertTrue(saved.timeline.any { it.runError == pauseFailure.message })
            val retained = requireNotNull(saved.timeline.last().profileFailure)
            assertEquals(kotlinx.serialization.json.JsonPrimitive("PERSISTENCE_OUTCOME_UNKNOWN"), retained.recoveryDetails?.get("provider_wait_no_wait_reason"))
            assertEquals(false, retained.retryAllowed)
            assertEquals(false, retained.requestReplayAllowed)
            coVerify(exactly = 1) { waits.deferRunningResult(any(), decision, deferred.checkpoint) }
            coVerify(exactly = 1) { waits.reconcileTerminalCheckpointSafely("run") }
            coVerify(exactly = 1) { chats.commitAgentRun(any(), "run", "BLOCKED", any(), pauseFailure.message) }
        }
    }
}
