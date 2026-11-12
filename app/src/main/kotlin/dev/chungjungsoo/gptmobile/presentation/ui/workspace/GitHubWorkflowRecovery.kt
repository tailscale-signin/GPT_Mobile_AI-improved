package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class GitHubWorkflowRecoveryEvidence(
    val run: AgentRun,
    val snapshot: GitHubWorkflowSnapshot,
    val hasSavedTask: Boolean,
    val sourceConnectionUid: String? = null
)

/** Recheck durable run ownership and protected evidence at the moment a recovery control is used. */
internal object GitHubWorkflowRecovery {
    suspend fun load(workspace: WorkspaceRepository, expected: AgentRun): GitHubWorkflowRecoveryEvidence? {
        val persistence = workspace.database.agentPersistenceDao()
        val run = persistence.recoveryRun(expected.runId)?.takeIf { sameAttempt(expected, it) } ?: return null
        val events = persistence.getToolEventsForRun(run.runId)
        val record = workspace.dao.observeGitHubEvidence(run.chatId).first()
            .filter { it.kind == "github_evidence" && it.chatId == run.chatId && it.runId == run.runId && ":verification_" in it.id }
            .maxWithOrNull(compareBy<WorkspaceRecord> { it.updatedAt }.thenBy { it.id })
        val verification = try {
            record?.let { workspace.protectedSynthesisCheckpoint(it.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        // A new owner, retry, or terminal transition invalidates the evidence we just loaded.
        if (persistence.recoveryRun(run.runId)?.let { sameAttempt(run, it) } != true) return null
        return GitHubWorkflowRecoveryEvidence(
            run,
            GitHubWorkflowPresentation.snapshot(run, events, eventWindowComplete = true, verification = verification, evidenceReadFailed = record != null && verification == null),
            workspace.dao.hasGitHubTaskForRun(run.chatId, run.runId),
            ((verification?.get("source_read_recovery") as? JsonObject)?.get("failed_call_id") as? JsonPrimitive)?.contentOrNull
                ?.let { failedCallId -> events.singleOrNull { it.runId == run.runId && it.callId == failedCallId }?.connectionUidSnapshot }
        )
    }

    fun sameAttempt(expected: AgentRun, current: AgentRun): Boolean =
        expected.runId == current.runId &&
            expected.chatId == current.chatId &&
            expected.userMessageId == current.userMessageId &&
            expected.assistantMessageId == current.assistantMessageId &&
            expected.profileUid == current.profileUid &&
            expected.modelSnapshot == current.modelSnapshot &&
            expected.ownershipFence == current.ownershipFence &&
            expected.status == current.status &&
            expected.terminalError == current.terminalError
}
