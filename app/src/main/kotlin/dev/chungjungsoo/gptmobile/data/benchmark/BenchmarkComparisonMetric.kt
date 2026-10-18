package dev.chungjungsoo.gptmobile.data.benchmark

internal data class BenchmarkComparisonMetric(
    val label: String,
    val first: Double?,
    val second: Double?,
    val comparable: Boolean,
    val unit: String = "pts",
    val higherIsBetter: Boolean = true
) {
    val difference: Double? get() = if (comparable && first != null && second != null) second - first else null
}

/** Compare the same version, mode and execution cohort. Never subtract unrelated rating scales. */
internal fun benchmarkComparisonMetrics(first: BenchmarkScoreRow, second: BenchmarkScoreRow): List<BenchmarkComparisonMetric> {
    val compatible = first.cohort == second.cohort
    fun metric(label: String, a: Double?, b: Double?) = BenchmarkComparisonMetric(label, a, b, compatible)
    return listOf(
        metric("App rating", first.overall, second.overall),
        metric("Reasoning accuracy", first.intelligence?.score, second.intelligence?.score),
        metric("Functional task compliance", first.quality, second.quality),
        metric("Request completion", first.reliability, second.reliability),
        metric("Speed score", first.speedScore, second.speedScore),
        metric("Response score", first.latencyScore, second.latencyScore),
        metric("Consistency", first.consistency, second.consistency),
        metric("Task accuracy", first.accuracyByCategory["task"], second.accuracyByCategory["task"]),
        metric("Structured output", first.accuracyByCategory["json"], second.accuracyByCategory["json"]),
        metric("Tool success", first.accuracyByCategory["tools"], second.accuracyByCategory["tools"]),
        BenchmarkComparisonMetric("Generation", first.speed, second.speed, compatible, "tok/s"),
        BenchmarkComparisonMetric("First response", first.firstTextMs, second.firstTextMs, compatible, "ms", false)
    )
}
