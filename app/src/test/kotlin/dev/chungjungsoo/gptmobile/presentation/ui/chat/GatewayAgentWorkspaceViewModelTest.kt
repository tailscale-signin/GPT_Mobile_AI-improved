package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.github.GatewayAgentWorkspace
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequest
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringSettings
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkspaceCommandReceipt
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkspacePendingCommand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayAgentWorkspaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<GatewayEngineeringRepository>()
    private val sha = "a".repeat(40)
    private val job = GatewayEngineeringJob("eng_" + "b".repeat(32), "owner/repo", "work", state = "IMPLEMENT", revision = 2, createdAt = 0.0, deadline = 100.0, message = "Working", headSha = sha)
    private val request = GatewayEngineeringRequest("owner/repo", "work", "main", "Repair accepted task", "selected", chatId = 8, requestId = "original")
    private var saved = GatewayEngineeringBinding("original", "http://gateway/v1", request, job, principalId = "owner", gatewayId = "gateway")
    private val board = GatewayAgentWorkspace(1, job.id, "owner", "gateway", "owner/repo", "work", sha, 2, "IMPLEMENT", availableActions = listOf("worker_status"))
    private lateinit var model: GatewayEngineeringViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.bindings(any()) } answers { if (firstArg<Int?>() in listOf(null, 8)) listOf(saved) else emptyList() }
        coEvery { repository.options() } returns GatewayEngineeringSettings()
        coEvery { repository.observe(request.requestId) } answers { saved }
        coEvery { repository.workspace(request.requestId) } returns board
        model = GatewayEngineeringViewModel(repository, dispatcher)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun workspaceResultCannotEnterADifferentConversation() = runTest(dispatcher) {
        model.bind(8)
        runCurrent()
        model.openWorkspace(saved)
        model.bind(9)
        runCurrent()
        assertNull(model.state.value.workspace)
        assertNull(model.state.value.workspaceRequestId)
        assertTrue(model.state.value.bindings.isEmpty())
        assertFalse(model.state.value.busy)
    }

    @Test fun uncertainCommandRetainsItsCheckpointAndCannotBeDuplicatedByTwoTaps() = runTest(dispatcher) {
        model.bind(8)
        runCurrent()
        model.openWorkspace(saved)
        runCurrent()
        val pending = GatewayWorkspacePendingCommand("pending", job.id, sha, 2, "worker_status")
        coEvery { repository.workspaceCommand(request.requestId, board, "worker_status", any()) } coAnswers {
            saved = saved.copy(workspaceCommand = pending)
            throw IOException("Lost response")
        }
        model.workspaceCommand(saved, "worker_status")
        model.workspaceCommand(saved, "worker_status")
        runCurrent()
        assertEquals(pending, model.state.value.bindings.single().workspaceCommand)
        assertEquals("Lost response", model.state.value.error)
        assertFalse(model.state.value.busy)
        coVerify(exactly = 1) { repository.workspaceCommand(any(), any(), any(), any()) }
    }

    @Test fun foregroundWorkspaceRefreshOnlyReconcilesTheSavedCommand() = runTest(dispatcher) {
        model.bind(8)
        runCurrent()
        model.openWorkspace(saved)
        runCurrent()
        val command = GatewayWorkspacePendingCommand("pending", job.id, sha, 2, "worker_status")
        saved = saved.copy(workspaceCommand = command)
        val receipt = GatewayWorkspaceCommandReceipt(1, job.id, command.commandId, sha, "COMPLETED", result = JsonObject(emptyMap()))
        coEvery { repository.reconcileWorkspaceCommand(request.requestId) } coAnswers {
            saved = saved.copy(workspaceCommand = command.copy(receipt = receipt))
            receipt
        }
        model.setVisible("workspace", 8, true)
        try {
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(model.state.value.bindings.single().workspaceCommand?.settled == true)
            coVerify(exactly = 1) { repository.reconcileWorkspaceCommand(request.requestId) }
            coVerify(exactly = 0) { repository.workspaceCommand(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.retryWorkspaceCommand(any()) }
        } finally {
            model.setVisible("workspace", 8, false)
        }
    }

    @Test fun closingWorkspaceClearsItsSourceAndCandidateViews() = runTest(dispatcher) {
        model.bind(8)
        runCurrent()
        model.openWorkspace(saved)
        runCurrent()
        assertEquals(board, model.state.value.workspace)
        model.closeWorkspace()
        assertNull(model.state.value.workspaceRequestId)
        assertNull(model.state.value.workspace)
        assertNull(model.state.value.workspaceCandidate)
        assertNull(model.state.value.workspaceDiff)
    }
}
