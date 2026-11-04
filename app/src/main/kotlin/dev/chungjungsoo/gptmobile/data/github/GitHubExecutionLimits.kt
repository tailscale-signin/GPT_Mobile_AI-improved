package dev.chungjungsoo.gptmobile.data.github

/** One policy is applied before binding tools and again before opening the model loop. */
internal object GitHubExecutionLimits {
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
}
