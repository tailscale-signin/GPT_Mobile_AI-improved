package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkMode(val label: String) { QUICK("Quick"), FULL("Full"), DIAGNOSTIC("Capability diagnostics"), CPU_EXPERIMENT("CPU thread comparison"), PERFORMANCE("Performance diagnostics"), DELEGATION("Delegation"), AGENT("Legacy Agent") }

@Serializable
enum class BenchmarkOutcome { PASSED, FAILED, ERROR, TIMED_OUT, CANCELED, UNSUPPORTED, QUOTA_BLOCKED, POLICY_BLOCKED, PARTIAL, SKIPPED }

data class BenchmarkProgress(val profileName: String, val testName: String, val completed: Int, val total: Int)

@Serializable
data class DelegationBenchmarkEvent(
    val elapsedMs: Long,
    val type: String,
    val level: String = "INFO",
    val message: String
)

@Serializable
data class DelegationBenchmarkMetrics(
    val workerUid: String,
    val workerName: String,
    val workerProvider: String,
    val workerCalls: Int,
    val workerInputTokens: Long,
    val workerOutputTokens: Long,
    val primaryInputTokens: Long,
    val primaryOutputTokens: Long,
    val searches: Int,
    val pagesRead: Int,
    val rawEvidenceBytes: Int,
    val handoffCharacters: Int,
    val fixtureCalls: Int,
    val successfulFixtureCalls: Int,
    val primaryEstimated: Boolean = false,
    val workerModel: String = "",
    val workerConfigKey: String = "",
    val workerEstimated: Boolean = false,
    val workerDurationMs: Long = 0,
    val workerFirstTextMs: Long? = null,
    val workerDecodeTokensPerSecond: Double? = null,
    val outputCapViolations: Int = 0,
    val workerSpeedUsesReportedTokens: Boolean = false,
    val reviewerScore: Int? = null,
    val reviewerEvaluations: Int = 0,
    val reviewerCalls: Int = 0,
    val reviewerInputTokens: Long = 0,
    val reviewerOutputTokens: Long = 0,
    val reviewerEstimated: Boolean = false,
    val diagnosticEvents: List<DelegationBenchmarkEvent> = emptyList(),
    val delegateAttempts: Int = 0,
    val successfulRequests: Int = 0,
    val physicalRequests: Int = 0,
    val wastedMs: Long = 0,
    val repairWastedTokens: Long = 0,
    val repairWastedMs: Long = 0,
    val timeEfficiencyPercent: Double? = null
)

@Serializable
data class BenchmarkRoundMetrics(
    val round: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedOutput: Boolean,
    val finishReason: String?,
    val terminalReceived: Boolean,
    val inputTokenSource: String = "unknown",
    val outputTokenSource: String = "unknown",
    val inputTokenConfidence: String = "unknown",
    val outputTokenConfidence: String = "unknown",
    val reasoningTokens: Int? = null,
    val reasoningTokenSource: String = "unknown",
    val reasoningTokenConfidence: String = "unknown"
)

@Serializable
data class BenchmarkSample(
    val testId: String,
    val label: String,
    val category: String,
    val outcome: BenchmarkOutcome,
    val durationMs: Long = 0,
    val firstTextMs: Long? = null,
    val outputTokens: Int = 0,
    val estimatedTokens: Boolean = true,
    val outputCharacters: Int = 0,
    val chunks: Int = 0,
    val longestGapMs: Long? = null,
    val toolCalls: Int = 0,
    val successfulToolCalls: Int = 0,
    val preview: String = "",
    val error: String? = null,
    val lastTextMs: Long? = null,
    val nativeMetrics: dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics? = null,
    val inputTokens: Int = 0,
    val delegation: DelegationBenchmarkMetrics? = null,
    val reconnectAttempts: Int = 0,
    val referenceTokens: Int? = null,
    val referenceDecodeTokens: Int? = null,
    val tokenBasis: String = "legacy-provider-or-estimated",
    val warmup: Boolean = false,
    val workload: String = "legacy",
    val firstChunkCharacters: Int = 0,
    val failureCategory: String? = null,
    val runtimeStage: String? = null,
    val finishReason: String? = null,
    val completionKind: String = "unknown",
    val retryDurationMs: Long = 0,
    val effectiveSettingsConfirmed: Boolean = false,
    val requestedOutputTokens: Int? = null,
    val effectiveRequestOutputTokens: Int? = null,
    val requestedReasoningEffort: String? = null,
    val intelligenceAnswers: Map<String, Boolean> = emptyMap(),
    val intelligenceFormatCompliant: Boolean? = null,
    val intelligenceParseable: Boolean? = null,
    val intelligenceFixtureVersion: Int? = null,
    val intelligenceCorrection: String? = null,
    val timingEligible: Boolean? = null,
    val localSegments: List<dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics> = emptyList(),
    val rounds: List<BenchmarkRoundMetrics> = emptyList(),
    val inputImageCount: Int? = null,
    val inputModalities: Set<String> = emptySet(),
    val languageBackend: String? = null,
    val visionBackend: String? = null,
    val finalResponseFormat: String? = null,
    val pairedComparison: PairedDelegationComparison? = null,
    val attemptId: String? = null,
    val requestDispatched: Boolean? = null,
    val failure: BenchmarkFailure? = null,
    val canonicalAnswer: String? = null,
    val answerHash: String? = null,
    val answerLength: Int? = null,
    val answerTruncated: Boolean = false,
    val fixtureHash: String? = null,
    val validatorVersion: String = "legacy",
    val deliveryMode: String = "unknown",
    val throughputExclusion: String? = null,
    val loadRegime: String = "unknown",
    val cacheRegime: String = "unknown",
    val inputTokenSource: String = "unknown",
    val outputTokenSource: String = "unknown",
    val terminalReceived: Boolean? = null,
    val cleanupReceipt: String? = null,
    val physicalRequestIds: List<String> = emptyList(),
    val logicalRequestId: String? = null,
    val invocationIds: List<String> = emptyList(),
    val runtimeSnapshot: dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeState? = null,
    val actualRuntimeVersion: String? = null,
    val workerPssBytes: Long? = null,
    val inputTokenConfidence: String = "unknown",
    val outputTokenConfidence: String = "unknown",
    val phaseIntervals: List<BenchmarkPhaseInterval> = emptyList(),
    val phaseElapsedMs: Map<String, Long> = emptyMap(),
    val admissionWaitMs: Long? = null,
    val cleanupDurationMs: Long? = null,
    val nativeIdentity: BenchmarkNativeIdentity? = null,
    val phaseIntervalsTruncated: Boolean = false,
    val reasoningTokens: Int? = null,
    val reasoningTokenSource: String = "unknown",
    val reasoningTokenConfidence: String = "unknown",
    val validation: BenchmarkValidationResult? = null,
    val runtimeExecutions: List<dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt> = emptyList(),
    val runtimeCleanup: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt? = null,
    val outputParts: List<BenchmarkOutputPart> = emptyList(),
    val finalAnswer: String? = null,
    val repetitionDiagnostic: String? = null,
    val fixtureContentHash: String? = null,
    val repeatIndex: Int? = null,
    val requestAdmitted: Boolean? = null,
    val nativeMetricScope: String = "legacy_unspecified",
    val endToEndDurationMs: Long? = null,
    val generationDurationMs: Long? = null,
    val peakExecutionWorkerPssBytes: Long? = null,
    val peakExecutionClientPssKb: Long? = null,
    val workerPssScope: String = "legacy_unspecified",
    val residentMatch: BenchmarkResidentMatch? = null,
    val cacheConfigured: Boolean? = null,
    val intelligenceSemantics: BenchmarkIntelligenceEvidence? = null,
    val settingsReceipt: BenchmarkSettingsReceipt? = null,
    val toolAssessment: BenchmarkToolAssessment? = null,
    val performanceTrial: BenchmarkPerformanceTrial? = null,
    val nativeLoading: BenchmarkNativeLoadObservation? = null,
    val inspectionReceipt: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeInspectionReceipt? = null,
    val inspectionAttribution: BenchmarkInspectionAttribution? = null,
    val deadlineReceipt: BenchmarkDeadlineReceipt? = null
) {
    val runtimeExecution: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt? get() = runtimeExecutions.lastOrNull()
    val rawReferenceSpeed: Double? get() = firstTextMs?.let { first ->
        val last = lastTextMs ?: return@let null
        referenceDecodeTokens?.takeIf { it > 0 && chunks > 1 && last > first }
            ?.let { it * 1000.0 / (last - first) }?.takeIf { it.isFinite() && it > 0 }
    }
    val referenceSpeed: Double? get() = firstTextMs?.let { first ->
        val last = lastTextMs ?: return@let null
        referenceDecodeTokens?.takeIf { it > 0 && completed && chunks > 1 && last > first }
            ?.let { it * 1000.0 / (last - first) }?.takeIf { it.isFinite() && it > 0 }
    }
    val timingExclusionReason: String? get() = throughputExclusion ?: if (category == "speed" &&
        completed &&
        referenceTokens != null &&
        referenceTokens < BenchmarkSuiteRegistry.MIN_TIMING_REFERENCE_UNITS &&
        listOf("short", "medium", "long").any { testId.startsWith("speed-$it-") }
    ) {
        "Insufficient timing sample: $referenceTokens reference units produced; ${BenchmarkSuiteRegistry.MIN_TIMING_REFERENCE_UNITS} required."
    } else {
        null
    }

    val completed: Boolean get() = outcome == BenchmarkOutcome.PASSED || outcome == BenchmarkOutcome.FAILED

    // A one-chunk response has no observed decoding interval; avoid inventing a speed.
    val decodeTokensPerSecond: Double? get() = firstTextMs?.let { first ->
        val end = lastTextMs ?: durationMs
        (outputTokens * 1000.0 / (end - first)).takeIf {
            completed && outputTokens > 0 && chunks > 1 && end > first && it.isFinite()
        }
    }
}

@Serializable
data class BenchmarkResourceSample(
    val testId: String,
    val recordedAt: Long,
    val processPssKb: Long,
    val javaHeapBytes: Long,
    val batteryPercent: Int?,
    val thermalStatus: Int?,
    val workerPssBytes: Long? = null,
    val scope: String = "legacy_unspecified",
    val elapsedRealtimeMs: Long? = null
)

@Serializable
data class BenchmarkRun(
    val id: String,
    val profileUid: String,
    val profileName: String,
    val provider: String,
    val model: String,
    val configKey: String,
    val local: Boolean,
    val mode: BenchmarkMode,
    val startedAt: Long,
    val samples: List<BenchmarkSample> = emptyList(),
    val canceled: Boolean = false,
    val finished: Boolean = true,
    val suiteVersion: Int = 1,
    val device: String = "",
    val backend: String? = null,
    val accelerator: String? = null,
    val peakClientPssKb: Long? = null,
    val thermalBefore: Int? = null,
    val thermalAfter: Int? = null,
    val batteryBefore: Int? = null,
    val batteryAfter: Int? = null,
    val engineWasLoaded: Boolean = false,
    val stoppedReason: String? = null,
    val delegationSettings: dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings? = null,
    val measurementVersion: Int = 1,
    val scoringVersion: Int = 1,
    val plannedTrials: Int = 0,
    val appCommit: String = "",
    val runtimeVersion: String = "",
    val powerPolicy: String = "interactive-normal-v1",
    val networkPolicy: String = "client-default-v1",
    val fixtureContextTokens: Int? = null,
    val delegationPlan: BenchmarkMode? = null,
    val delegationSuiteVersion: Int = 0,
    val reviewerProvider: String? = null,
    val reviewerModel: String? = null,
    val billingOrigin: String? = null,
    val diagnosticMode: Boolean = false,
    val resources: List<BenchmarkResourceSample> = emptyList(),
    val batchId: String? = null,
    val queueOrdinal: Int? = null,
    val state: String = "LEGACY_IMPORTED",
    val endedAt: Long? = null,
    val cancellationOrigin: BenchmarkStopOrigin? = null,
    val loadRegime: String = "unknown",
    val cacheRegime: String = "unknown",
    val capabilityManifest: Set<String> = emptySet(),
    val requestedAccelerator: String? = null,
    val cleanupReceipt: String? = null,
    val fixtureManifestHash: String? = null,
    val runtimeSnapshot: dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeState? = null,
    val runtimeCleanup: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt? = null,
    val caseManifest: List<BenchmarkCase>? = null,
    val preflightDurationMs: Long? = null,
    val measurementPolicy: String? = null,
    val aggregatePolicy: String? = null,
    val peakExecutionWorkerPssBytes: Long? = null,
    val peakExecutionClientPssKb: Long? = null,
    val executionPolicy: BenchmarkExecutionPolicy? = null,
    val fixtureRotationSeed: Long? = null,
    val fixtureSetVersion: Int? = null,
    val deviceBuild: String? = null,
    val readiness: BenchmarkReadinessReceipt? = null,
    val deadlinePolicy: String? = null,
    val structuredOutputMode: BenchmarkStructuredOutputMode? = null,
    val structuredSchemaModes: Map<String, BenchmarkStructuredOutputMode> = emptyMap(),
    val verifiedStructuredCompatibilityKeys: Map<String, String> = emptyMap()
)

data class BenchmarkDimension(val label: String, val score: Double?, val weight: Int, val detail: String)

data class BenchmarkRating(
    val score: Int?,
    val dimensions: List<BenchmarkDimension>,
    val sampleCount: Int,
    val completed: Int,
    val medianFirstTextMs: Long?,
    val p95FirstTextMs: Long?,
    val medianDurationMs: Long?,
    val medianSpeed: Double?,
    val estimatedSpeed: Boolean,
    val toolSuccessPercent: Double?,
    val measuredWeight: Int
) {
    val grade: String get() = when {
        score == null -> "Not rated"
        score >= 90 -> "Excellent"
        score >= 75 -> "Strong"
        score >= 60 -> "Good"
        score >= 40 -> "Fair"
        else -> "Needs attention"
    }
}

fun benchmarkConfigKey(profile: PlatformV2, localEnvironment: String = "", executionPolicy: BenchmarkExecutionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1): String {
    val effective = benchmarkProfile(profile, allowTools = true, category = "speed", executionPolicy = executionPolicy)
    val values = listOf(
        if (executionPolicy == BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1) "benchmark-effective-v4" else "benchmark-profile-settings-v1", effective.compatibleType.name, effective.model, effective.apiUrl,
        effective.accelerator.orEmpty(), effective.temperature.toString(), effective.maxTokens.toString(),
        effective.timeout.toString(), effective.stream.toString(), effective.topP.toString(), effective.topK.toString(),
        effective.openRouterRouting.orEmpty(), effective.ollamaOptions.orEmpty(), effective.providerConnectionUid.orEmpty(),
        effective.systemPrompt.orEmpty(), profile.reasoning.toString(), profile.disableAllTools.toString(),
        profile.disableLocalTools.toString(), if (profile.compatibleType == ClientType.LITERT_LM) localEnvironment else ""
    )
    val identity = if (executionPolicy == BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1) values + kotlinx.serialization.json.Json.encodeToString(PlatformV2.serializer(), effective.copy(id = 0, uid = "", name = "", token = null, secretRef = null)) else values
    return MessageDigest.getInstance("SHA-256").digest(identity.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }
}

fun comparableRuns(history: List<BenchmarkRun>, profile: PlatformV2, mode: BenchmarkMode, localEnvironment: String = ""): List<BenchmarkRun> {
    val key = benchmarkConfigKey(profile, localEnvironment)
    return history.filter { it.profileUid == profile.uid && it.configKey == key && it.mode == mode && it.suiteVersion == dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSuiteRegistry.VERSION && it.finished && !it.canceled && it.stoppedReason == null }
        .sortedByDescending { it.startedAt }.take(5)
}

/** Versioned app score, not a general intelligence or hardware benchmark. Missing data is never a zero. */
fun benchmarkRating(runs: List<BenchmarkRun>, local: Boolean = runs.firstOrNull()?.local == true, speedAnchor: Double? = null, latencyAnchor: Long? = null): BenchmarkRating {
    val samples = runs.filter { it.finished && !it.canceled && it.stoppedReason == null }.flatMap { it.samples }
        .filter { it.outcome !in setOf(BenchmarkOutcome.CANCELED, BenchmarkOutcome.UNSUPPORTED, BenchmarkOutcome.QUOTA_BLOCKED, BenchmarkOutcome.POLICY_BLOCKED, BenchmarkOutcome.SKIPPED) }
    val completed = samples.filter { it.completed }
    val speedSamples = completed.filter { it.category == "speed" && it.outcome == BenchmarkOutcome.PASSED }
    val speeds = speedSamples.mapNotNull { it.decodeTokensPerSecond }.sorted()
    val first = completed.mapNotNull { it.firstTextMs }.sorted()
    val speedTarget = speedAnchor
    val latencyTargetMs = latencyAnchor?.toDouble()
    val speed = percentile(speeds, .5)
    val latency = percentile(first, .5)
    val success = samples.takeIf { it.isNotEmpty() }?.let { 100.0 * completed.size / it.size }
    val consistency = speeds.takeIf { it.size >= 3 && it.average() > 0 }?.let { data ->
        val average = data.average()
        val deviation = sqrt(data.map { (it - average) * (it - average) }.average())
        (100.0 * (1.0 - deviation / average)).coerceIn(0.0, 100.0)
    }
    fun passRate(category: String): Double? = samples.filter { it.category == category }.takeIf { it.isNotEmpty() }
        ?.let { items -> 100.0 * items.count { it.outcome == BenchmarkOutcome.PASSED } / items.size }
    val toolSamples = samples.filter { it.category == "tools" }
    val toolSuccess = toolSamples.takeIf { it.isNotEmpty() }?.let {
        // A model that never invokes the required fixture has failed the tool task.
        100.0 * it.count { sample -> sample.outcome == BenchmarkOutcome.PASSED } / it.size
    }
    val dimensions = listOf(
        BenchmarkDimension("Generation speed", speed?.let { DynamicScoreEngine.higher(it, speedTarget) }, 20, "Cohort-relative speed; legacy rates are diagnostic only"),
        BenchmarkDimension("First response", latency?.let { DynamicScoreEngine.lower(it.toDouble(), latencyTargetMs) }, 15, "Cohort-relative first usable text"),
        BenchmarkDimension("Request completion", success, 15, "Completed requests ÷ attempted requests; timeouts count as failures"),
        BenchmarkDimension("Consistency", consistency, 10, "Variation across at least three speed trials"),
        BenchmarkDimension("Task accuracy", passRate("task"), 15, "Exact instruction, arithmetic and multi-turn fixture checks"),
        BenchmarkDimension("Structured output", passRate("json"), 10, "Strict JSON and expected field values"),
        BenchmarkDimension("Tool success", toolSuccess, 15, "Correct fixture call, arguments and final answer")
    )
    val measured = dimensions.filter { it.score != null }
    val weight = measured.sumOf { it.weight }
    val score = if (samples.size >= 3 && weight == 100) minOf(measured.sumOf { it.score!! * it.weight } / 100, success ?: 0.0, passRate("task") ?: 0.0).roundToInt() else null
    return BenchmarkRating(
        score, dimensions, samples.size, completed.size, latency, percentile(first, .95).takeIf { first.size >= 20 },
        percentile(completed.map { it.durationMs }.sorted(), .5), speed,
        speedSamples.any { it.estimatedTokens && it.decodeTokensPerSecond != null }, toolSuccess, weight
    )
}

private fun <T> percentile(sorted: List<T>, fraction: Double): T? = sorted.takeIf { it.isNotEmpty() }
    ?.get((ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0))
