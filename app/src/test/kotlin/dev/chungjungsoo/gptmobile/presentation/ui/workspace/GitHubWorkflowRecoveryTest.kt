package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceDao
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubWorkflowRecoveryTest {
    private val workspace = mockk<WorkspaceRepository>()
    private val database = mockk<ChatDatabaseV2>()
    private val dao = mockk<WorkspaceDao>()
    private val persistence = mockk<AgentPersistenceDao>()
    private val run = AgentRun("run", 31, 42, 43, "profile", "provider", "model", AgentRunStatus.FAILED, terminalError = "SAVED_VIEW_UNAVAILABLE", ownershipFence = 7)
    private val failed = ToolEvent("event", "run", 0, "call", "github", "GitHub", "github_read", "github_read", """{"view_cursor":"saved:page:2"}""", null, null, ToolEventStatus.FAILED, true, error = "SAVED_VIEW_UNAVAILABLE")
    private val record = WorkspaceRecord("github-evidence:31:42:run:verification_final", "github_evidence", "Retained verification", "{}", 31, "run")
    private val proof = Json.parseToJsonElement("""{"evidence_retention_complete":true,"source_read_recovery":{"schema":"github.saved-read-recovery.v1","available":true,"read_only":true,"source_bound":true,"failed_call_id":"call","tool_name":"github_read","arguments":{"path":"A.kt","commit_sha":"${"a".repeat(40)}"},"source_receipt_id":"source","binding":"bound-task"}}""") as JsonObject

    @Before fun setup() {
        every { workspace.database } returns database
        every { workspace.dao } returns dao
        every { database.agentPersistenceDao() } returns persistence
        every { dao.observeGitHubEvidence(31) } returns flowOf(listOf(record))
        coEvery { persistence.recoveryRun("run") } returns run
        coEvery { persistence.getToolEventsForRun("run") } returns listOf(failed)
        coEvery { workspace.protectedSynthesisCheckpoint(record.id) } returns proof
        coEvery { dao.hasGitHubTaskForRun(31, "run") } returns true
    }

    @Test fun recoveryReloadsProtectedCurrentRunProofAndTheFailedRead() = runBlocking {
        val evidence = requireNotNull(GitHubWorkflowRecovery.load(workspace, run))
        assertEquals(run, evidence.run)
        assertEquals(listOf(TaskRecoveryAction.CONTINUE_RUN), evidence.snapshot.recovery)
        assertTrue(evidence.hasSavedTask)
        coVerify(exactly = 1) { workspace.protectedSynthesisCheckpoint(record.id) }
        coVerify(exactly = 1) { persistence.getToolEventsForRun("run") }
    }

    @Test fun aDifferentRunOrChatCannotSupplyTheSourceProof() = runBlocking {
        every { dao.observeGitHubEvidence(31) } returns flowOf(listOf(record.copy(runId = "other"), record.copy(chatId = 99)))
        val evidence = requireNotNull(GitHubWorkflowRecovery.load(workspace, run))
        assertTrue(evidence.snapshot.recovery.isEmpty())
        coVerify(exactly = 0) { workspace.protectedSynthesisCheckpoint(any()) }
    }

    @Test fun unreadableProtectedProofDoesNotFallBackToUnprotectedMetadata() = runBlocking {
        every { dao.observeGitHubEvidence(31) } returns flowOf(listOf(record.copy(payload = proof.toString())))
        coEvery { workspace.protectedSynthesisCheckpoint(record.id) } returns null
        val evidence = requireNotNull(GitHubWorkflowRecovery.load(workspace, run))
        assertTrue(evidence.snapshot.recovery.isEmpty())
        assertEquals(false, evidence.snapshot.evidenceComplete)
    }

    @Test fun changedAttemptIsRejectedBeforeLoadingItsProof() = runBlocking {
        coEvery { persistence.recoveryRun("run") } returns run.copy(ownershipFence = 8)
        assertNull(GitHubWorkflowRecovery.load(workspace, run))
        coVerify(exactly = 0) { workspace.protectedSynthesisCheckpoint(any()) }
    }

    @Test fun ownerChangeDuringEvidenceLoadInvalidatesTheRecovery() = runBlocking {
        coEvery { persistence.recoveryRun("run") } returnsMany listOf(run, run.copy(ownershipFence = 8))
        assertNull(GitHubWorkflowRecovery.load(workspace, run))
    }
}
