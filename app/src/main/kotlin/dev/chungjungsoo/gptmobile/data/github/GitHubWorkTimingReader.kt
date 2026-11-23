package dev.chungjungsoo.gptmobile.data.github

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Refresh on inspection; this adds no database work to streaming composition. */
internal suspend fun WorkspaceRepository.gitHubWorkTiming(
    chatId: Int,
    binding: String,
    contract: JsonObject?,
    decisions: List<GitHubChatDecision>,
    measurement: GitHubWorkMeasurement,
    snapshotAtMillis: Long?,
    observedAtMillis: Long
): GitHubTaskWorkTiming {
    val captured = database.withTransaction {
        val runs = dao.githubTimingRuns(chatId, "github-evidence:$binding:")
        val ids = runs.take(64).map { it.runId }
        TimingRows(runs, dao.githubTimingInvocations(ids), dao.githubTimingTools(ids), dao.githubTimingWaits(ids))
    }
    val runs = captured.runs.take(64)
    val runIds = runs.mapTo(hashSetOf()) { it.runId }
    val truncated = captured.runs.size > 64 || captured.invocations.size > 4096 || captured.tools.size > 4096
    val invalidOperationTimes = captured.invocations.take(4096).any { GitHubTimingInterval.duration(it.startMillis, it.durationMillis) == null } ||
        captured.tools.take(4096).any { GitHubTimingInterval.seconds(it.startSeconds, it.endSeconds) == null }
    val active = captured.invocations.take(4096).groupBy { it.runId }.mapValues { (_, rows) ->
        rows.mapNotNull { GitHubTimingInterval.duration(it.startMillis, it.durationMillis) }
    }.toMutableMap()
    captured.tools.take(4096).groupBy { it.runId }.forEach { (id, rows) ->
        active[id] = active[id].orEmpty() + rows.mapNotNull { GitHubTimingInterval.seconds(it.startSeconds, it.endSeconds) }
    }
    val waits = captured.waits.associate { row -> row.runId to readWaitTiming(row.checkpoint) }
    val relevantDecisions = decisions.filter { it.chatId == chatId && it.runId in runIds && !it.automatic }
    fun decisionIntervals(values: List<GitHubChatDecision>, until: Long): List<GitHubTimingInterval> = values.mapNotNull { decision ->
        val start = decision.offeredAtMillis ?: return@mapNotNull null
        val end = decision.answeredAtMillis ?: until.takeIf { decision.answer == null } ?: return@mapNotNull null
        GitHubTimingInterval(start, end)
    }
    fun waitIntervals(runId: String, until: Long?): List<GitHubTimingInterval> = waits[runId]?.let { wait ->
        wait.closed + listOfNotNull(wait.started?.let { start -> until?.let { GitHubTimingInterval(start, it) } })
    }.orEmpty()
    val runEnd = runs.associate { run ->
        run.runId to (
            run.completedAtSeconds?.let(GitHubTimingInterval::millis)?.coerceAtMost(observedAtMillis)
                ?: observedAtMillis.takeIf { run.status in setOf("QUEUED", "RUNNING", "WAITING_PROVIDER_LIMIT", "WAITING_REMOTE_JOB") }
            )
    }
    val projectedRuns = runs.map { run ->
        val start = GitHubTimingInterval.millis(run.createdAtSeconds)
        val end = runEnd.getValue(run.runId)
        val wait = waits[run.runId]
        val choices = relevantDecisions.filter { it.runId == run.runId }
        GitHubRunWorkTiming(
            run.runId,
            start?.takeIf { end != null }?.let {
                GitHubWorkTiming.project(
                    it,
                    requireNotNull(end),
                    active[run.runId].orEmpty(),
                    waitIntervals(run.runId, end),
                    decisionIntervals(choices, end),
                    wait?.unlocatedMillis ?: 0L.takeIf { wait == null },
                    truncated || invalidOperationTimes || wait?.incomplete == true || choices.any { it.offeredAtMillis == null || it.answer != null && it.answeredAtMillis == null }
                )
            }
        )
    }
    val accepted = contract.number("accepted_at_ms")?.takeIf { it >= 0 }
    val firstRun = runs.mapNotNull { GitHubTimingInterval.millis(it.createdAtSeconds) }.minOrNull()
    val start = accepted ?: firstRun
    val snapshotEnd = snapshotAtMillis?.takeIf { it >= 0 }?.coerceAtMost(observedAtMillis)
    val terminalEnd = runs.mapNotNull { it.completedAtSeconds?.let(GitHubTimingInterval::millis) }.maxOrNull()?.coerceAtMost(observedAtMillis)
    val end = when {
        measurement.completed -> snapshotEnd ?: terminalEnd
        // Cancellation/rejection can be newer than the last verification snapshot.
        // Preserve later recorded work and waits without extending past observation.
        measurement.state in setOf("REJECTED", "CANCELED") -> listOfNotNull(snapshotEnd, terminalEnd).maxOrNull()
        else -> observedAtMillis
    }
    val missingChoices = relevantDecisions.any { it.offeredAtMillis == null || it.answer != null && it.answeredAtMillis == null }
    val unlocated = if (waits.values.any { it.unlocatedMillis == null }) null else waits.values.fold(0L as Long?) { total, wait -> total?.let { runCatching { Math.addExact(it, requireNotNull(wait.unlocatedMillis)) }.getOrNull() } }
    val task = if (start == null || end == null) {
        null
    } else {
        GitHubWorkTiming.project(
            start,
            end,
            active.values.flatten(),
            runs.flatMap { waitIntervals(it.runId, runEnd.getValue(it.runId)?.coerceAtMost(end)) },
            decisionIntervals(relevantDecisions, end),
            unlocated,
            truncated || invalidOperationTimes || waits.values.any { it.incomplete } || missingChoices || accepted == null || runs.isEmpty() || projectedRuns.any { it.timing == null }
        )
    }
    return GitHubTaskWorkTiming(
        task,
        projectedRuns,
        if (accepted != null) {
            "ACCEPTED_TASK_CONTRACT"
        } else if (firstRun != null) {
            "EARLIEST_RETAINED_RUN"
        } else {
            "UNAVAILABLE"
        }
    )
}

private data class TimingRows(
    val runs: List<dev.chungjungsoo.gptmobile.data.workspace.GitHubTimingRun>,
    val invocations: List<dev.chungjungsoo.gptmobile.data.workspace.GitHubTimingInvocation>,
    val tools: List<dev.chungjungsoo.gptmobile.data.workspace.GitHubTimingTool>,
    val waits: List<dev.chungjungsoo.gptmobile.data.workspace.GitHubTimingWait>
)

private data class WaitTiming(val closed: List<GitHubTimingInterval>, val started: Long?, val unlocatedMillis: Long?, val incomplete: Boolean)

private fun JsonObject?.number(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun readWaitTiming(checkpoint: String): WaitTiming {
    val value = checkpoint.takeIf { it.length <= 262144 }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ?: return WaitTiming(emptyList(), null, null, true)
    val raw = (value["closedWaitIntervals"] as? JsonArray).orEmpty()
    val closed = raw.takeLast(128).mapNotNull { element ->
        val interval = element as? JsonObject ?: return@mapNotNull null
        val start = interval.number("startMillis") ?: return@mapNotNull null
        val end = interval.number("endMillis") ?: return@mapNotNull null
        GitHubTimingInterval(start, end).takeIf { start >= 0 && end >= start }
    }
    val accumulated = value.number("accumulatedProviderWaitMillis")?.takeIf { it >= 0 }
    val located = GitHubWorkTiming.project(0, Long.MAX_VALUE, providerWait = closed)?.providerWaitMillis ?: 0
    val unlocated = accumulated?.takeIf { it >= located }?.minus(located)
    val complete = value["closedWaitIntervalsComplete"] == JsonPrimitive(true) && raw.size == closed.size && unlocated == 0L
    return WaitTiming(closed, value.number("waitStartedAtMillis")?.takeIf { it >= 0 }, unlocated, !complete)
}
