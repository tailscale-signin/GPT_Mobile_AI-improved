package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMetricResult
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStopOrigin

internal fun benchmarkAttemptStatus(run: BenchmarkRun?, status: String): String =
    if (run?.cancellationOrigin == BenchmarkStopOrigin.USER_STOP) "stopped by you" else status.lowercase().replace('_', ' ')

internal fun benchmarkPlanCompletionDescription(metric: BenchmarkMetricResult): String {
    val progress = when {
        !metric.plannedKnown -> "planned population unavailable"
        metric.denominator <= 0 -> "no supported planned cases"
        else ->
            "${metric.numerator}/${metric.denominator} successful planned cases" +
                if (metric.value == null) " · run incomplete or stopped" else ""
    }
    return "Plan completion: $progress. This is progress, not answer accuracy."
}

/** Admission identifiers do not establish that bytes reached a server. */
internal fun benchmarkDispatchDescription(sample: BenchmarkSample): String {
    val dispatch = when (sample.requestDispatched) {
        true -> "recorded"
        false -> "not recorded"
        null -> "unknown"
    }
    val response = sample.providerHttpStatus?.let { "observed ($it)" } ?: "not recorded"
    return "Fixture dispatch: $dispatch · HTTP response: $response · admission records: ${sample.physicalRequestIds.size}"
}

internal fun benchmarkPreparationLines(run: BenchmarkRun): List<String> {
    val preparation = run.preparation ?: return emptyList()
    return buildList {
        val status = if (preparation.status == "CANCELED" && run.cancellationOrigin == BenchmarkStopOrigin.USER_STOP) "stopped by you" else preparation.status.lowercase().replace('_', ' ')
        add("Preparation: ${preparation.stage.replace('_', ' ')} · $status" + preparation.elapsedMs?.let { " · ${it.coerceAtLeast(0) / 1000}s observed" }.orEmpty())
        preparation.lastObservedElapsedMs?.let { add("Last observed preparation: ${it.coerceAtLeast(0) / 1000}s · final duration unknown") }
        preparation.queueWaitMs?.let {
            val label = if (preparation.lastObservedElapsedMs != null) "Last observed admission wait" else "Admission wait"
            add("$label: ${it.coerceAtLeast(0) / 1000}s" + preparation.queueDeadlineMs?.let { limit -> " · wait limit ${limit.coerceAtLeast(0) / 1000}s" }.orEmpty())
        }
        if (preparation.blockerLabels.isNotEmpty()) {
            // Historical/imported receipts must not reveal unsanitized owner names or addresses.
            val safe = preparation.blockerLabels.filter {
                it in setOf("Earlier queued measurement", "Earlier queued inference") ||
                    it.matches(Regex("(?i)(?:measurement|inference|interactive|background|benchmark)[ :][a-f0-9]{8,64}"))
            }
            val waiting = preparation.status == "WAITING"
            add("${if (waiting) "Waiting for" else "Admission waited for"} ${if (safe.isEmpty()) "another request" else safe.joinToString()}")
        }
        if (preparation.status in setOf("CANCELED", "FAILED", "TIMED_OUT", "INTERRUPTED") && run.samples.none { it.requestDispatched == true || it.providerHttpStatus != null }) {
            add("Preparation ended before any fixture dispatch was recorded. Readiness may have contacted the provider separately.")
        }
    }
}
