package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

@Serializable
internal data class BenchmarkRunSelection(
    val runId: String,
    val resultId: String,
    val cohort: String,
    val enrolledRevision: String?,
    val capabilityIncluded: Boolean,
    val operationalIncluded: Boolean,
    val scoreRowIds: List<String>,
    val exclusionReasons: List<String>,
    val snapshotPolicy: String?,
    val selectedAt: Long?,
    val enrollmentProvenance: String,
    val latestAttemptStatusRowIds: List<String> = emptyList()
)

internal fun benchmarkRunSelection(runs: List<BenchmarkRun>, snapshot: BenchmarkScoreSnapshot?, currentEnrollment: Map<String, String>?): List<BenchmarkRunSelection> {
    val enrollment = snapshot?.enrollment ?: currentEnrollment
    return runs.map { run ->
        val capabilityRows = snapshot?.rows.orEmpty().filter { run.id in it.sourceRunIds }
        val operationalRows = snapshot?.rows.orEmpty().filter { row -> row.metrics.any { metric -> metric.id in benchmarkOperationalMetricIds && run.id in metric.sourceRunIds } }
        BenchmarkRunSelection(
            run.id, benchmarkResultId(run), DynamicScoreEngine.cohort(run), enrollment?.let { benchmarkEnrolledRevision(it, run) },
            capabilityRows.isNotEmpty(), operationalRows.isNotEmpty(), (capabilityRows + operationalRows).map { it.resultId }.distinct(),
            buildList {
                if (run.mode == BenchmarkMode.PERFORMANCE) add("SEPARATE_PERFORMANCE_DIMENSIONS_NO_OVERALL")
                if (run.mode == BenchmarkMode.CPU_EXPERIMENT) add("CONTROLLED_CPU_DIAGNOSTIC_NOT_RANKED")
                if (run.mode == BenchmarkMode.DELEGATION) add("SEPARATE_DELEGATION_SCORE_DOMAIN")
                if (enrollment != null && benchmarkEnrolledRevision(enrollment, run) != run.configKey) add("INACTIVE_OR_UNENROLLED_REVISION")
                if (snapshot != null && snapshot.createdAt - run.startedAt !in 0..DynamicScoreEngine.FRESHNESS_MS) add("OUTSIDE_SNAPSHOT_FRESHNESS_WINDOW")
                if (run.suiteVersion !in 3..BenchmarkSuiteRegistry.VERSION) add("UNSUPPORTED_FIXTURE_COMPATIBILITY")
                if (run.measurementVersion !in setOf(2, 3, 4)) add("UNSUPPORTED_MEASUREMENT_COMPATIBILITY")
                if (!run.finished) add("INCOMPLETE_RUN")
                if (run.canceled) add("USER_CANCELED_RUN")
                if (run.stoppedReason != null) add("RUN_STOPPED_WITH_RETAINED_EVIDENCE")
                if (capabilityRows.isEmpty()) add("NOT_IN_STORED_CAPABILITY_POPULATION")
                if (operationalRows.isEmpty()) add("NOT_IN_STORED_OPERATIONAL_POPULATION")
                if (snapshot == null) add("SCORE_SNAPSHOT_UNAVAILABLE")
            },
            snapshot?.policy, snapshot?.createdAt,
            when {
                snapshot?.enrollment != null -> "CAPTURED_WITH_SCORE_SNAPSHOT"
                currentEnrollment != null -> "CURRENT_MAPPING_LEGACY_SNAPSHOT_MAPPING_UNKNOWN"
                else -> "UNKNOWN"
            },
            latestAttemptStatusRowIds = snapshot?.rows.orEmpty().filter { it.latestAttemptRunId == run.id }.map { it.resultId }
        )
    }
}
