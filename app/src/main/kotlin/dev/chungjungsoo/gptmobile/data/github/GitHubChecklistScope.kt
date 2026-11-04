package dev.chungjungsoo.gptmobile.data.github

/** One scope contract for planning, reporting and completion. */
internal object GitHubChecklistScope {
    private val negatedReadOnly = Regex("(?i)\\bnot\\s+read[ -]only\\b")
    private val readOnlyScope = Regex("(?i)\\bread[ -]only\\b|\\bno writes\\b|\\bwithout (?:writes|changes)\\b")
    private val writeRequest = Regex("(?i)\\b(?:include|allow|perform|test|with)\\s+(?:the\\s+)?(?:writes|mutations)\\b")
    private val negatedRequest = Regex("(?i)(?:\\b(?:not|never|without|no)|\\bdon['’]t)\\s*$")

    fun readOnly(task: String): Boolean = !negatedReadOnly.containsMatchIn(task) &&
        readOnlyScope.containsMatchIn(task) &&
        writeRequest.findAll(task).none { request ->
            !negatedRequest.containsMatchIn(task.substring(0, request.range.first))
        }

    fun exhaustive(task: String): Boolean = Regex("(?i)\\b(?:all|entire|complete|full)\\s+(?:(?:workflow|ci|run)\\s+)?(?:branches|workflows|pull requests|commits|runs|inventor(?:y|ies)|history)\\b").containsMatchIn(task)
}
