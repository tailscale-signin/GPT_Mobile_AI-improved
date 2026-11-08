package dev.chungjungsoo.gptmobile.data.github

/** One policy is applied before binding tools and again before opening the model loop. */
internal object GitHubExecutionLimits {
    /** An explicit task duration survives continuations; an ordinary run timeout does not grant a new task. */
    fun requestedDurationMillis(task: String): Long? = task.lineSequence()
        .flatMap { line ->
            val explicitBudget = budgetLine.find(line)?.groupValues?.get(1)
            if (explicitBudget != null) duration.findAll(explicitBudget) else constrainedDuration.findAll(line)
        }
        .mapNotNull { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val unit = when (match.groupValues[2].lowercase(java.util.Locale.ROOT).first()) {
                'h' -> 3_600_000L
                'm' -> 60_000L
                else -> 1_000L
            }
            amount.coerceAtMost(Long.MAX_VALUE / unit) * unit
        }.minOrNull()

    fun toolCalls(task: String, configured: Int, preset: GitHubWorkflowPreset): Int {
        val selected = GitHubWorkflowPreset.fromRequest(task) ?: preset
        val scopeLimit = when {
            GitHubStatusRequest.matches(task) -> 6
            GitHubTestChecklist.integration(task) -> minOf(256, selected.toolCalls)
            GitHubTaskCoverage.boundedDiagnostic(task) -> 32
            GitHubRequestCompletion.informational(task) -> 64
            else -> selected.toolCalls
        }
        val explicitLimit = requestLimit.findAll(task).mapNotNull { it.groupValues[1].toLongOrNull() }
            .minOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
        return minOf(configured.coerceAtLeast(0), scopeLimit, explicitLimit)
    }

    private val requestLimit = Regex(
        "(?i)\\b(?:at most|no more than|up to|maximum|max|limit(?:ed)?(?: to)?|budget(?: of)?)\\s*:?\\s*(\\d{1,10})\\s+tool[ -]calls?\\b"
    )
    private val duration = Regex("(?i)\\b(\\d{1,10})\\s*(seconds?|minutes?|hours?)\\b")
    private val budgetLine = Regex("(?i)^\\s*(?:budget|time limit|deadline)\\s*:\\s*([^.!?;\\r\\n]+)")
    private val constrainedDuration = Regex("(?i)\\b(?:at most|no more than|within)\\s+(?:\\d{1,10}\\s+tool[ -]calls?\\s*(?:and|,)\\s*)?(\\d{1,10})\\s*(seconds?|minutes?|hours?)\\b")
}
