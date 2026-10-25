package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.AcceptedResearchTask
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

/** Identity belongs to the persisted user request; stage instructions never replace it. */
@Serializable
data class AcceptedTaskRef(
    val taskId: String,
    val rootWorkId: String,
    val chatId: Int,
    val originatingUserMessageId: Int,
    val originalRequestDigest: String
) {
    fun validate(original: MessageV2) {
        require(original.platformType == null && this == from(original)) { "ACCEPTED_TASK_ORIGINAL_MISMATCH" }
    }

    fun validate(task: AcceptedResearchTask) {
        require(
            task.taskId == taskId &&
                task.rootWorkId == rootWorkId &&
                task.chatId == chatId &&
                task.originatingUserMessageId == originatingUserMessageId &&
                researchHash(task.taskText) == originalRequestDigest
        ) { "ACCEPTED_TASK_REGISTRY_MISMATCH" }
    }

    companion object {
        fun from(original: MessageV2): AcceptedTaskRef {
            require(original.platformType == null && original.content.isNotBlank()) { "ACCEPTED_TASK_ORIGINAL_UNAVAILABLE" }
            val root = "${original.chatId}:${original.id}"
            return AcceptedTaskRef(researchHash("task:$root"), root, original.chatId, original.id, researchHash(original.content))
        }
    }
}

/** A model-facing envelope with an independently validated immutable parent task. */
@Serializable
data class AgentStageRequest(
    val acceptedTask: AcceptedTaskRef,
    val role: CombinedRunRole,
    val stagePrompt: String,
    val frozenInputDigest: String,
    val snapshotId: String,
    val profileUid: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val writerRequestPlan: CombinedWriterRequestPlan? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val writerContext: CombinedWriterContext? = null
)

/** Legacy recovery may bind only to the original stored in its validated frozen snapshot. */
fun CombinedInputSnapshot.bindAcceptedStage(): AgentStageRequest {
    require(version in 1..6 && prompt.isNotBlank() && profileUid.isNotBlank()) { "COMBINED_STAGE_INPUT_UNAVAILABLE" }
    if (version >= 6) require(snapshotId.isNotBlank() && inputDigest == contentDigest()) { "COMBINED_SNAPSHOT_INTEGRITY_FAILED" }
    CombinedClaimInventoryWire.validatePrompt(prompt)
    writerRequestPlan?.validate(this)
    writerContext?.let { require(it.bindPrompt(prompt, userMessage) == prompt) { "COMBINED_WRITER_CONTEXT_DOCUMENT_CHANGED" } }
    val reference = acceptedTask ?: combined?.acceptedTask ?: AcceptedTaskRef.from(userMessage)
    reference.validate(userMessage)
    combined?.let { policy ->
        require(
            policy.requested.userPrompt == userMessage.content &&
                profileUid in policy.requested.participantUids &&
                (policy.profileUid == null || policy.profileUid == profileUid) &&
                policy.chatId == userMessage.chatId
        ) { "COMBINED_STAGE_POLICY_MISMATCH" }
        policy.acceptedTask?.let { require(it == reference) { "COMBINED_STAGE_TASK_MISMATCH" } }
    }
    return AgentStageRequest(reference, combined?.role ?: CombinedRunRole.SYNTHESIS, prompt, inputDigest.ifBlank { contentDigest() }, snapshotId, profileUid, writerRequestPlan, writerContext)
}
