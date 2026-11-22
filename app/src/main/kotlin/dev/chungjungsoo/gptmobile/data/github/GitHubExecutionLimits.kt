package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One policy is applied before binding tools and again before opening the model loop. */
internal object GitHubExecutionLimits {
    enum class DeadlineCheck { BEFORE_TOOL_RESTORE, BEFORE_MODEL_LOOP, MODEL_LOOP_TIMEOUT, VERIFIED_SESSION_ENTRY }

    fun deadlineTiming(contract: JsonObject?, observedAt: Long): JsonObject = buildJsonObject {
        val start = (contract?.get("accepted_at_ms") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val end = (contract?.get("deadline_at_ms") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val state = when {
            contract == null -> "CONTRACT_UNAVAILABLE"
            "deadline_at_ms" !in contract -> "NO_ACCEPTED_DEADLINE"
            start == null || end == null || start < 0 || end < start || observedAt < start -> "TIMING_UNAVAILABLE"
            observedAt < end -> "WINDOW_OPEN"
            else -> "WINDOW_EXPIRED"
        }
        put("task_deadline_observation", state)
        if (start != null && end != null && end >= start && start >= 0) {
            put("task_deadline_clock", "WALL_CLOCK")
            put("task_deadline_renewed", (contract?.get("grant_message_id") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.let { it > 0 } == true)
            ((end - start) / 1000).takeIf { it <= 16_000_000 }?.let { put("task_deadline_window_seconds", it) }
            if (observedAt >= start) {
                ((observedAt - start) / 1000).takeIf { it <= 16_000_000 }?.let { put("task_deadline_elapsed_seconds", it) }
                ((end - observedAt).coerceAtLeast(0) / 1000).takeIf { it <= 16_000_000 }?.let { put("task_deadline_remaining_seconds", it) }
            }
            if (observedAt >= end) {
                ((observedAt - end) / 1000).takeIf { it <= 16_000_000 }?.let { put("task_deadline_overdue_seconds", it) }
            }
        }
    }

    private val renewal = Regex("""(?i)(?:please )?(?:resume|continue) for ([1-9][0-9]{0,3}) (minutes?|hours?)[.!]?""")
    const val RENEWAL_EXAMPLE = "Continue for 10 minutes"
    fun renewedDuration(text: String): Long? {
        val match = renewal.matchEntire(GitHubTaskContinuity.request(text)) ?: return null
        val minutes = match.groupValues[1].toLong() * if (match.groupValues[2].startsWith("h", true)) 60 else 1
        return minutes.takeIf { it in 1..1440 }?.times(60_000L)
    }

    fun isExpiredFailure(details: String?): Boolean = details != null &&
        ("ACCEPTED_TASK_DEADLINE_REACHED" in details || "The task's time limit has been reached" in details)

    fun expiredTaskFailure(check: DeadlineCheck? = null) = ProviderEvent.Failed(
        "The task's time limit has been reached. Your results are saved. Send “$RENEWAL_EXAMPLE” to continue this task with a new time allowance.",
        errorCode = "GITHUB_TASK_INCOMPLETE",
        taskOutcome = "BLOCKED",
        failureDetails = buildJsonObject {
            put("diagnostic_area", "ACCEPTANCE")
            put("diagnostic_capture_mode", "INCIDENT")
            check?.let { put("task_deadline_check", it.name) }
            put("failed_stage", "task_budget")
            put("failure_origin", "APPLICATION")
            put("failure_domain", "APPLICATION_VERIFICATION")
            put("stop_reason", "ACCEPTED_TASK_DEADLINE_REACHED")
            put("recovery_action", "AUTHORIZE_NEW_TIME_WINDOW")
            put("time_window_counts_waits", true)
            put("resume_allowed", false)
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
            // Health includes paged checks, policy, failures and a revision-bound
            // review read for each open PR. The four-read snapshot cap cannot
            // cover that accepted plan; explicit/profile ceilings still apply.
            GitHubStatusRequest.health(task) -> minOf(32, selected.toolCalls)
            GitHubStatusRequest.matches(task) -> 6
            GitHubTestChecklist.integration(task) -> minOf(256, selected.toolCalls)
            selected.id == "diagnose_ci" -> selected.toolCalls
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
