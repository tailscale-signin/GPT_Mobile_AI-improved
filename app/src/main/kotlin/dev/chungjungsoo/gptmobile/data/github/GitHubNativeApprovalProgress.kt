package dev.chungjungsoo.gptmobile.data.github

/** One read-only refresh of native task bindings and verified decisions for the header. */
internal data class GitHubNativeApprovalProgress(
    val workspace: GitHubConversationContext,
    val chatId: Int?,
    val tasks: List<Task>,
    val decisions: List<GitHubNativeDecisionReceipt>
) {
    data class Task(val taskId: String, val branch: String, val state: String, val chatId: Int?, val sourceMessageId: Int?) {
        val pending: Boolean get() = state == "AWAITING_APPROVAL" || state == "COMMITTING"

        fun matches(receipt: GitHubNativeDecisionReceipt): Boolean =
            taskId == receipt.taskId && branch == receipt.branch && chatId == receipt.chatId && sourceMessageId == receipt.sourceMessageId
    }

    fun matches(context: GitHubConversationContext?, chat: Int?): Boolean =
        context != null && chat != null && chatId == chat && workspace.sameWorkspace(context)
}
