package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringFilePatch
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringAuthorization
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringController
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

/** WorkManager owns recovery. Durable task and conversation receipts supply all authority. */
@Singleton
class GitHubNativeApprovalRecovery @Inject constructor(
    private val engineering: GitHubEngineeringController,
    private val store: GitHubRepositoryStateStore,
    private val database: ChatDatabaseV2,
    private val settings: SettingRepository,
    private val queue: DurablePromptQueue
) {
    internal suspend fun scheduleOutstanding(chatId: Int? = null) {
        engineering.recoveryTasks().forEach { task ->
            val original = GitHubApprovalBinding.originalChat(store, task.taskId)
            if (original != null && (chatId == null || original == chatId)) engineering.scheduleApprovalRecovery(task.taskId)
        }
    }

    internal suspend fun resume(taskId: String, allowApproval: Boolean = true): Boolean = try {
        reconcile(taskId, allowApproval)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("GitHub", "NATIVE_APPROVAL_RECOVERY_DEFERRED · reason=${failure.javaClass.simpleName}", "W")
        false
    }

    private suspend fun reconcile(taskId: String, allowApproval: Boolean): Boolean {
        val task = engineering.task(taskId) ?: return true
        val frozen = engineering.run(taskId) ?: return true
        val chatId = GitHubApprovalBinding.originalChat(store, taskId) ?: return true
        val scope = settings.getFeatureSettings().conversationGitHub[chatId] ?: return true
        if (scope.connectionUid != frozen.connectionUid || !scope.owner.equals(task.repositoryOwner, true) || !scope.repo.equals(task.repositoryName, true)) return true
        val source = GitHubApprovalBinding.source(store, taskId, scope, task.workingBranch)?.takeIf { it.chatId == chatId } ?: return true
        val room = database.agentPersistenceDao().getChatRoom(chatId) ?: return true
        if (room.isArchived || room.activePlatform != listOf(scope.mainProfileUid)) return true
        val original = database.agentPersistenceDao().rawRecoveryMessage(source.messageId)?.takeIf { it.chatId == chatId && it.platformType == null } ?: return true
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == scope.mainProfileUid && it.enabled } ?: return true
        if (source.model != profile.model || source.profileContract != GitHubApprovalBinding.profileContract(profile.compatibleType.name, profile.apiUrl, profile.providerConnectionUid, profile.reasoning)) return true
        if (settings.providerActivationRequired(profile) || FreeProviderPreflight.check(profile) != null) return true

        suspend fun queuePublication(): Boolean {
            val publication = engineering.verifiedPublication(taskId) ?: return false
            val continuation = GitHubApprovalBinding.continuation(store, taskId, scope, task.workingBranch, publication) ?: return true
            val commit = continuation.sha
            val bound = if (scope.ref == continuation.branch) scope.copy(commitSha = commit, stagedFileCount = 0, recentCommits = emptyList(), observedAtMillis = null) else scope
            if (settings.getFeatureSettings().conversationGitHub[chatId]?.sameWorkspace(scope) != true) return false
            val text = "GitHub workspace: ${scope.owner}/${scope.repo}\nBranch: ${bound.ref}\n\nCommit $commit was approved and published. Continue from the saved task using this exact commit. Subscribe with github_watch_checks when CI is pending; do not repeat the commit."
            queue.enqueue(
                chatId,
                text,
                PendingPromptPayload(
                    profileUids = listOf(profile.uid),
                    models = mapOf(profile.uid to profile.model),
                    tools = ChatMcpToolConfig(allowAllByDefault = false, enabledToolIds = setOf(scope.connectionUid), github = bound)
                ),
                "github-approved-commit:$commit"
            )
            GitHubApprovalBinding.continuationQueued(store, taskId, commit)
            return true
        }

        if (queuePublication()) return true
        if (!allowApproval || task.state !in setOf("AWAITING_APPROVAL", "COMMITTING")) return true
        val request = GitHubTaskContinuity.request(original.content)
        if (GitHubTaskContinuity.control(request) || GatewayOwnedTurnPolicy.scope(request) != "implement" || task.requiresHardwareVerification) return true
        if (!eligibleAutomaticEngineeringPatch(task.workingBranch, frozen.policyBranch, GitHubEngineeringAuthorization.files(frozen.patchJson))) return true

        suspend fun authority(): Boolean {
            val features = settings.getFeatureSettings()
            val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid } ?: return false
            return features.conversationGitHub[chatId]?.sameWorkspace(scope) == true &&
                features.githubDecisionMode(chatId, scope) == GitHubDecisionMode.AUTO_APPROVE &&
                GitHubChatPolicy.settings(features, profile.uid, scope)?.githubTools == true &&
                current.enabled &&
                !current.disableAllTools &&
                !current.disableRemoteTools &&
                current.model == source.model &&
                GitHubApprovalBinding.profileContract(current.compatibleType.name, current.apiUrl, current.providerConnectionUid, current.reasoning) == source.profileContract
        }

        if (!authority()) return true
        val sourceRun = database.agentRunDao().getByChatId(chatId).lastOrNull { it.userMessageId == source.messageId && it.profileUid == scope.mainProfileUid } ?: return false
        if (sourceRun.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING)) return false
        if (sourceRun.status != AgentRunStatus.BLOCKED || !GitHubApprovalBinding.isApprovalPause(sourceRun.terminalError)) return true

        if (task.state == "AWAITING_APPROVAL") {
            engineering.approve(taskId, frozen.patchDigest, ::authority)
            GitHubApprovalBinding.markDecision(store, taskId, scope, task.workingBranch, automatic = true)
        } else {
            val state = engineering.approvalStatus(taskId, scope.connectionUid, GitHubEngineeringApi.root(scope.owner, scope.repo))
            if (state?.get("publication_may_have_started") != JsonPrimitive(false)) {
                engineering.recover()
                return queuePublication()
            }
            if (!GitHubApprovalBinding.automaticallyApproved(store, taskId) || frozen.approvalRevoked || (frozen.approvalExpiresAtEpochMs ?: 0L) <= System.currentTimeMillis()) return true
        }
        if (!authority()) return true
        engineering.execute(taskId, ::authority)
        return queuePublication()
    }
}

internal fun eligibleAutomaticEngineeringPatch(branch: String, defaultBranch: String, changes: List<EngineeringFilePatch>): Boolean =
    branch.isNotBlank() &&
        defaultBranch.isNotBlank() &&
        branch != defaultBranch &&
        changes.isNotEmpty() &&
        changes.none {
            val path = it.path.lowercase(java.util.Locale.ROOT)
            it.delete || path.startsWith(".github/") || path in setOf(".gitmodules", "codeowners", "docs/codeowners")
        }
