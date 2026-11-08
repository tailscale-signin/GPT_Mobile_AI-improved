package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native durable publication proof; a model status or approved patch alone cannot resume a chat. */
internal object GitHubEngineeringPublication {
    fun verified(task: EngineeringTaskRow, run: EngineeringRunRow?, operations: List<EngineeringGitOperationRow>): JsonObject? {
        if (task.state !in setOf("PUSHED", "CI_PENDING", "CI_VERIFIED", "AWAITING_HARDWARE_VERIFICATION", "HARDWARE_VERIFIED", "COMPLETED", "CI_FAILED")) return null
        if (run?.taskId != task.taskId || run.approvedActor.isNullOrBlank() || run.approvedAtEpochMs == null || run.approvalRevoked) return null
        val op = operations.singleOrNull()?.takeIf { it.taskId == task.taskId } ?: return null
        val sha = op.commitSha?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return null
        if (op.stage != GitSagaStage.REF_UPDATED.name || op.remoteRefSha != sha || op.expectedHeadSha != task.baseSha) return null
        return buildJsonObject {
            put("engineering_task_id", task.taskId)
            put("branch", task.workingBranch)
            put("sha", sha)
            put("parent_sha", task.baseSha)
            put("approval_state", "PUBLISHED")
            put("remote_state_verified", true)
            put("verification_source", "DURABLE_NATIVE_REF_UPDATED_RECEIPT")
        }
    }
}
