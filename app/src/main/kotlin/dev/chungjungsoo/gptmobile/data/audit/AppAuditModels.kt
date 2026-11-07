package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** Functional observations never imply instrumentation or source-line coverage. */
@Serializable
enum class AppAuditOutcome { PASS, FAIL, SKIPPED, BLOCKED, UNAVAILABLE, UNTESTED, CANCELED }

@Serializable
enum class AppAuditEvidenceKind { CONFIGURATION, LOCAL_CONTRACT, LIVE_METADATA, TOOL_EXECUTION, MODEL_EXECUTION, DEVICE_MEASUREMENT, EXTERNAL_SUITE }

@Serializable
enum class AppAuditSafetyClass { LOCAL_READ, NETWORK_READ, MODEL_REQUEST, EXTERNAL_ONLY }

@Serializable
enum class AppAuditPrerequisite { READY, NOT_CONFIGURED, UNSUPPORTED, PERMISSION_REQUIRED, EXTERNAL_HARNESS_REQUIRED }

@Serializable
enum class AppAuditDispatchEvidence { NOT_ATTEMPTED, NOT_DISPATCHED, ATTEMPTED, CONFIRMED, UNKNOWN }

@Serializable
enum class AppAuditObservationSource { CONFIGURATION, FIXTURE, LIVE, CACHE, NONE }

@Serializable
enum class AppAuditMetricName { TARGET_COUNT, TOOL_COUNT, ENABLED_COUNT, FILE_COUNT, LINE_COUNT, CHECK_COUNT, PAYLOAD_BYTES, DURATION_MS, RESULT_COUNT }

@Serializable
enum class AppAuditMetricUnit { COUNT, BYTES, MILLISECONDS }

@Serializable
data class AppAuditMetric(val name: AppAuditMetricName, val value: Double, val unit: AppAuditMetricUnit = AppAuditMetricUnit.COUNT) {
    init {
        require(value.isFinite() && value >= 0)
    }
}

@Serializable
data class AppAuditOptions(
    val allowNetworkReads: Boolean = false,
    val selectedProfileIds: List<String> = emptyList()
)

/** These bound logical probes; the existing clients retain their physical request limits. */
@Serializable
data class AppAuditLimits(
    val maxExecutedProbes: Int = 128,
    val maxNetworkProbes: Int = 16,
    val perProbeTimeoutMs: Long = 15_000,
    val totalTimeoutMs: Long = 180_000,
    val maxMetricsPerProbe: Int = 12
) {
    init {
        require(maxExecutedProbes in 1..512)
        require(maxNetworkProbes in 0..128)
        require(perProbeTimeoutMs in 1..60_000)
        require(totalTimeoutMs in 1..600_000)
        require(maxMetricsPerProbe in 0..32)
    }
}

@Serializable
data class AppAuditPlanEntry(
    val id: String,
    val sourceId: String,
    val probeId: String,
    val componentId: String,
    val label: String,
    val targetId: String? = null,
    val sourceAreaIds: List<String> = emptyList(),
    val evidenceKind: AppAuditEvidenceKind = AppAuditEvidenceKind.CONFIGURATION,
    val safetyClass: AppAuditSafetyClass = AppAuditSafetyClass.LOCAL_READ,
    val selected: Boolean = true,
    val prerequisite: AppAuditPrerequisite = AppAuditPrerequisite.READY,
    val reasonCode: String? = null,
    val configFingerprint: String? = null,
    val fixtureHash: String? = null,
    val weight: Int = 1
) {
    init {
        require(listOf(id, sourceId, probeId, componentId).all(::appAuditIdentifier))
        require(label.isNotBlank() && label.length <= 120 && label.none(Char::isISOControl))
        require(sourceAreaIds.all(::appAuditIdentifier))
        require(reasonCode == null || appAuditIdentifier(reasonCode))
        require(weight in 1..100)
    }
}

/** Only fixed codes, enums and numeric metrics cross a probe's diagnostic boundary. */
@Serializable
data class AppAuditObservation(
    val outcome: AppAuditOutcome,
    val reasonCode: String,
    val stage: String = "validation",
    val evidenceKind: AppAuditEvidenceKind = AppAuditEvidenceKind.CONFIGURATION,
    val observationSource: AppAuditObservationSource = AppAuditObservationSource.NONE,
    val dispatchEvidence: AppAuditDispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED,
    val retryable: Boolean? = null,
    val httpStatus: Int? = null,
    val metrics: List<AppAuditMetric> = emptyList(),
    val configChanged: Boolean = false
) {
    init {
        require(appAuditIdentifier(reasonCode) && appAuditIdentifier(stage))
        require(httpStatus == null || httpStatus in 100..599)
        require(metrics.size <= 32)
    }
}

@Serializable
data class AppAuditResult(
    val entryId: String,
    val observation: AppAuditObservation,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val durationMs: Long?,
    val attempted: Boolean = false
) {
    val outcome: AppAuditOutcome get() = observation.outcome
    val reasonCode: String get() = observation.reasonCode
    val evidenceKind: AppAuditEvidenceKind get() = observation.evidenceKind
    val observationSource: AppAuditObservationSource get() = observation.observationSource
    val dispatchEvidence: AppAuditDispatchEvidence get() = observation.dispatchEvidence
}

/** Stored in BenchmarkBatch.audit; the enclosing batch is the lifecycle authority. */
@Serializable
data class AppAuditRun(
    val id: String,
    val startedAtEpochMs: Long,
    val catalogHash: String,
    val sourceCommit: String,
    val buildVersion: String,
    val buildVariant: String,
    val sourceInventoryDigest: String,
    val sourceFileCount: Int,
    val sourceLineCount: Int,
    val plan: List<AppAuditPlanEntry>,
    val sourceAreas: List<AppAuditSourceArea> = emptyList(),
    val options: AppAuditOptions = AppAuditOptions(),
    val limits: AppAuditLimits = AppAuditLimits(),
    val schemaVersion: Int = 1,
    val catalogVersion: String = "app-audit-v1",
    val lineCoverageAvailable: Boolean = false,
    val results: List<AppAuditResult> = emptyList(),
    val activeProbeId: String? = null,
    val activeStartedAtEpochMs: Long? = null,
    val activeDispatchEvidence: AppAuditDispatchEvidence = AppAuditDispatchEvidence.NOT_ATTEMPTED,
    val executedProbes: Int = 0,
    val networkProbesDispatched: Int = 0,
    val updatedAtEpochMs: Long = startedAtEpochMs,
    val finishedAtEpochMs: Long? = null,
    val stopReasonCode: String? = null
) {
    val pendingEntries: List<AppAuditPlanEntry> get() = plan.filter { entry -> results.none { it.entryId == entry.id } && activeProbeId != entry.id }
    val deadlineAtEpochMs: Long get() = startedAtEpochMs + limits.totalTimeoutMs
}

data class AppAuditSummary(
    val planned: Int,
    val accounted: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val blocked: Int,
    val unavailable: Int,
    val untested: Int,
    val canceled: Int,
    val exercised: Int,
    val verified: Int,
    val sourceAreasPlanned: Int,
    val sourceAreasExercised: Int
)

data class AppAuditScore(val passedWeight: Int, val measuredWeight: Int) {
    val score: Double? get() = measuredWeight.takeIf { it > 0 }?.let { 100.0 * passedWeight / it }
}

fun appAuditSummary(run: AppAuditRun): AppAuditSummary {
    val plannedIds = run.plan.map { it.id }.toSet()
    val results = run.results.filter { it.entryId in plannedIds }.distinctBy { it.entryId }
    val measured = measuredAppAuditResults(run)
    fun count(outcome: AppAuditOutcome) = results.count { it.outcome == outcome }
    val exercisedIds = measured.map { it.first.id }.toSet()
    return AppAuditSummary(
        planned = plannedIds.size,
        accounted = results.size,
        passed = count(AppAuditOutcome.PASS),
        failed = count(AppAuditOutcome.FAIL),
        skipped = count(AppAuditOutcome.SKIPPED),
        blocked = count(AppAuditOutcome.BLOCKED),
        unavailable = count(AppAuditOutcome.UNAVAILABLE),
        untested = count(AppAuditOutcome.UNTESTED) + plannedIds.size - results.size,
        canceled = count(AppAuditOutcome.CANCELED),
        exercised = measured.size,
        verified = measured.count { it.second.outcome == AppAuditOutcome.PASS },
        sourceAreasPlanned = run.sourceAreas.map { it.id }.ifEmpty { run.plan.flatMap { it.sourceAreaIds } }.distinct().size,
        sourceAreasExercised = run.plan.filter { it.id in exercisedIds }.flatMap { it.sourceAreaIds }.distinct().size
    )
}

fun appAuditScore(run: AppAuditRun): AppAuditScore {
    val measured = measuredAppAuditResults(run)
    return AppAuditScore(
        passedWeight = measured.filter { it.second.outcome == AppAuditOutcome.PASS }.sumOf { it.first.weight },
        measuredWeight = measured.sumOf { it.first.weight }
    )
}

private fun measuredAppAuditResults(run: AppAuditRun): List<Pair<AppAuditPlanEntry, AppAuditResult>> {
    val byId = run.plan.associateBy { it.id }
    return run.results.distinctBy { it.entryId }.mapNotNull { result ->
        val entry = byId[result.entryId] ?: return@mapNotNull null
        if (result.attempted &&
            result.outcome in setOf(AppAuditOutcome.PASS, AppAuditOutcome.FAIL) &&
            entry.evidenceKind == result.evidenceKind &&
            result.evidenceKind !in setOf(AppAuditEvidenceKind.CONFIGURATION, AppAuditEvidenceKind.EXTERNAL_SUITE) &&
            result.observationSource in setOf(AppAuditObservationSource.LIVE, AppAuditObservationSource.FIXTURE)
        ) {
            entry to result
        } else {
            null
        }
    }
}

interface AppAuditProbeSource {
    val sourceId: String
    suspend fun plan(options: AppAuditOptions): List<AppAuditPlanEntry>
    suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation
}

/** A remote probe must checkpoint this boundary immediately before its existing client call. */
class AppAuditProbeContext(
    val limits: AppAuditLimits,
    private val markDispatch: suspend () -> Unit
) {
    suspend fun beforeDispatch() {
        ensureActive()
        markDispatch()
    }

    suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
    }
}

internal fun appAuditIdentifier(value: String): Boolean = value.length in 1..160 && value.all { it.isLetterOrDigit() || it in "_.:-" }
