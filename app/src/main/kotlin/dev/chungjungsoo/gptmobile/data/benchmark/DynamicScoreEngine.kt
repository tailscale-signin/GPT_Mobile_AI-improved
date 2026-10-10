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
    val latencyByBand: Map<String, Double> = emptyMap()
)

@Serializable
internal data class BenchmarkScoreSnapshot(
    val generation: Long,
    val createdAt: Long,
    val policy: String = "benchmark-score-v2",
    val rows: List<BenchmarkScoreRow>
)

/** Pure scoring across all enrolled measurements. Presentation filters never enter this API. */
internal object DynamicScoreEngine {
    const val FRESHNESS_MS = 30L * 24 * 60 * 60 * 1000
    fun higher(value: Double?, anchor: Double?): Double? = if (valid(value) && valid(anchor)) 100.0 * value!! / anchor!! else null
    fun lower(value: Double?, anchor: Double?): Double? = if (valid(value) && valid(anchor)) 100.0 * anchor!! / value!! else null
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
        "warm-serial-v1"
    ).joinToString("|")

    fun snapshot(history: List<BenchmarkRun>, generation: Long, now: Long): BenchmarkScoreSnapshot {
        val fresh = history.filter { it.suiteVersion == 2 && it.mode != BenchmarkMode.DELEGATION && now - it.startedAt in 0..FRESHNESS_MS }
        // The most recently enrolled revision is active; previous revisions remain in history.
        val revisions = fresh.groupBy { it.profileUid }.mapValues { (_, runs) -> runs.maxBy { it.startedAt }.configKey }
        val compatible = fresh.filter { revisions[it.profileUid] == it.configKey }
        val aggregated = compatible.groupBy { listOf(it.profileUid, it.configKey, cohort(it), it.backend.orEmpty(), it.accelerator.orEmpty()) }.values.map { group ->
            val complete = group.filter { it.finished && !it.canceled && it.stoppedReason == null && it.samples.size == it.plannedTrials && it.plannedTrials == benchmarkSuite(it.mode).size }.sortedByDescending { it.startedAt }.take(5)
            val run = group.maxBy { it.startedAt }
            val source = complete.ifEmpty { listOf(run) }
            val samples = source.flatMap { it.samples }.filterNot { it.warmup || it.outcome == BenchmarkOutcome.CANCELED }
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
            val qualityCases = samples.filter { it.category != "speed" && it.completed }
            val quality = qualityCases.groupBy { it.workload }.values.takeIf { it.isNotEmpty() }?.map { category -> 100.0 * category.count { it.outcome == BenchmarkOutcome.PASSED } / category.size }?.average()
            val reliability = samples.takeIf { it.isNotEmpty() }?.let { 100.0 * it.count { sample -> sample.completed } / it.size }
            val consistency = samples.filter { it.category == "speed" }.groupBy { it.workload }.values.mapNotNull { band ->
                band.mapNotNull { it.referenceSpeed }.takeIf { it.size >= 3 && it.average() > 0 }?.let { values ->
                    val mean = values.average()
                    100.0 * (1 - sqrt(values.map { (it - mean) * (it - mean) }.average()) / mean).coerceIn(0.0, 1.0)
                }
            }.takeIf { it.size == 3 }?.average()
            BenchmarkScoreRow(run.profileUid, run.configKey, cohort(run), source.map { it.id }, speed, latency, null, null, quality, reliability, consistency, null, verified, samples.size, if (verified) "Verified warm block; median of up to five complete blocks" else "Provisional or incomplete; cannot establish an official anchor", speedByBand, latencyByBand)
        }
        val rows = aggregated.map { row ->
            val cohortRows = aggregated.filter { it.cohort == row.cohort && it.verified }
            val anchor = cohortRows.mapNotNull { it.speed }.filter(::valid).maxOrNull()
            val speedScore = row.speedByBand.mapNotNull { (band, value) -> higher(value, cohortRows.mapNotNull { it.speedByBand[band] }.filter(::valid).maxOrNull()) }.takeIf { it.size == 3 }?.average()
            val latencyScore = row.latencyByBand.mapNotNull { (band, value) -> lower(value, cohortRows.mapNotNull { it.latencyByBand[band] }.filter(::valid).minOrNull()) }.takeIf { it.size == 3 }?.average()
            val local = row.cohort.contains("device:")
            val weights = if (local) listOf(40, 25, 10, 10, 15) else listOf(35, 20, 20, 15, 10)
            val dimensions = listOf(row.quality, row.reliability, speedScore, latencyScore, row.consistency)
            val requiredQuality = compatible.filter { it.id in row.sourceRunIds }.all { block -> block.samples.count { it.category != "speed" && it.completed } == if (block.mode == BenchmarkMode.AGENT) 30 else 18 }
            val overall = if (row.verified && requiredQuality && dimensions.all { it != null }) minOf(dimensions.zip(weights).sumOf { (value, weight) -> value!! * weight } / 100, row.quality!!, row.reliability!!) else null
            row.copy(
                speedScore = speedScore,
                latencyScore = latencyScore,
                overall = overall,
                explanation = row.explanation + if (anchor != null) "; speed = equal mean of 100 × band rate / verified band leader; ${cohortRows.size} eligible profiles" else "; no verified cohort anchor"
            )
        }
        return BenchmarkScoreSnapshot(generation, now, rows = rows)
    }
}
