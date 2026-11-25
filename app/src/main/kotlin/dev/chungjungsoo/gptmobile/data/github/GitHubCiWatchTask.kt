package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.data.agent.TaskResumeDescriptor
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringApiException
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
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
    sealed interface Delivery {
        data object Waiting : Delivery
        data object Discarded : Delivery
        data class Ready(val payload: PendingPromptPayload) : Delivery
    }

    /** The remote read is prepared outside the watch's cancellation/admission mutex. */
    suspend fun prepare(watch: GitHubCheckWatch, database: ChatDatabaseV2, settings: SettingRepository, api: GitHubEngineeringApi): Delivery {
        val current = currentDelivery(watch, database, settings)
        if (current !is Delivery.Ready) return current
        val head = try {
            val root = GitHubEngineeringApi.root(watch.owner, watch.repo)
            Json.parseToJsonElement(api.request("$root/commits/${GitHubEngineeringApi.segment(ref)}")).jsonObject["sha"]
        } catch (failure: EngineeringApiException) {
            if (failure.status in setOf(401, 403, 404) && failure.retryAtEpochMs == null) return Delivery.Discarded
            throw failure
        }
        return if (head == JsonPrimitive(watch.headSha)) current else Delivery.Discarded
    }

    /** Re-read the saved run, conversation and profile immediately before queue admission. */
    suspend fun currentDelivery(watch: GitHubCheckWatch, database: ChatDatabaseV2, settings: SettingRepository): Delivery {
        val run = database.agentRunDao().getById(runId) ?: return Delivery.Discarded
        if (run.chatId != watch.chatId || run.profileUid != watch.profileUid || run.userMessageId != sourceMessageId || run.modelSnapshot != model) return Delivery.Discarded
        if (run.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING)) return Delivery.Waiting
        if (run.status !in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING)) return Delivery.Discarded
        val binding = capture(runId, watch.chatId, watch.profileUid, watch.owner, watch.repo, database, settings) ?: return Delivery.Discarded
        if (binding != this) return Delivery.Discarded
        val room = database.agentPersistenceDao().getChatRoom(watch.chatId) ?: return Delivery.Discarded
        if (room.isArchived || room.isTemporary || room.activePlatform != listOf(watch.profileUid)) return Delivery.Discarded
        val scope = settings.getFeatureSettings().conversationGitHub[watch.chatId] ?: return Delivery.Discarded
        if (scope.ref != ref ||
            scope.connectionUid != watch.connectionUid ||
            scope.mainProfileUid != watch.profileUid ||
            !scope.owner.equals(watch.owner, true) ||
            !scope.repo.equals(watch.repo, true)
        ) {
            return Delivery.Discarded
        }
        val payload = PendingPromptPayload(
            profileUids = listOf(watch.profileUid),
            models = mapOf(watch.profileUid to model),
            tools = ChatMcpToolConfig(allowAllByDefault = false, enabledToolIds = setOf(watch.connectionUid), github = scope.copy(commitSha = watch.headSha)),
            recovery = TaskResumeDescriptor("${watch.chatId}:$sourceMessageId", sourceMessageId, runId, TaskRecoveryAction.CONTINUE_RUN),
            githubCiWatch = GitHubCiWatchContinuation.capture(watch, this)
        )
        return Delivery.Ready(payload)
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
