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

    private val pendingReads = Regex("(?i)\\b(?:finish|complete|resume)\\s+(?:(?:all|the)\\s+)?(?:(?:pending|remaining|unread)\\s+)(?:read(?:s| pages)?|pages|pagination)\\b")

    fun exhaustive(task: String): Boolean = requested(pendingReads, task) || requested(Regex("(?i)\\b(?:all|entire|complete|full)\\s+(?:(?:workflow|ci|run|issue)\\s+)?(?:branches|workflows|pull requests|issues|commits|runs|tags|releases|inventor(?:y|ies)|history)\\b"), task)

    private fun requested(pattern: Regex, task: String): Boolean = pattern.findAll(task).any { match ->
        !negatedRequest.containsMatchIn(task.substring(0, match.range.first))
    }
}
