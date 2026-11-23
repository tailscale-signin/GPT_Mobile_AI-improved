package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import kotlinx.coroutines.CancellationException

@HiltWorker
class GitHubDecisionActionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val decisions: GitHubChatDecisions,
    private val settings: SettingRepository,
    private val database: ChatDatabaseV2,
    private val notifications: GitHubDecisionNotifications
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(GitHubDecisionActionReceiver.REQUEST) ?: return Result.failure()
        val binding = inputData.getString(GitHubDecisionActionReceiver.BINDING) ?: return Result.failure()
        val option = inputData.getInt(GitHubDecisionActionReceiver.OPTION, -1)
        return try {
            dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
            // Keep the current run/fence stable until the durable input is saved.
            // The transaction performs no repository mutation or approval action.
            val response = database.withTransaction {
                decisions.respondNotification(id, binding, option) { decision ->
                    val features = settings.getFeatureSettings()
                    val workspace = features.conversationGitHub[decision.chatId]
                    val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == decision.profileUid }?.let { GitHubChatPolicy.platform(it, features, workspace) }
                    features.conversationIdentities[decision.chatId] == decision.conversationIdentity &&
                        workspace != null &&
                        GitHubChatDecisions.workspaceKey(workspace) == decision.workspace &&
                        workspace.mainProfileUid == decision.profileUid &&
                        profile?.enabled == true &&
                        !profile.disableAllTools &&
                        !profile.disableLocalTools &&
                        decision.runId?.let { database.agentRunDao().ownsPendingDecision(it, decision.chatId, decision.sourceMessageId, decision.profileUid) } == true
                }
            }
            val retained = decisions.decisions.value.firstOrNull { it.id == id && it.notificationBinding() == binding }
            if (response.accepted || retained?.answer != null) {
                if (retained != null && !retained.delivered && !retained.cancelled) notifications.choiceSaved(retained, response.waiting) else notifications.dismiss(id)
            } else if (retained != null) {
                notifications.prompt(id, retained.chatId, retained.sourceMessageId, "This task has changed. Open the conversation to review its current choices.", title = "Review current choice", action = "Open chat")
            } else if (decisions.decisions.value.none { it.id == id }) {
                notifications.dismiss(id)
            }
            AppLogRecorder.record("GitHubDecision", "NOTIFICATION_ANSWER · request=$id · accepted=${response.accepted} · waiting=${response.waiting} · attempt=$runAttemptCount")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppLogRecorder.record("GitHubDecision", "NOTIFICATION_SAVE_RETRY · request=$id · attempt=$runAttemptCount")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
