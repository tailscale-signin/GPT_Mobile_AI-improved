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
import kotlinx.serialization.json.contentOrNull

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
        val expiresAt = frozen.approvalExpiresAtEpochMs
        if (task.state in setOf("AWAITING_APPROVAL", "COMMITTING") &&
            expiresAt != null &&
            expiresAt <= System.currentTimeMillis() &&
            GitHubApprovalBinding.events(store, taskId).none { (it["event"] as? JsonPrimitive)?.contentOrNull == "approval_card_expired" }
        ) {
            GitHubApprovalBinding.recordEvent(
                store,
                taskId,
                "approval_card_expired",
                kotlinx.serialization.json.buildJsonObject {
                    put("branch", task.workingBranch)
                    put("approval_expires_at_epoch_ms", expiresAt)
                }
            )
        }
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
            val retainedFixture = GitHubApprovalBinding.fixtureKept(store, taskId)
            val text = if (retainedFixture) {
                "GitHub workspace: ${scope.owner}/${scope.repo}\nBranch: ${bound.ref}\n\nCommit $commit was approved and published. Continue from the saved integration task using this exact commit. Reconcile the approval and report that the fixture is intentionally retained by the user's choice. Do not delete the fixture or repeat the commit."
            } else {
                "GitHub workspace: ${scope.owner}/${scope.repo}\nBranch: ${bound.ref}\n\nCommit $commit was approved and published. Continue from the saved task using this exact commit. Subscribe with github_watch_checks when CI is pending; do not repeat the commit."
            }
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
        val nativeState = engineering.approvalStatus(taskId, scope.connectionUid, GitHubEngineeringApi.root(scope.owner, scope.repo))
        val nativeCancellationConfirmed = task.state == "CANCELLED" &&
            nativeState?.let {
                it["cancelled_before_publication"] == JsonPrimitive(true) && it["publication_may_have_started"] == JsonPrimitive(false)
            } == true
        val rejectionWasClicked = GitHubApprovalBinding.events(store, taskId).any { event ->
            event["event"] == JsonPrimitive("approval_card_clicked") &&
                (event["details"] as? JsonObject)?.get("choice") == JsonPrimitive("REJECT")
        }
        if (nativeCancellationConfirmed && rejectionWasClicked && !GitHubApprovalBinding.cancellationConfirmed(store, taskId)) {
            GitHubApprovalBinding.markDecision(store, taskId, scope, task.workingBranch, automatic = false, cancellationConfirmed = true)
        }
        val cancelledBeforePublication = nativeCancellationConfirmed && GitHubApprovalBinding.cancellationConfirmed(store, taskId)
        if (cancelledBeforePublication) {
            val changes = GitHubEngineeringAuthorization.files(frozen.patchJson)
            val isIntegrationFixture = GitHubTestChecklist.integration(original.content) &&
                eligibleAutomaticEngineeringPatch(task.workingBranch, frozen.policyBranch, changes)
            if (isIntegrationFixture && !GitHubApprovalBinding.cancellationContinuationQueued(store, taskId)) {
                if (settings.getFeatureSettings().conversationGitHub[chatId]?.sameWorkspace(scope) != true) return false
                val retainFixture = GitHubApprovalBinding.fixtureKept(store, taskId)
                val retentionInstruction = if (retainFixture) {
                    "Keep the fixture because the user explicitly chose Keep fixture; verify its current state and report the intentional retention. Do not delete it."
                } else {
                    "Reconcile the owned codex-self-test fixture, delete it only after verifying its current head, and verify the branch is absent."
                }
                val text = "GitHub workspace: ${scope.owner}/${scope.repo}\nBranch: ${scope.ref}\n\nThe existing approval for task $taskId was rejected and confirmed cancelled before publication. Continue from the saved evidence for this exact integration test. Do not approve or restage the patch, and do not create another fixture. $retentionInstruction Stop if ownership or remote state is uncertain."
                queue.enqueue(
                    chatId,
                    text,
                    PendingPromptPayload(
                        profileUids = listOf(profile.uid),
                        models = mapOf(profile.uid to profile.model),
                        tools = ChatMcpToolConfig(allowAllByDefault = false, enabledToolIds = setOf(scope.connectionUid), github = scope)
                    ),
                    "github-cancelled-approval-cleanup:$taskId"
                )
                GitHubApprovalBinding.markCancellationContinuationQueued(store, taskId)
            }
            return true
        }
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
        if (sourceRun.status !in setOf(AgentRunStatus.BLOCKED, AgentRunStatus.WAITING_FOR_APPROVAL) || !GitHubApprovalBinding.isApprovalPause(sourceRun.terminalError)) return true

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
        branch.matches(Regex("codex-self-test/[a-f0-9]{20}")) &&
        defaultBranch.isNotBlank() &&
        branch != defaultBranch &&
        changes.isNotEmpty() &&
        changes.all { it.path.startsWith("docs/codex-self-test/${branch.substringAfterLast('/')}/") } &&
        changes.none {
            val path = it.path.lowercase(java.util.Locale.ROOT)
            it.delete || path.startsWith(".github/") || path in setOf(".gitmodules", "codeowners", "docs/codeowners")
        }
