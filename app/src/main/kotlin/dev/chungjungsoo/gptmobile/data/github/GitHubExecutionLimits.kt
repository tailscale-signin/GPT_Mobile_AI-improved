package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One policy is applied before binding tools and again before opening the model loop. */
internal object GitHubExecutionLimits {
    fun expiredTaskFailure() = ProviderEvent.Failed(
        "The task's time limit has been reached. Saved results and unfinished work are retained. Start a new task to allow more work.",
        errorCode = "GITHUB_TASK_INCOMPLETE",
        taskOutcome = "BLOCKED",
        failureDetails = buildJsonObject {
            put("failed_stage", "task_budget")
            put("failure_domain", "APPLICATION_VERIFICATION")
            put("stop_reason", "ACCEPTED_TASK_DEADLINE_REACHED")
            put("dispatched", false)
            put("retryable", false)
        }
    )

    /** An explicit task duration survives continuations; an ordinary run timeout does not grant a new task. */
    fun requestedDurationMillis(task: String): Long? = task.lineSequence()
        .flatMap { line ->
            budgetClause.findAll(line).flatMap { duration.findAll(it.groupValues[1]) } + constrainedDuration.findAll(line)
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

    data class CallLimit(val effective: Int, val configured: Int, val workflow: Int, val scope: Int, val requested: Int?, val sources: List<String>) {
        fun description(): String = "$effective tool calls · limited by ${sources.joinToString(", ")}"
    }

    fun toolCalls(task: String, configured: Int, preset: GitHubWorkflowPreset): Int = callLimit(task, configured, preset).effective

    fun callLimit(task: String, configured: Int, preset: GitHubWorkflowPreset): CallLimit {
        val selected = GitHubWorkflowPreset.fromRequest(task) ?: preset
        val scopeLimit = when {
            GitHubStatusRequest.matches(task) -> 6
            GitHubTestChecklist.integration(task) -> minOf(256, selected.toolCalls)
            GitHubTaskCoverage.boundedDiagnostic(task) -> 32
            GitHubRequestCompletion.informational(task) -> 64
            else -> selected.toolCalls
        }
        val labelledLimits = task.lineSequence().flatMap { budgetClause.findAll(it) }.flatMap { toolCallCount.findAll(it.groupValues[1]) }
        val explicitLimit = (requestLimit.findAll(task) + labelledLimits).mapNotNull { it.groupValues[1].toLongOrNull() }
            .minOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
        val effective = minOf(configured.coerceAtLeast(0), scopeLimit, explicitLimit)
        val sources = buildList {
            if (effective == configured.coerceAtLeast(0)) add("profile/chat allowance")
            if (effective == scopeLimit) add(if (scopeLimit == selected.toolCalls) "workflow preset" else "task scope policy")
            if (effective == explicitLimit) add("request ceiling")
        }
        return CallLimit(effective, configured.coerceAtLeast(0), selected.toolCalls, scopeLimit, explicitLimit.takeUnless { it == Int.MAX_VALUE }, sources)
    }

    private val requestLimit = Regex(
        "(?i)\\b(?:at most|no more than|up to|maximum|max|limit(?:ed)?(?: to)?|budget(?: of)?)\\s*:?\\s*(\\d{1,10})\\s+tool[ -]calls?\\b"
    )
    private val duration = Regex("(?i)\\b(\\d{1,10})\\s*(seconds?|minutes?|hours?)\\b")
    private val toolCallCount = Regex("(?i)\\b(\\d{1,10})\\s+tool[ -]calls?\\b")
    private val budgetClause = Regex("(?i)(?:^\\s*|[.!?;]\\s+)(?:budget|time limit|deadline)\\s*:\\s*([^.!?;\\r\\n]+)")
    private val constrainedDuration = Regex("(?i)\\b(?:at most|no more than|within)\\s+(?:\\d{1,10}\\s+tool[ -]calls?\\s*(?:and|,)\\s*)?(\\d{1,10})\\s*(seconds?|minutes?|hours?)\\b")
}
