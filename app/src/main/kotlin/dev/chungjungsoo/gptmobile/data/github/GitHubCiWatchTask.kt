package dev.chungjungsoo.gptmobile.data.github

import dagger.Lazy
import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.data.agent.TaskResumeDescriptor
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringApiException
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A native CI subscription can wake only the saved diagnosis that registered it. */
@Serializable
internal data class GitHubCiWatchTask(
    val runId: String,
    val sourceMessageId: Int,
    val ref: String,
    val model: String,
    val profileContract: String
) {
    suspend fun deliver(watch: GitHubCheckWatch, database: ChatDatabaseV2, settings: SettingRepository, api: GitHubEngineeringApi, queue: Lazy<DurablePromptQueue>): Boolean {
        val run = database.agentRunDao().getById(runId) ?: return true
        if (run.chatId != watch.chatId || run.profileUid != watch.profileUid || run.userMessageId != sourceMessageId || run.modelSnapshot != model) return true
        if (run.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING)) return false
        if (run.status !in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING)) return true
        val binding = capture(runId, watch.chatId, watch.profileUid, watch.owner, watch.repo, database, settings) ?: return true
        if (binding != this) return true
        val room = database.agentPersistenceDao().getChatRoom(watch.chatId) ?: return true
        if (room.isArchived || room.isTemporary || room.activePlatform != listOf(watch.profileUid)) return true
        val scope = settings.getFeatureSettings().conversationGitHub[watch.chatId] ?: return true
        if (scope.ref != ref ||
            scope.connectionUid != watch.connectionUid ||
            scope.mainProfileUid != watch.profileUid ||
            !scope.owner.equals(watch.owner, true) ||
            !scope.repo.equals(watch.repo, true)
        ) {
            return true
        }
        val head = try {
            val root = GitHubEngineeringApi.root(watch.owner, watch.repo)
            Json.parseToJsonElement(api.request("$root/commits/${GitHubEngineeringApi.segment(ref)}")).jsonObject["sha"]
        } catch (failure: EngineeringApiException) {
            if (failure.status in setOf(401, 403, 404) && failure.retryAtEpochMs == null) return true
            throw failure
        }
        if (head != JsonPrimitive(watch.headSha)) return true
        val key = "github-ci-task:${watch.id}:${watch.headSha}:$sourceMessageId"
        val payload = PendingPromptPayload(
            profileUids = listOf(watch.profileUid),
            models = mapOf(watch.profileUid to model),
            tools = ChatMcpToolConfig(allowAllByDefault = false, enabledToolIds = setOf(watch.connectionUid), github = scope.copy(commitSha = watch.headSha)),
            recovery = TaskResumeDescriptor("${watch.chatId}:$sourceMessageId", sourceMessageId, runId, TaskRecoveryAction.CONTINUE_RUN)
        )
        queue.get().enqueue(watch.chatId, "Continue", payload, key)
        return true
    }

    companion object {
        suspend fun capture(runId: String, chatId: Int, profileUid: String, owner: String, repo: String, database: ChatDatabaseV2, settings: SettingRepository): GitHubCiWatchTask? {
            val run = database.agentRunDao().getById(runId)?.takeIf { it.chatId == chatId && it.profileUid == profileUid } ?: return null
            val source = database.agentPersistenceDao().rawRecoveryMessage(run.userMessageId) ?: return null
            if (source.chatId != chatId || source.platformType != null || !GitHubCiObservationReport.matches(source.content)) return null
            val scope = settings.getFeatureSettings().conversationGitHub[chatId] ?: return null
            if (scope.mainProfileUid != profileUid || !scope.owner.equals(owner, true) || !scope.repo.equals(repo, true)) return null
            val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == profileUid && it.enabled && it.model == run.modelSnapshot } ?: return null
            val contract = GitHubApprovalBinding.profileContract(profile.compatibleType.name, profile.apiUrl, profile.providerConnectionUid, profile.reasoning)
            return GitHubCiWatchTask(runId, run.userMessageId, scope.ref, profile.model, contract)
        }
    }
}
