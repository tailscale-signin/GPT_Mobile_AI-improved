package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayWorkspaceCommandStoreTest {
    private val workspace = mockk<WorkspaceRepository>()
    private val store = GatewayWorkspaceCommandStore(workspace)
    private val command = GatewayWorkspacePendingCommand("command-1", "eng_" + "a".repeat(32), "b".repeat(40), 4, "review_draft")
    private val binding = GatewayEngineeringBinding("selected", "http://gateway/v1", GatewayEngineeringRequest("owner/repo", "work", "main", "Accepted task", "model", chatId = 8, requestId = "saved"), principalId = "owner", gatewayId = "gateway")
    private val arguments = buildJsonObject { put("body", "private note") }

    @Test fun originalArgumentsUseAppendOnlyProtectedStorage() = runTest {
        var retained: JsonObject? = null
        coEvery { workspace.saveProtectedSynthesisCheckpoint(any(), 8, command.jobId, any(), true, any(), any(), any()) } coAnswers {
            retained = arg(3)
            true
        }
        coEvery { workspace.protectedSynthesisCheckpoint(any()) } answers { retained }
        val reference = store.retain(binding, command, arguments)
        assertTrue(reference.startsWith("gateway-workspace-command:"))
        assertEquals(arguments, store.recover(binding, command.copy(argumentsReference = reference)))
    }

    @Test fun failedProtectedStorageCannotProduceADispatchReference() = runTest {
        coEvery { workspace.saveProtectedSynthesisCheckpoint(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        assertTrue(runCatching { store.retain(binding, command, arguments) }.isFailure)
    }

    @Test fun recoveredArgumentsCannotMoveToAnotherAccountSourceOrRevision() = runTest {
        var retained: JsonObject? = null
        coEvery { workspace.saveProtectedSynthesisCheckpoint(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            retained = arg(3)
            true
        }
        coEvery { workspace.protectedSynthesisCheckpoint(any()) } answers { retained }
        val saved = command.copy(argumentsReference = store.retain(binding, command, arguments))
        assertTrue(runCatching { store.recover(binding.copy(principalId = "other"), saved) }.isFailure)
        assertTrue(runCatching { store.recover(binding, saved.copy(headSha = "c".repeat(40))) }.isFailure)
        assertTrue(runCatching { store.recover(binding, saved.copy(revision = 5)) }.isFailure)
        assertTrue(runCatching { store.recover(binding.copy(endpoint = "http://another/v1"), saved) }.isFailure)
    }
}
