package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Only visible conversation text belongs in a readable export; never system prompts or tool traces. */
internal fun githubConversationExport(
    title: String,
    repository: String,
    branch: String,
    commit: String,
    turns: List<Pair<String, List<String>>>,
    runStates: List<String>,
    exportedAt: String,
    format: ChatExportFormat
): String {
    val markdown = buildString {
        append("# ").append(title.lineSequence().firstOrNull().orEmpty()).append("\n\n")
        append("Repository: ").append(repository).append("\nBranch: ").append(branch)
        append("\nContext commit: ").append(commit).append("\nExported: ").append(exportedAt)
        append("\nSnapshot: ").append(runStates.distinct().joinToString(", ").ifBlank { "No run recorded" })
        append(". Active replies may be incomplete; this is not proof of task completion.\n")
        turns.forEach { (user, replies) ->
            append("\n## You\n\n").append(user).append('\n')
            append("\n## Assistant\n\n")
            append(replies.filter(String::isNotBlank).joinToString("\n\n").ifBlank { "No response recorded." }).append('\n')
        }
    }
    return if (format == ChatExportFormat.MARKDOWN) markdown else plainChatExportText(markdown)
}
