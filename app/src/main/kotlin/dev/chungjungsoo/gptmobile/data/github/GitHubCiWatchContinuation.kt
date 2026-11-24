package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Local queue identity for a watched diagnosis. It grants no new tools or budget. */
@Serializable
data class GitHubCiWatchContinuation(
    val watchId: String,
    val bindingDigest: String,
    val profileContract: String
) {
    internal fun task(payload: PendingPromptPayload): GitHubCiWatchTask? {
        val descriptor = payload.recovery ?: return null
        val github = payload.tools.github ?: return null
        val model = payload.models[github.mainProfileUid] ?: return null
        return GitHubCiWatchTask(descriptor.generationId, descriptor.sourceMessageId, github.ref, model, profileContract)
    }

    internal fun matches(prompt: PendingPrompt, watch: GitHubCheckWatch, prior: AgentRun, original: MessageV2, room: ChatRoomV2): Boolean {
        val payload = prompt.details()
        val descriptor = payload.recovery ?: return false
        val github = payload.tools.github ?: return false
        val task = task(payload) ?: return false
        val key = "${prompt.chatId}:github-ci-task:${watch.id}:${watch.headSha}:${original.id}"
        return watch.id == watchId &&
            watch.allChecks &&
            watch.status in setOf("PASSED", "FAILED", "SKIPPED") &&
            bindingDigest == binding(watch, task) &&
            prompt.id == UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString() &&
            prompt.text == "Continue" &&
            payload.attachments.isEmpty() &&
            prompt.chatId == watch.chatId &&
            room.id == watch.chatId &&
            !room.isArchived &&
            !room.isTemporary &&
            room.activePlatform == listOf(watch.profileUid) &&
            payload.profileUids == listOf(watch.profileUid) &&
            payload.models == mapOf(watch.profileUid to prior.modelSnapshot) &&
            payload.tools == ChatMcpToolConfig(allowAllByDefault = false, enabledToolIds = setOf(watch.connectionUid), github = github) &&
            github.connectionUid == watch.connectionUid &&
            github.mainProfileUid == watch.profileUid &&
            github.owner.equals(watch.owner, true) &&
            github.repo.equals(watch.repo, true) &&
            github.commitSha == watch.headSha &&
            prior.runId == descriptor.generationId &&
            prior.chatId == watch.chatId &&
            prior.profileUid == watch.profileUid &&
            prior.userMessageId == original.id &&
            prior.status in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING) &&
            original.chatId == watch.chatId &&
            original.platformType == null &&
            original.id == descriptor.sourceMessageId &&
            GitHubCiObservationReport.matches(original.content) &&
            descriptor.rootWorkId == "${watch.chatId}:${original.id}" &&
            descriptor.action == TaskRecoveryAction.CONTINUE_RUN &&
            descriptor.continuationPrefixDigest == null &&
            !descriptor.allowNewToolBudget &&
            !descriptor.allowUndispatchedBudgetRetry
    }

    companion object {
        internal fun capture(watch: GitHubCheckWatch, task: GitHubCiWatchTask): GitHubCiWatchContinuation {
            require(task in watch.waitingTasks)
            return GitHubCiWatchContinuation(watch.id, binding(watch, task), task.profileContract)
        }

        internal fun decode(row: WorkspaceRecord?): GitHubCheckWatch? {
            if (row == null || row.kind != "github_checks") return null
            val watch = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<GitHubCheckWatch>(row.payload) }.getOrNull() ?: return null
            return watch.takeIf { it.id == row.id && it.chatId == row.chatId }
        }

        /** Exclude mutable display/delivery fields; bind the registered task and original authority. */
        private fun binding(watch: GitHubCheckWatch, task: GitHubCiWatchTask): String = GitHubRepositoryStateStore.digest(
            JsonArray(
                listOf(
                    "github-ci-watch-continuation-v1",
                    watch.id,
                    watch.chatId.toString(),
                    watch.connectionUid,
                    watch.profileUid,
                    watch.owner,
                    watch.repo,
                    watch.pullNumber.toString(),
                    watch.runId.toString(),
                    watch.headSha,
                    watch.connectionUpdatedAt.toString(),
                    watch.startedAt.toString(),
                    watch.permissionRevision,
                    watch.accountScopeHash,
                    watch.followPullRequest.toString(),
                    watch.runAttempt.toString(),
                    watch.allChecks.toString(),
                    task.runId,
                    task.sourceMessageId.toString(),
                    task.ref,
                    task.model,
                    task.profileContract
                ).map { JsonPrimitive(it) }
            ).toString()
        )
    }
}
