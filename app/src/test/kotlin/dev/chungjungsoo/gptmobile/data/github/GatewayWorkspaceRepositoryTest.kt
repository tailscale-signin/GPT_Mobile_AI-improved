package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayWorkspaceRepositoryTest {
    private val settings = mockk<SettingRepository>()
    private val client = mockk<GatewayEngineeringClient>()
    private val store = mockk<GatewayEngineeringStore>()
    private val conversations = mockk<GatewayConversationRepository>()
    private val history = mockk<GatewayJobClient>()
    private val commands = mockk<GatewayWorkspaceCommandStore>()
    private val profile = PlatformV2(uid = "original", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.8:8080/v1", model = "selected")
    private val sha = "a".repeat(40)
    private val job = GatewayEngineeringJob("eng_" + "b".repeat(32), "owner/repo", "work", state = "IMPLEMENT", revision = 4, createdAt = 0.0, deadline = 100.0, message = "Working", headSha = sha)
    private val request = GatewayEngineeringRequest("owner/repo", "work", "main", "Repair accepted task", "selected", chatId = 8, requestId = "saved-request")
    private var saved = GatewayEngineeringBinding(profile.uid, profile.apiUrl, request, job, principalId = "owner", gatewayId = "gateway")
    private val board = GatewayAgentWorkspace(1, job.id, "owner", "gateway", "owner/repo", "work", sha, 4, "IMPLEMENT", availableActions = listOf("review_draft"))
    private val args = buildJsonObject {
        put("draft_id", "draft")
        put("body", "private candidate note")
    }
    private val order = mutableListOf<String>()
    private val repository get() = GatewayEngineeringRepository(mockk(), settings, client, mockk(), store, conversations, history, mockk(), commands)

    @Before fun setup() {
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { conversations.requireUnprotectedConversation(8) } returns ConversationPrivacyContext(8, false)
        coEvery { store.bindings(any(), any()) } answers { listOf(saved) }
        coEvery { store.save(any()) } coAnswers {
            saved = firstArg()
            order += "checkpoint"
        }
        coEvery { client.principal(any(), requireReady = false) } returns "owner"
        coEvery { history.identity(any()) } returns GatewayScopeIdentity("gateway", "owner")
        coEvery { client.workspace(any(), job.id) } returns board
        coEvery { commands.retain(any(), any(), args) } coAnswers {
            order += "encrypted-arguments"
            "protected-reference"
        }
    }

    @Test fun encryptedArgumentsAndCommandIdentityAreSavedBeforeDispatch() = runTest {
        coEvery { client.workspaceCommand(any(), any(), args) } coAnswers {
            val command = secondArg<GatewayWorkspacePendingCommand>()
            order += "dispatch"
            assertEquals(command, saved.workspaceCommand)
            assertEquals("protected-reference", command.argumentsReference)
            assertFalse(Json.encodeToString(saved).contains("private candidate note"))
            GatewayWorkspaceCommandReceipt(1, job.id, command.commandId, sha, "QUEUED")
        }
        val result = repository.workspaceCommand(request.requestId, board, "review_draft", args)
        assertEquals(listOf("encrypted-arguments", "checkpoint", "dispatch", "checkpoint"), order)
        assertEquals(result, saved.workspaceCommand?.receipt)
    }

    @Test fun lostResponseRetainsIdentityAndBlocksAnotherMutation() = runTest {
        coEvery { client.workspaceCommand(any(), any(), args) } throws IOException("lost response")
        val repository = repository
        assertTrue(runCatching { repository.workspaceCommand(request.requestId, board, "review_draft", args) }.isFailure)
        val pending = requireNotNull(saved.workspaceCommand)
        assertNull(pending.receipt)
        assertNotNull(pending.argumentsReference)
        assertTrue(runCatching { repository.workspaceCommand(request.requestId, board, "review_draft", args) }.isFailure)
        assertEquals(pending, saved.workspaceCommand)
        coVerify(exactly = 1) { client.workspaceCommand(any(), any(), any()) }
    }

    @Test fun readbackSettlesOnlyTheSavedCommandWithoutPosting() = runTest {
        val pending = GatewayWorkspacePendingCommand("saved-command", job.id, sha, 4, "review_draft", argumentsReference = "protected-reference")
        saved = saved.copy(workspaceCommand = pending)
        val receipt = GatewayWorkspaceCommandReceipt(1, job.id, pending.commandId, sha, "COMPLETED")
        coEvery { client.workspaceCommandStatus(any(), pending) } returns receipt
        assertEquals(receipt, repository.reconcileWorkspaceCommand(request.requestId))
        assertTrue(saved.workspaceCommand?.settled == true)
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
    }

    @Test fun explicitRetryPreservesTheEntireOriginalEnvelopeAfterConfirmedAbsence() = runTest {
        val receipt = GatewayWorkspaceCommandReceipt(1, job.id, "saved-command", sha, "NOT_FOUND", confirmedAbsent = true)
        val pending = GatewayWorkspacePendingCommand(receipt.commandId, job.id, sha, 4, "review_draft", receipt, "protected-reference")
        saved = saved.copy(workspaceCommand = pending)
        coEvery { commands.recover(any(), pending) } returns args
        coEvery { client.workspaceCommand(any(), any(), args) } coAnswers {
            val sent = secondArg<GatewayWorkspacePendingCommand>()
            assertEquals(pending.copy(receipt = null), sent)
            assertNull(saved.workspaceCommand?.receipt)
            GatewayWorkspaceCommandReceipt(1, job.id, sent.commandId, sha, "QUEUED")
        }
        repository.retryWorkspaceCommand(request.requestId)
        assertEquals(pending.commandId, saved.workspaceCommand?.commandId)
        assertEquals("QUEUED", saved.workspaceCommand?.receipt?.status)
        coVerify(exactly = 0) { commands.retain(any(), any(), any()) }
    }

    @Test fun sourceOrObservationMovementPreventsWriteAndRetry() = runTest {
        coEvery { client.workspace(any(), job.id) } returns board.copy(revision = 5)
        assertTrue(runCatching { repository.workspaceCommand(request.requestId, board, "review_draft", args) }.isFailure)
        saved = saved.copy(workspaceCommand = GatewayWorkspacePendingCommand("saved-command", job.id, sha, 4, "review_draft", GatewayWorkspaceCommandReceipt(1, job.id, "saved-command", sha, "NOT_FOUND", confirmedAbsent = true), "protected-reference"))
        assertTrue(runCatching { repository.retryWorkspaceCommand(request.requestId) }.isFailure)
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
        coVerify(exactly = 0) { commands.recover(any(), any()) }
    }

    @Test fun protectedConversationAndChangedIdentityCannotReadOrSendWorkspace() = runTest {
        coEvery { conversations.requireUnprotectedConversation(8) } throws IllegalStateException("Protected chat")
        assertTrue(runCatching { repository.workspace(request.requestId) }.isFailure)
        coVerify(exactly = 0) { client.workspace(any(), any()) }
        coEvery { conversations.requireUnprotectedConversation(8) } returns ConversationPrivacyContext(8, false)
        coEvery { history.identity(any()) } returns GatewayScopeIdentity("different", "owner")
        assertTrue(runCatching { repository.workspaceCommand(request.requestId, board, "review_draft", args) }.isFailure)
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
        coVerify(exactly = 0) { commands.retain(any(), any(), any()) }
    }

    @Test fun invalidArgumentsFailBeforeSavingADispatchCheckpoint() = runTest {
        assertTrue(runCatching { repository.workspaceCommand(request.requestId, board, "review_draft", buildJsonObject { put("head_sha", "override") }) }.isFailure)
        assertNull(saved.workspaceCommand)
        coVerify(exactly = 0) { store.save(any()) }
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
    }

    @Test fun localDismissalRequiresFreshAbsenceAndMovedObservation() = runTest {
        val absent = GatewayWorkspaceCommandReceipt(1, job.id, "saved-command", sha, "NOT_FOUND", confirmedAbsent = true)
        val command = GatewayWorkspacePendingCommand(absent.commandId, job.id, sha, 4, "review_draft", absent)
        saved = saved.copy(workspaceCommand = command)
        coEvery { client.workspaceCommandStatus(any(), command) } returns absent
        assertTrue(runCatching { repository.dismissAbsentWorkspaceCommand(request.requestId) }.isFailure)
        assertFalse(saved.workspaceCommand!!.settled)
        coEvery { client.workspace(any(), job.id) } returns board.copy(revision = 5)
        repository.dismissAbsentWorkspaceCommand(request.requestId)
        assertTrue(saved.workspaceCommand!!.dismissedAbsent)
        assertEquals("NOT_FOUND", saved.workspaceCommand!!.receipt!!.status)
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
    }

    @Test fun existingCommandCannotBeDismissedAsAbsent() = runTest {
        val absent = GatewayWorkspaceCommandReceipt(1, job.id, "saved-command", sha, "NOT_FOUND", confirmedAbsent = true)
        val command = GatewayWorkspacePendingCommand(absent.commandId, job.id, sha, 4, "review_draft", absent)
        saved = saved.copy(workspaceCommand = command)
        coEvery { client.workspaceCommandStatus(any(), command) } returns absent.copy(status = "RUNNING", confirmedAbsent = false)
        assertTrue(runCatching { repository.dismissAbsentWorkspaceCommand(request.requestId) }.isFailure)
        assertFalse(saved.workspaceCommand!!.dismissedAbsent)
        coVerify(exactly = 0) { store.save(any()) }
    }

    @Test fun readbackReturnsPrivateEvidenceWithoutCopyingItIntoBindings() = runTest {
        val command = GatewayWorkspacePendingCommand("saved-command", job.id, sha, 4, "review_draft")
        saved = saved.copy(workspaceCommand = command)
        val receipt = GatewayWorkspaceCommandReceipt(1, job.id, command.commandId, sha, "COMPLETED", result = buildJsonObject { put("body", "private candidate note") })
        coEvery { client.workspaceCommandStatus(any(), command) } returns receipt
        assertEquals(receipt, repository.reconcileWorkspaceCommand(request.requestId))
        assertTrue(saved.workspaceCommand!!.settled)
        assertNull(saved.workspaceCommand!!.receipt!!.result)
        assertFalse(Json.encodeToString(saved).contains("private candidate note"))
    }

    @Test fun changedCredentialsDuringProtectedCheckpointPreventDispatch() = runTest {
        coEvery { commands.retain(any(), any(), args) } coAnswers {
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile.copy(token = "changed"))
            "protected-reference"
        }
        assertTrue(runCatching { repository.workspaceCommand(request.requestId, board, "review_draft", args) }.isFailure)
        assertNotNull(saved.workspaceCommand)
        assertNull(saved.workspaceCommand!!.receipt)
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
    }

    @Test fun acknowledgedUnknownCommandKeepsItsOriginalIdentityWithoutReplay() = runTest {
        val unknown = GatewayWorkspaceCommandReceipt(1, job.id, "original-unknown", sha, "UNKNOWN")
        val original = GatewayWorkspacePendingCommand(unknown.commandId, job.id, sha, 4, "review_draft", unknown, "protected-original")
        saved = saved.copy(workspaceCommand = original)
        coEvery { client.workspaceCommandStatus(any(), original) } returns unknown
        coEvery { client.workspace(any(), job.id) } returns board.copy(state = "NEEDS_INPUT", revision = 5)
        repository.acknowledgeUnknownWorkspaceCommand(request.requestId)
        assertNull(saved.workspaceCommand)
        assertEquals(original, saved.workspaceUnresolvedCommands.single())
        assertFalse(saved.workspaceUnresolvedCommands.single().settled)
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
        coVerify(exactly = 0) { commands.recover(any(), any()) }
    }

    @Test fun runningCommandCannotBeAcknowledgedAsUnknown() = runTest {
        val unknown = GatewayWorkspaceCommandReceipt(1, job.id, "original-unknown", sha, "UNKNOWN")
        val original = GatewayWorkspacePendingCommand(unknown.commandId, job.id, sha, 4, "review_draft", unknown, "protected-original")
        saved = saved.copy(workspaceCommand = original)
        coEvery { client.workspaceCommandStatus(any(), original) } returns unknown.copy(status = "RUNNING")
        assertTrue(runCatching { repository.acknowledgeUnknownWorkspaceCommand(request.requestId) }.isFailure)
        assertEquals(original, saved.workspaceCommand)
        assertTrue(saved.workspaceUnresolvedCommands.isEmpty())
        coVerify(exactly = 0) { store.save(any()) }
    }

    @Test fun unresolvedArchiveBlocksCodingCommandsEvenIfTheJobWasResumedElsewhere() = runTest {
        val unknown = GatewayWorkspaceCommandReceipt(1, job.id, "original-unknown", sha, "UNKNOWN")
        saved = saved.copy(workspaceUnresolvedCommands = listOf(GatewayWorkspacePendingCommand(unknown.commandId, job.id, sha, 4, "agent_start", unknown, "protected-original")))
        val current = board.copy(availableActions = listOf("worker_wave"))
        assertTrue(runCatching { repository.workspaceCommand(request.requestId, current, "worker_wave", buildJsonObject {}) }.isFailure)
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
        coVerify(exactly = 0) { commands.retain(any(), any(), any()) }
    }

    @Test fun archivedReadbackSettlesTheOriginalWithoutReplacingANewerCommand() = runTest {
        val unknown = GatewayWorkspaceCommandReceipt(1, job.id, "original-unknown", sha, "UNKNOWN")
        val original = GatewayWorkspacePendingCommand(unknown.commandId, job.id, sha, 4, "agent_start", unknown, "protected-original")
        val current = GatewayWorkspacePendingCommand("new-maintenance", job.id, sha, 5, "agent_status")
        saved = saved.copy(workspaceCommand = current, workspaceUnresolvedCommands = listOf(original))
        coEvery { client.workspaceCommandStatus(any(), original) } returns unknown.copy(status = "COMPLETED")
        repository.reconcileWorkspaceCommand(request.requestId, original.commandId)
        assertEquals(current, saved.workspaceCommand)
        assertTrue(saved.workspaceUnresolvedCommands.single().settled)
        assertEquals(original.argumentsReference, saved.workspaceUnresolvedCommands.single().argumentsReference)
        coVerify(exactly = 0) { client.workspaceCommand(any(), any(), any()) }
    }
}
