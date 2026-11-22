package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class GitHubTimingInterval(val startMillis: Long, val endMillis: Long) {
    companion object {
        fun duration(startMillis: Long, durationMillis: Long): GitHubTimingInterval? =
            durationMillis.takeIf { it > 0 && startMillis >= 0 }?.let {
                runCatching { GitHubTimingInterval(startMillis, Math.addExact(startMillis, it)) }.getOrNull()
            }

        fun seconds(startSeconds: Long?, endSeconds: Long?): GitHubTimingInterval? =
            startSeconds?.let(::millis)?.let { start -> endSeconds?.let(::millis)?.let { end -> GitHubTimingInterval(start, end) } }

        fun millis(seconds: Long): Long? = seconds.takeIf { it >= 0 }?.let { runCatching { Math.multiplyExact(it, 1000L) }.getOrNull() }
    }
}

/** Recorded operation wall time is not CPU time. Unobserved gaps are never inferred to be suspension. */
internal data class GitHubWorkTiming(
    val elapsedMillis: Long,
    val observedActiveMillis: Long,
    val providerWaitMillis: Long,
    val userWaitMillis: Long,
    val unobservedMillis: Long,
    val overlappingEvidenceMillis: Long,
    val unlocatedProviderWaitMillis: Long?,
    val incompleteEvidence: Boolean
) {
    fun json() = buildJsonObject {
        put("schema", "github.work-timing.v1")
        put("clock", "WALL_CLOCK")
        put("elapsed_millis", elapsedMillis)
        put("observed_active_model_tool_millis", observedActiveMillis)
        put("observed_provider_wait_millis", providerWaitMillis)
        put("observed_user_wait_millis", userWaitMillis)
        put("unobserved_millis", unobservedMillis)
        put("overlapping_evidence_millis", overlappingEvidenceMillis)
        put("unlocated_provider_wait_millis", unlocatedProviderWaitMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("incomplete_evidence", incompleteEvidence)
        put("overlap_rule", "USER_WAIT_THEN_PROVIDER_WAIT_THEN_RECORDED_OPERATION")
        put("unlocated_provider_wait_in_elapsed_breakdown", false)
        put("unobserved_is_suspended", false)
    }

    fun description(): String = buildString {
        append("Elapsed ${githubElapsedTime(0, elapsedMillis)} · recorded model/tool ${githubElapsedTime(0, observedActiveMillis)}")
        append(" · provider wait ${githubElapsedTime(0, providerWaitMillis)} · user wait ${githubElapsedTime(0, userWaitMillis)}")
        append(" · unobserved ${githubElapsedTime(0, unobservedMillis)}.")
        if (overlappingEvidenceMillis > 0) append(" Overlapping waits and operation timestamps count once, with waits taking precedence.")
        if (unlocatedProviderWaitMillis != null && unlocatedProviderWaitMillis > 0) {
            append(" Earlier provider wait totals ${githubElapsedTime(0, unlocatedProviderWaitMillis)} across retained runs; interval locations are unavailable, so this is not added to the breakdown.")
        }
        if (incompleteEvidence) append(" Timing history is incomplete.")
    }

    companion object {
        private data class Edge(val time: Long, val category: Int, val change: Int)

        /** The union of each category is clipped to the window; waits exclude nested operation time. */
        fun project(
            startMillis: Long,
            endMillis: Long,
            active: List<GitHubTimingInterval> = emptyList(),
            providerWait: List<GitHubTimingInterval> = emptyList(),
            userWait: List<GitHubTimingInterval> = emptyList(),
            unlocatedProviderWaitMillis: Long? = 0,
            incompleteEvidence: Boolean = false
        ): GitHubWorkTiming? {
            if (startMillis < 0 || endMillis < startMillis) return null
            val elapsed = endMillis - startMillis
            var incomplete = incompleteEvidence || unlocatedProviderWaitMillis == null || unlocatedProviderWaitMillis != 0L
            val edges = ArrayList<Edge>()
            // Priority applies only to an overlap; it does not add parallel run durations.
            listOf(userWait, providerWait, active).forEachIndexed { category, intervals ->
                intervals.forEach { interval ->
                    if (interval.startMillis < 0 || interval.endMillis < interval.startMillis) {
                        incomplete = true
                    } else {
                        val first = maxOf(startMillis, interval.startMillis)
                        val last = minOf(endMillis, interval.endMillis)
                        if (last > first) {
                            edges.add(Edge(first, category, 1))
                            edges.add(Edge(last, category, -1))
                        }
                    }
                }
            }
            edges.sortBy { it.time }
            val running = IntArray(3)
            val totals = LongArray(3)
            var previous = startMillis
            var overlap = 0L
            var index = 0
            while (index < edges.size) {
                val at = edges[index].time
                val width = at - previous
                val category = running.indexOfFirst { it > 0 }
                if (category >= 0) totals[category] += width
                if (running.count { it > 0 } > 1) overlap += width
                while (index < edges.size && edges[index].time == at) {
                    val edge = edges[index++]
                    running[edge.category] += edge.change
                }
                previous = at
            }
            val remainder = elapsed - totals.sum()
            return GitHubWorkTiming(elapsed, totals[2], totals[1], totals[0], remainder, overlap, unlocatedProviderWaitMillis?.takeIf { it >= 0 }, incomplete)
        }
    }
}

internal data class GitHubRunWorkTiming(val runId: String, val timing: GitHubWorkTiming?)

internal data class GitHubTaskWorkTiming(val task: GitHubWorkTiming?, val runs: List<GitHubRunWorkTiming>, val startBasis: String) {
    fun json() = buildJsonObject {
        put("start_basis", startBasis)
        put("task", task?.json() ?: JsonNull)
        put(
            "runs",
            JsonArray(
                runs.map { run ->
                    buildJsonObject {
                        put("run_id", run.runId)
                        put("timing", run.timing?.json() ?: JsonNull)
                    }
                }
            )
        )
    }
}
