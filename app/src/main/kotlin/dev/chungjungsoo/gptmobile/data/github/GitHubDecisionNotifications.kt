package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.AppForegroundTracker
import dev.chungjungsoo.gptmobile.util.NotificationManager
import javax.inject.Inject
import javax.inject.Singleton

/** Track visible conversation instances; another visible pane must not suppress this chat's alert. */
object GitHubChatPresence {
    private val visible = mutableMapOf<String, Int>()

    @Synchronized fun show(owner: String, chatId: Int) {
        visible[owner] = chatId
    }

    @Synchronized fun hide(owner: String) {
        visible.remove(owner)
    }

    @Synchronized fun contains(chatId: Int): Boolean = chatId in visible.values
    fun shouldNotify(chatId: Int, foreground: Boolean): Boolean = !foreground || !contains(chatId)
}

@Singleton
class GitHubDecisionNotifications @Inject constructor(private val notifications: NotificationManager, private val settings: SettingRepository) {
    suspend fun offered(decision: GitHubChatDecision) {
        prompt(decision.id, decision.chatId, decision.sourceMessageId, decision.question)
    }
    suspend fun prompt(id: String, chatId: Int, messageId: Int, question: String) {
        if (settings.getFeatureSettings().responseNotifications && GitHubChatPresence.shouldNotify(chatId, !AppForegroundTracker.isBackgrounded)) {
            // Notification denial cannot turn a pending question into an answer or fail the task.
            runCatching { notifications.showGitHubDecision(id, chatId, messageId, question) }
        }
    }
    fun dismiss(id: String) {
        runCatching { notifications.dismissGitHubDecision(id) }
    }
}
