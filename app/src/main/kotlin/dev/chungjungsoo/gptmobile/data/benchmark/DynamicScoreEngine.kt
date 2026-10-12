package dev.chungjungsoo.gptmobile.data.benchmark

import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
internal data class BenchmarkScoreRow(
    val profileUid: String,
    val revision: String,
    val cohort: String,
    val sourceRunIds: List<String>,
    val speed: Double?,
    val firstTextMs: Double?,
    val speedScore: Double?,
    val latencyScore: Double?,
    val quality: Double?,
    val reliability: Double?,
    val consistency: Double?,
    val overall: Double?,
    val verified: Boolean,
    val sampleCount: Int,
    val explanation: String,
    val speedByBand: Map<String, Double> = emptyMap(),
    val latencyByBand: Map<String, Double> = emptyMap(),
    val accuracyByCategory: Map<String, Double> = emptyMap(),
    val unsupportedCategories: Set<String> = emptySet(),
    val intelligence: IntelligenceScore? = null
)

@Serializable
internal data class BenchmarkScoreSnapshot(
    val generation: Long,
    val createdAt: Long,
    val policy: String = "benchmark-score-v2",
    val rows: List<BenchmarkScoreRow>
)

internal fun benchmarkScoreDimensions(row: BenchmarkScoreRow?, local: Boolean): List<BenchmarkDimension> {
    val weights = if (local) listOf(40, 25, 10, 10, 15) else listOf(35, 20, 20, 15, 10)
    return listOf(
        BenchmarkDimension("Quality", row?.quality, weights[0], "Equal weighting of accuracy categories; unsupported capabilities are excluded"),
        BenchmarkDimension("Reliability", row?.reliability, weights[1], "Completed requests / attempted requests"),
        BenchmarkDimension("Speed", row?.speedScore, weights[2], "Equal average of short, medium and long rates against verified cohort leaders"),
        BenchmarkDimension("First response", row?.latencyScore, weights[3], "First text latency against verified leaders in each workload band"),
        BenchmarkDimension("Consistency", row?.consistency, weights[4], "Timing variation within each of the three workload bands")
    )
}

/** Pure scoring across all enrolled measurements. Presentation filters never enter this API. */
internal object DynamicScoreEngine {
    const val FRESHNESS_MS = 30L * 24 * 60 * 60 * 1000
    fun higher(value: Double?, anchor: Double?): Double? = if (valid(value) && valid(anchor)) (100.0 * value!! / anchor!!).coerceIn(0.0, 100.0) else null
    fun lower(value: Double?, anchor: Double?): Double? = if (valid(value) && valid(anchor)) (100.0 * anchor!! / value!!).coerceIn(0.0, 100.0) else null
    private fun valid(value: Double?) = value != null && value.isFinite() && value > 0
    fun median(values: List<Double>): Double? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    fun cohort(run: BenchmarkRun): String = listOf(
        run.mode.name,
        run.suiteVersion,
        run.measurementVersion,
        ReferenceTextTokenizer.VERSION,
        when {
            run.local -> "device:${run.device}:${run.powerPolicy}"
            run.provider in setOf("LLAMA", "OLLAMA") -> "self-hosted:${run.networkPolicy}"
            else -> "remote:${run.networkPolicy}"
        },
        "warm-serial-v1",
        "fixture-context:${run.fixtureContextTokens ?: "remote-standard"}"
    ).joinToString("|")

    fun snapshot(history: List<BenchmarkRun>, generation: Long, now: Long, activeRevisions: Map<String, String>? = null): BenchmarkScoreSnapshot {
        val fresh = history.filter { it.suiteVersion in 3..BenchmarkSuiteRegistry.VERSION && it.measurementVersion == 2 && it.mode != BenchmarkMode.DELEGATION && now - it.startedAt in 0..FRESHNESS_MS }
        // The most recently enrolled revision is active; previous revisions remain in history.
        val revisions = activeRevisions ?: fresh.groupBy { it.profileUid }.mapValues { (_, runs) -> runs.maxBy { it.startedAt }.configKey }
        val compatible = fresh.filter { revisions[it.profileUid] == it.configKey }
        val aggregated = compatible.groupBy { listOf(it.profileUid, it.configKey, cohort(it), it.backend.orEmpty(), it.accelerator.orEmpty()) }.values.map(::aggregateGroup)
        val anchors = aggregated.filter { it.verified }.groupBy { it.cohort }.mapValues { (_, rows) ->
            CohortAnchors(
                rows.mapNotNull { it.speed }.filter(::valid).maxOrNull(),
                listOf("short", "medium", "long").associateWith { band -> rows.mapNotNull { it.speedByBand[band] }.filter(::valid).maxOrNull() },
                listOf("short", "medium", "long").associateWith { band -> rows.mapNotNull { it.latencyByBand[band] }.filter(::valid).minOrNull() },
                rows.size
            )
        }
        val runsById = compatible.associateBy { it.id }
        val rows = aggregated.map { normalizeRow(it, anchors[it.cohort], runsById) }
        return BenchmarkScoreSnapshot(generation, now, rows = rows)
    }

    private fun aggregateGroup(group: List<BenchmarkRun>): BenchmarkScoreRow {
        val complete = group.filter { block ->
            val manifest = benchmarkSuiteForRun(block).associateBy { it.id }
            block.finished &&
                !block.canceled &&
                block.stoppedReason == null &&
                block.samples.size == block.plannedTrials &&
                block.plannedTrials == manifest.size &&
                block.samples.map { it.testId }.toSet() == manifest.keys &&
                block.samples.all { sample ->
                    manifest[sample.testId]?.let { case -> sample.category == case.category && sample.workload == case.workload && sample.warmup == case.warmup && sample.tokenBasis == ReferenceTextTokenizer.VERSION } == true
                }
        }.sortedByDescending { it.startedAt }.take(5)
        val run = group.maxBy { it.startedAt }
        val source = complete.ifEmpty { listOf(run) }
        val samples = source.flatMap { it.samples }.filterNot { it.warmup || it.outcome in setOf(BenchmarkOutcome.CANCELED, BenchmarkOutcome.QUOTA_BLOCKED, BenchmarkOutcome.POLICY_BLOCKED, BenchmarkOutcome.UNSUPPORTED, BenchmarkOutcome.SKIPPED) }
        val verified = run.mode in setOf(BenchmarkMode.FULL, BenchmarkMode.AGENT) &&
            complete.isNotEmpty() &&
            complete.all { block -> listOf("short", "medium", "long").all { band -> block.samples.count { !it.warmup && it.workload == band && it.referenceSpeed != null && it.outcome == BenchmarkOutcome.PASSED } >= 5 } }
        val speedByBand = listOf("short", "medium", "long").mapNotNull { band ->
            median(source.mapNotNull { block -> median(block.samples.filter { !it.warmup && it.category == "speed" && it.workload == band }.mapNotNull { it.referenceSpeed }) })?.let { band to it }
        }.toMap()
        val latencyByBand = listOf("short", "medium", "long").mapNotNull { band ->
            median(source.mapNotNull { block -> median(block.samples.filter { !it.warmup && it.category == "speed" && it.workload == band && it.completed }.mapNotNull { it.firstTextMs?.toDouble() }) })?.let { band to it }
        }.toMap()
        val speed = median(
            source.mapNotNull { block ->
                median(block.samples.filter { !it.warmup && it.category == "speed" }.mapNotNull { it.referenceSpeed })
            }
        )
        val latency = median(samples.filter { it.completed }.mapNotNull { it.firstTextMs?.toDouble() })
        val qualityCases = samples.filter { it.category != "speed" && it.category != IntelligenceBenchmark.CATEGORY }
        val quality = qualityCases.groupBy { it.workload }.values.takeIf { it.isNotEmpty() }?.map { category -> 100.0 * category.count { it.outcome == BenchmarkOutcome.PASSED } / category.size }?.average()
        val reliability = samples.filter { it.category != IntelligenceBenchmark.CATEGORY }.takeIf { it.isNotEmpty() }?.let { 100.0 * it.count { sample -> sample.completed } / it.size }
        val consistency = samples.filter { it.category == "speed" }.groupBy { it.workload }.values.mapNotNull { band ->
            band.mapNotNull { it.referenceSpeed }.takeIf { it.size >= 3 && it.average() > 0 }?.let { values ->
                val mean = values.average()
                100.0 * (1 - sqrt(values.map { (it - mean) * (it - mean) }.average()) / mean).coerceIn(0.0, 1.0)
            }
        }.takeIf { it.size == 3 }?.average()
        val accuracy = qualityCases.groupBy { it.category }.mapValues { (_, cases) -> 100.0 * cases.count { it.outcome == BenchmarkOutcome.PASSED } / cases.size }
        val unsupported = source.flatMap { it.samples }.filter { it.outcome == BenchmarkOutcome.UNSUPPORTED }.map { it.category }.toSet()
        val intelligence = source.maxBy { it.startedAt }.takeIf { it.suiteVersion >= 5 }?.let {
            when {
                it.suiteVersion >= 6 -> IntelligenceBenchmark.scoreBatches(it.samples, it.mode == BenchmarkMode.QUICK)
                it.mode != BenchmarkMode.QUICK -> IntelligenceBenchmark.score(it.samples)
                else -> null
            }
        }
        val timingCoverage = listOf("short", "medium", "long").joinToString { band -> "$band ${run.samples.count { !it.warmup && it.workload == band && it.referenceSpeed != null && it.outcome == BenchmarkOutcome.PASSED }}/5" }
        return BenchmarkScoreRow(run.profileUid, run.configKey, cohort(run), source.map { it.id }, speed, latency, null, null, quality, reliability, consistency, null, verified, samples.size, if (verified) "Verified warm block; median of up to five complete blocks" else "Provisional timing: $timingCoverage successful timed trials; a complete Standard run is required to establish an anchor", speedByBand, latencyByBand, accuracy, unsupported, intelligence)
    }

    private data class CohortAnchors(val speed: Double?, val speeds: Map<String, Double?>, val latencies: Map<String, Double?>, val count: Int)

    private fun normalizeRow(row: BenchmarkScoreRow, anchors: CohortAnchors?, runsById: Map<String, BenchmarkRun>): BenchmarkScoreRow {
        val anchor = anchors?.speed
        val speedScore = row.speedByBand.mapNotNull { (band, value) -> higher(value, anchors?.speeds?.get(band)) }.takeIf { it.size == 3 }?.average()
        val latencyScore = row.latencyByBand.mapNotNull { (band, value) -> lower(value, anchors?.latencies?.get(band)) }.takeIf { it.size == 3 }?.average()
        val local = row.cohort.contains("device:")
        val weights = if (local) listOf(40, 25, 10, 10, 15) else listOf(35, 20, 20, 15, 10)
        val dimensions = listOf(row.quality, row.reliability, speedScore, latencyScore, row.consistency)
        val requiredQuality = row.sourceRunIds.mapNotNull(runsById::get).all { block ->
            val manifest = benchmarkSuiteForRun(block).filter { it.category != "speed" && it.category != IntelligenceBenchmark.CATEGORY }
            manifest.all { test -> block.samples.any { it.testId == test.id && it.outcome !in setOf(BenchmarkOutcome.CANCELED, BenchmarkOutcome.SKIPPED, BenchmarkOutcome.QUOTA_BLOCKED, BenchmarkOutcome.POLICY_BLOCKED) } }
        }
        val overall = if (row.verified && requiredQuality && dimensions.all { it != null }) minOf(dimensions.zip(weights).sumOf { (value, weight) -> value!! * weight } / 100, row.quality!!, row.reliability!!) else null
        return row.copy(
            speedScore = speedScore,
            latencyScore = latencyScore,
            overall = overall,
            explanation = row.explanation + if (anchor != null) "; speed = equal mean of 100 × band rate / verified band leader; ${anchors?.count ?: 0} eligible profiles" else "; no verified cohort anchor"
        )
    }
}
