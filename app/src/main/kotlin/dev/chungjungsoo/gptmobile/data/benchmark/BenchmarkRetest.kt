package dev.chungjungsoo.gptmobile.data.benchmark

internal data class BenchmarkRetest(val current: BenchmarkScoreRow, val previous: BenchmarkScoreRow?)
internal fun BenchmarkScoreRow.retestKey(): String = resultId.ifBlank { "$profileUid|$revision|$cohort|$backend|$accelerator" }

/** Score individual runs against the same current cohort anchors, never a rolling average. */
internal fun benchmarkRetests(history: List<BenchmarkRun>, now: Long = System.currentTimeMillis()): List<BenchmarkRetest> {
    val complete = history.filter { run ->
        run.mode != BenchmarkMode.DELEGATION &&
            run.finished &&
            !run.canceled &&
            run.stoppedReason == null &&
            run.samples.size == benchmarkSuiteForRun(run).size &&
            run.samples.map { it.testId }.toSet() == benchmarkSuiteForRun(run).map { it.id }.toSet()
    }
    val byId = complete.associateBy { it.id }
    val isolated = complete.map { it.copy(profileUid = it.id, configKey = it.id) }
    val rows = DynamicScoreEngine.snapshot(isolated, 0, now).rows.mapNotNull { row ->
        byId[row.sourceRunIds.firstOrNull()]?.let { run -> row.copy(profileUid = run.profileUid, revision = run.configKey, resultId = benchmarkResultId(run)) }
    }
    return rows.groupBy { it.retestKey() }.values.map { group ->
        val ordered = group.sortedByDescending { row -> byId[row.sourceRunIds.firstOrNull()]?.startedAt ?: 0 }
        BenchmarkRetest(ordered.first(), ordered.getOrNull(1))
    }
}
