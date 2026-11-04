package dev.chungjungsoo.gptmobile.data.github

/** One scope contract for planning, reporting and completion. */
internal object GitHubChecklistScope {
    fun readOnly(task: String): Boolean = !Regex("(?i)\\bnot\\s+read[ -]only\\b|\\b(?:include|allow|perform|test|with)\\s+(?:the\\s+)?(?:writes|mutations)\\b").containsMatchIn(task) && Regex("(?i)\\bread[ -]only\\b|\\bno writes\\b|\\bwithout (?:writes|changes)\\b").containsMatchIn(task)

    fun exhaustive(task: String): Boolean = Regex("(?i)\\b(?:all|entire|complete|full)\\s+(?:(?:workflow|ci|run)\\s+)?(?:branches|workflows|pull requests|commits|runs|inventor(?:y|ies)|history)\\b").containsMatchIn(task)
}
