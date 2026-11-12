package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.data.agent.TaskResumeDescriptor
import dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** Shared producer/parser for native decisions. These messages request reconciliation, never approval. */
internal object GitHubNativeContinuation {
    private const val PUBLISHED = "Continue from the saved task using this exact commit. Subscribe with github_watch_checks when CI is pending; do not repeat the commit."
    private const val PUBLISHED_RETAINED = "Continue from the saved integration task using this exact commit. Reconcile the approval and report that the fixture is intentionally retained by the user's choice. Do not delete the fixture or repeat the commit."
    private const val REJECTED = "was rejected and confirmed cancelled before publication. Continue from the saved evidence for this exact integration test. Do not approve or restage the patch, and do not create another fixture."
    private const val KEEP = "Keep the fixture because the user explicitly chose Keep fixture; verify its current state and report the intentional retention. Do not delete it."
    private const val CLEANUP = "Reconcile the owned codex-self-test fixture, delete it only after verifying its current head, and verify the branch is absent."
    private const val STOP = "Stop if ownership or remote state is uncertain."
    private val published = Regex("^Commit ([a-fA-F0-9]{40}) was approved and published\\. (?:${Regex.escape(PUBLISHED)}|${Regex.escape(PUBLISHED_RETAINED)})$")
    private val cancelled = Regex("^The existing approval for task [A-Za-z0-9_-]{1,128} ${Regex.escape(REJECTED)} (?:${Regex.escape(KEEP)}|${Regex.escape(CLEANUP)}) ${Regex.escape(STOP)}$")

    fun publication(sha: String, keepFixture: Boolean): String {
        require(sha.matches(Regex("[a-fA-F0-9]{40}")))
        return "Commit $sha was approved and published. ${if (keepFixture) PUBLISHED_RETAINED else PUBLISHED}"
    }

    fun cancellation(taskId: String, keepFixture: Boolean): String {
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return "The existing approval for task $taskId $REJECTED ${if (keepFixture) KEEP else CLEANUP} $STOP"
    }

    fun publishedCommit(text: String): String? = published.matchEntire(GitHubTaskContinuity.request(text))?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)

    fun matches(text: String): Boolean = publishedCommit(text) != null || cancelled.matches(GitHubTaskContinuity.request(text))

    /** Select the card's saved source before later user turns; queue dispatch supplies the run ID. */
    fun recovery(taskId: String, source: GitHubApprovalBinding.Source, profileUid: String, messages: List<MessageV2>): TaskResumeDescriptor {
        val original = requireNotNull(messages.singleOrNull { it.id == source.messageId && it.chatId == source.chatId && it.platformType == null }) { "The approval's original request is unavailable." }
        val history = messages.filter { it.chatId == source.chatId && it.platformType == null && it.id <= original.id }
        val owner = messages.lastOrNull { it.chatId == source.chatId && it.linkedMessageId == original.id && it.platformType == profileUid }
        val inherited = owner?.timeline?.mapNotNull { it.recovery }?.firstOrNull { it.generationId == owner.currentRunId }
        val resolved = if (inherited == null) resolveTaskRecovery(history) else requireNotNull(resolveTaskRecovery(history, inherited)) { "The approval's saved recovery identity no longer matches its original request." }
        val sourceId = resolved?.sourceMessageId ?: original.id
        require(history.any { it.id == sourceId && TaskRecoveryAction.fromText(it.content) == null }) { "The approval's original task cannot be recovered from a control message." }
        return TaskResumeDescriptor("${source.chatId}:$sourceId", sourceId, "github-native-approval:$taskId", TaskRecoveryAction.CONTINUE_RUN)
    }
}
