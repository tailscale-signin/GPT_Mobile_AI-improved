package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayJobResult
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySavedJob
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCoordinatorRecoveryTest {
    private class Fixture(old: Boolean = false, github: Boolean = false) {
        val chats = mockk<ChatRepository>()
        val gateway = mockk<GatewayAPI>()
        private val settings = mockk<SettingRepository>()
        var state = AgentRunStatus.INTERRUPTED
        var error: String? = null
        val run = AgentRun(
            "run", 1, 1, 2, "profile", "LLAMA", "model", AgentRunStatus.INTERRUPTED,
            createdAt = System.currentTimeMillis() / 1000 - if (old) 3600 else 1, gatewayJobId = "saved-job", gatewayBaseUrl = "http://127.0.0.1:8090"
        )
        val complete = GatewayJobResult("saved-job", "completed", "Saved answer", "complete", completedAt = 42, job = GatewaySavedJob("model"))
        init {
            coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "profile", name = "Selected", model = "model", apiUrl = "http://127.0.0.1:8090"))
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(conversationGitHub = if (github) mapOf(1 to GitHubConversationContext("connection", "o", "r", "feature", "a".repeat(40), "profile")) else emptyMap())
            coEvery { chats.getRecoverableGatewayRuns() } answers { listOf(run.copy(status = state, terminalError = error)) }
            coEvery { chats.hasSavedGitHubTask(any()) } returns false
            coEvery { gateway.getCapabilities(any()) } returns GatewayCapabilities(supportsResultRecovery = true)
            coEvery { gateway.getJobResult("saved-job", any()) } returns complete
            coEvery { chats.restoreGatewayAnswer(any(), any(), any(), any(), any(), any()) } answers {
                state = arg(4)
                error = arg(5)
                true
            }
            coEvery { chats.finishInterruptedAgentRun(any(), any(), any(), any()) } answers {
                state = arg(1)
                error = arg(3)
                true
            }
        }
        fun coordinator() = AgentRunCoordinator(mockk(), chats, gateway, settings, mockk(relaxed = true))
    }

    @Test fun completedLongRunningJobIsReadBeforeAnyRecoveryAgeDecision() = runTest {
        val f = Fixture(old = true)
        coEvery { f.gateway.getCapabilities(any()) } throws IllegalStateException("Optional metadata unavailable")
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.COMPLETED, f.state)
        coVerify(exactly = 1) { f.gateway.getJobResult("saved-job", any()) }
        coVerify(exactly = 0) { f.gateway.getCapabilities(any()) }
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
    }

    @Test fun healthyLongRunningJobIsNeverCancelledJustBecauseTheAppRestartedLate() = runTest {
        val f = Fixture(old = true)
        coEvery { f.gateway.getJobResult("saved-job", any()) } returns GatewayJobResult("saved-job", "RUNNING")
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.INTERRUPTED, f.state)
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
        coVerify(exactly = 0) { f.chats.finishInterruptedAgentRun(any(), any(), any(), any()) }
    }

    @Test fun expiredUnreconciledJobParksWithoutGrantingReplayPermission() = runTest {
        val f = Fixture(old = true)
        coEvery { f.gateway.getJobResult("saved-job", any()) } returns null
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.BLOCKED, f.state)
        assertTrue(GatewayOutcomeRecovery.requiresReconciliation(f.error.orEmpty()))
        assertEquals("saved-job", f.run.gatewayJobId)
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
    }

    @Test fun parkedJobCanRecoverOnNetworkChangeWithoutReplayingWork() = runTest {
        val f = Fixture(old = true)
        val coordinator = f.coordinator()
        coEvery { f.gateway.getJobResult("saved-job", any()) } returns null
        coordinator.recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.BLOCKED, f.state)
        coEvery { f.gateway.getJobResult("saved-job", any()) } returns f.complete
        coordinator.recoverInterruptedGatewayRuns()
        coVerify(exactly = 1) { f.gateway.getJobResult("saved-job", any()) }
        dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.networkChanged()
        coordinator.recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.COMPLETED, f.state)
        coVerify(exactly = 2) { f.gateway.getJobResult("saved-job", any()) }
        coVerify(exactly = 1) { f.chats.restoreGatewayAnswer(any(), "saved-job", any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
    }

    @Test fun userBlockedOrCancelledJobsAreNeverAdoptedByAutomaticRecovery() = runTest {
        for (state in listOf(AgentRunStatus.CANCELED, AgentRunStatus.BLOCKED)) {
            val f = Fixture(old = true)
            f.state = state
            f.error = "APPROVAL_REQUIRED"
            f.coordinator().recoverInterruptedGatewayRuns()
            coVerify(exactly = 0) { f.gateway.getJobResult(any(), any()) }
            coVerify(exactly = 0) { f.chats.restoreGatewayAnswer(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun recoveredGithubModelAnswerRemainsUnverifiedTaskEvidence() = runTest {
        val f = Fixture(github = true)
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.EVIDENCE_MISSING, f.state)
        assertTrue(f.error.orEmpty().contains("GATEWAY_RECOVERED_RESULT_UNVERIFIED"))
        coVerify(exactly = 1) { f.chats.restoreGatewayAnswer("run", "saved-job", "Saved answer", 42, AgentRunStatus.EVIDENCE_MISSING, any()) }
    }

    @Test fun streamFailureWithUnknownOutcomeStillReadsItsSavedJob() = runTest {
        val f = Fixture()
        f.state = AgentRunStatus.FAILED
        f.error = "GATEWAY_OUTCOME_UNKNOWN: stream disconnected"
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.COMPLETED, f.state)
        coVerify(exactly = 1) { f.gateway.getJobResult("saved-job", any()) }
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
    }

    @Test fun isolatedGithubResultCanResumeVerificationWhileUnprovedResultsKeepMutationFence() = runTest {
        for (proved in listOf(false, true)) {
            val f = Fixture(github = true)
            f.state = AgentRunStatus.FAILED
            f.error = "GATEWAY_OUTCOME_UNKNOWN: disconnected"
            val job = if (proved) {
                GatewaySavedJob("model", jobId = "saved-job", executionIsolationSchema = 1, executionIsolationScope = "entire_job", executionIsolation = "github_client_owned", serverToolsAllowed = false)
            } else {
                GatewaySavedJob("model")
            }
            coEvery { f.gateway.getJobResult("saved-job", any()) } returns f.complete.copy(job = job)
            f.coordinator().recoverInterruptedGatewayRuns()
            assertEquals(AgentRunStatus.EVIDENCE_MISSING, f.state)
            assertEquals(!proved, GatewayOutcomeRecovery.requiresReconciliation(f.error.orEmpty()))
            assertEquals(proved, f.error.orEmpty().startsWith(GatewayOutcomeRecovery.ISOLATED_RESULT_PREFIX))
            assertTrue(!GatewayOutcomeRecovery.canReadSavedResult(f.run.copy(status = f.state, terminalError = f.error)))
            coVerify(exactly = 1) { f.chats.restoreGatewayAnswer(any(), "saved-job", any(), any(), AgentRunStatus.EVIDENCE_MISSING, any()) }
        }
    }

    @Test fun savedClientOwnedProofStillRequiresVerificationAfterGithubSettingsDisappear() = runTest {
        val f = Fixture(github = false)
        f.state = AgentRunStatus.FAILED
        f.error = "GATEWAY_OUTCOME_UNKNOWN: disconnected"
        coEvery { f.gateway.getJobResult("saved-job", any()) } returns f.complete.copy(
            job = GatewaySavedJob("model", jobId = "saved-job", executionIsolationSchema = 1, executionIsolationScope = "entire_job", executionIsolation = "github_client_owned", serverToolsAllowed = false)
        )
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.EVIDENCE_MISSING, f.state)
        assertTrue(f.error.orEmpty().startsWith(GatewayOutcomeRecovery.ISOLATED_RESULT_PREFIX))
        coVerify(exactly = 1) { f.chats.restoreGatewayAnswer("run", "saved-job", "Saved answer", 42, AgentRunStatus.EVIDENCE_MISSING, any()) }
        coVerify(exactly = 0) { f.chats.hasSavedGitHubTask(any()) }
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
    }

    @Test fun retainedLegacyGithubTaskMetadataPreventsFinalCompletionWithoutServerProof() = runTest {
        val f = Fixture(github = false)
        f.state = AgentRunStatus.FAILED
        f.error = "GATEWAY_OUTCOME_UNKNOWN: disconnected"
        coEvery { f.chats.hasSavedGitHubTask(any()) } answers {
            val run = firstArg<AgentRun>()
            run.chatId == 1 && run.runId == "run"
        }
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.EVIDENCE_MISSING, f.state)
        assertTrue(f.error.orEmpty().contains("GATEWAY_RECOVERED_RESULT_UNVERIFIED"))
        assertTrue(GatewayOutcomeRecovery.requiresReconciliation(f.error.orEmpty()))
        coVerify(exactly = 1) { f.chats.restoreGatewayAnswer("run", "saved-job", "Saved answer", 42, AgentRunStatus.EVIDENCE_MISSING, any()) }
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
    }

    @Test fun unreadableSavedTaskMetadataCannotAuthorizeFinalCompletion() = runTest {
        val f = Fixture(github = false)
        coEvery { f.chats.hasSavedGitHubTask(any()) } throws IllegalStateException("Saved metadata unavailable")
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.INTERRUPTED, f.state)
        coVerify(exactly = 0) { f.chats.restoreGatewayAnswer(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
    }

    @Test fun gatewayDisconnectThenAppRestartReadsTheOriginalJobAndAdoptsItOnce() = runTest {
        val f = Fixture()
        var reads = 0
        coEvery { f.gateway.getJobResult("saved-job", any()) } answers {
            if (++reads == 1) throw java.net.ConnectException("Connection unavailable")
            f.complete
        }
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.INTERRUPTED, f.state)
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(AgentRunStatus.COMPLETED, f.state)
        f.coordinator().recoverInterruptedGatewayRuns()
        assertEquals(2, reads)
        coVerify(exactly = 1) { f.chats.restoreGatewayAnswer(any(), "saved-job", any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.gateway.cancelJob(any(), any()) }
    }
}
