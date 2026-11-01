package dev.chungjungsoo.gptmobile.data.github

/** Presentation is selected from the user's task; factual values still come only from verified receipts. */
internal object GitHubAnswerPresentation {
    fun title(task: String): String {
        val text = task.lowercase()
        return when {
            GitHubStatusRequest.matches(task) -> "Repository status"
            GitHubTaskCoverage.matches(task) -> "GitHub verification report"
            listOf("review", "pull request", " pr ").any { it in text } -> "Repository review"
            listOf("fix", "implement", "add", "change", "build").any { it in text } -> "Implementation update"
            listOf("why", "explain", "investigate", "issue", "error", "analy").any { it in text } -> "Investigation findings"
            else -> "Repository update"
        }
    }

    fun render(task: String, facts: List<String>, notes: List<String>, excerpts: List<String>, questions: List<String>): String = buildString {
        append("# ").append(title(task)).append("\n\n")
        // Explanations appear before supporting details. Do not turn the API's object structure into the answer.
        val interpretations = notes.filter { it.startsWith("- **Interpretation:") }
        val limits = notes - interpretations.toSet()
        if (interpretations.isNotEmpty()) {
            append(interpretations.first().removePrefix("- ")).append('\n')
            if (interpretations.size > 1) append("\n## Findings\n\n").append(interpretations.drop(1).joinToString("\n\n")).append('\n')
        } else {
            append(if (facts.isEmpty() && excerpts.isEmpty()) "The available evidence does not yet establish the requested result.\n" else "Current observations are listed below; unresolved work is identified separately.\n")
        }
        if (facts.isNotEmpty()) {
            append("\n## Repository details\n\n| Detail | Result |\n| --- | --- |\n")
            append(facts.distinct().joinToString("\n")).append('\n')
        }
        if (excerpts.isNotEmpty()) append("\n## Source evidence\n\n").append(excerpts.distinct().joinToString("\n\n")).append('\n')
        if (limits.isNotEmpty()) append("\n## Open items\n\n").append(limits.distinct().joinToString("\n")).append('\n')
        if (questions.isNotEmpty()) append("\n## Next steps\n\n").append(questions.distinct().joinToString("\n") { "- $it" })
    }
}
