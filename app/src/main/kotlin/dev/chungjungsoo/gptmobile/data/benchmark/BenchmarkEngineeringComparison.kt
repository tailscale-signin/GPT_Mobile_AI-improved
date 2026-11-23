package dev.chungjungsoo.gptmobile.data.benchmark

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkEngineeringComparisonState { INCOMPLETE, INCOMPARABLE, INSUFFICIENT_EVIDENCE, INCONCLUSIVE, SUPERIOR_ON_SUITE }

@Serializable
data class BenchmarkEngineeringComparison(
    val cohortId: String,
    val lane: BenchmarkEngineeringLane,
    val state: BenchmarkEngineeringComparisonState,
    val label: String,
    val reason: String,
    val profileUids: List<String>,
    val profileNames: List<String>,
    val resolvedCounts: List<Int>,
    val eligibleCounts: List<Int>,
    val plannedTasks: Int,
    val matchedTasks: Int,
    val uniqueFamilies: Int,
    /** Positive means the first selected profile solved more matched tasks. */
    val deltaPercentagePoints: Double?,
    val lower95: Double?,
    val upper95: Double?,
    val practicalMarginPercentagePoints: Double,
    val winnerUid: String?,
    val excludedPairs: Map<String, Int>,
    val settingsVerified: Boolean,
    val firstAttemptCounts: List<Int> = emptyList(),
    val finalArtifactCounts: List<Int> = emptyList(),
    val falseCompletionCounts: List<Int> = emptyList(),
    val bestAttemptCounts: List<Int> = emptyList(),
    val intervalMethod: String = "bounded-family-hoeffding-95-with-exact-sign-gate-v2",
    val comparisonPolicy: String = "benchmark-engineering-comparison-v2",
    val aggregation: String = "equal family weight; variants and repeats averaged within each independent family",
    val uncertaintyAssumption: String = "Independent task-family means bounded from -1 to 1; repeat and variant counts do not increase independent family count. The finite-sample bound is conservative."
)

fun benchmarkEngineeringComparisons(history: List<BenchmarkRun>): List<BenchmarkEngineeringComparison> = history
    .filter { it.engineeringPlan != null }
    .groupBy { it.engineeringPlan!!.id }
    .values.map(::benchmarkEngineeringComparison)

/** A failure remains a failure; infrastructure never quietly becomes a zero capability score. */
internal fun benchmarkEngineeringFailureDomain(sample: BenchmarkSample): BenchmarkEngineeringFailureDomain = when {
    sample.engineeringVerification?.failureDomain != null -> requireNotNull(sample.engineeringVerification?.failureDomain)
    sample.outcome == BenchmarkOutcome.UNSUPPORTED -> BenchmarkEngineeringFailureDomain.UNSUPPORTED
    sample.outcome == BenchmarkOutcome.CANCELED || sample.failure?.owner == "user" -> BenchmarkEngineeringFailureDomain.USER
    sample.failureCategory?.startsWith("FIXTURE_") == true || sample.failure?.owner in setOf("grader", "harness", "storage") -> BenchmarkEngineeringFailureDomain.HARNESS
    benchmarkInfrastructureFailure(sample) || sample.failure?.owner in setOf("runtime", "provider", "network", "gateway", "configuration") -> BenchmarkEngineeringFailureDomain.INFRASTRUCTURE
    sample.outcome == BenchmarkOutcome.PASSED || sample.outcome == BenchmarkOutcome.FAILED -> BenchmarkEngineeringFailureDomain.MODEL
    sample.outcome == BenchmarkOutcome.TIMED_OUT && sample.knownExecuted() && sample.failure?.owner == "request" -> BenchmarkEngineeringFailureDomain.MODEL
    else -> BenchmarkEngineeringFailureDomain.UNKNOWN
}

internal fun benchmarkEngineeringTaskResult(plan: BenchmarkEngineeringPlan, test: BenchmarkCase, sample: BenchmarkSample): Boolean? {
    if (benchmarkEngineeringFailureDomain(sample) != BenchmarkEngineeringFailureDomain.MODEL) return null
    if (sample.evidenceScope != plan.lane.evidenceClass) return null
    return when (plan.lane) {
        BenchmarkEngineeringLane.TOOL_CONTRACT -> if (sample.knownExecuted() || sample.completed) {
            sample.outcome == BenchmarkOutcome.PASSED &&
                sample.toolAssessment?.executionSuccessful == true &&
                sample.toolAssessment.toolSelectionCorrect == true &&
                sample.toolAssessment.argumentsCorrect == true
        } else {
            null
        }
        BenchmarkEngineeringLane.WORKFLOW_SIMULATION -> if (sample.knownExecuted() || sample.completed) {
            sample.outcome == BenchmarkOutcome.PASSED && sample.toolAssessment?.workflowCompleted == true
        } else {
            null
        }
        BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB -> {
            if (sample.outcome == BenchmarkOutcome.PARTIAL || sample.engineeringCleanupPending()) return null
            val evidence = sample.engineeringVerification ?: return null
            when {
                !evidence.boundTo(plan, test) -> null
                evidence.taskComplete(plan, test) -> true
                evidence.failureDomain == BenchmarkEngineeringFailureDomain.MODEL && evidence.finalArtifactPassed == false -> false
                !evidence.independentChecksExecuted || !evidence.requiredChecksKnown -> null
                else -> false
            }
        }
    }
}

internal fun benchmarkEngineeringComparison(runs: List<BenchmarkRun>): BenchmarkEngineeringComparison {
    val plan = requireNotNull(runs.first().engineeringPlan)
    val profiles = plan.profileUids
    val names = profiles.map { uid -> runs.firstOrNull { it.profileUid == uid }?.profileName ?: uid }
    val manifest = runs.firstOrNull { it.caseManifest != null }?.caseManifest.orEmpty()
    val planned = manifest.size * plan.repeats
    val resolved = MutableList(profiles.size) { 0 }
    val eligible = MutableList(profiles.size) { 0 }
    val first = MutableList(profiles.size) { 0 }
    val final = MutableList(profiles.size) { 0 }
    val falseCompletions = MutableList(profiles.size) { 0 }
    val best = MutableList(profiles.size) { 0 }
    val exclusions = linkedMapOf<String, Int>()
    fun exclude(reason: String) {
        exclusions[reason] = (exclusions[reason] ?: 0) + 1
    }
    val validPlan = plan.matches(manifest, requireExternalReceipts = true) &&
        runs.all {
            it.engineeringPlan?.valid() == true &&
                it.engineeringPlan.identityDigest() == plan.identityDigest() &&
                it.configKey == plan.profileConfigKeys[it.profileUid] &&
                it.engineeringRepeatIndex in 0 until plan.repeats &&
                it.appCommit == plan.sourceCommit &&
                it.caseManifest == manifest &&
                it.executionPolicy == plan.executionPolicy
        }
    val buckets = runs.groupBy { it.profileUid to it.engineeringRepeatIndex }
    val duplicateTrial = buckets.any { it.value.size != 1 }
    val pairedDifferences = linkedMapOf<String, MutableList<Double>>()
    var matched = 0
    for (repeat in 0 until plan.repeats) {
        for (test in manifest) {
            val values = profiles.mapIndexed { index, uid ->
                val run = buckets[uid to repeat]?.singleOrNull()
                val sample = run?.samples?.singleOrNull { it.testId == test.id }
                val value = sample?.let { benchmarkEngineeringTaskResult(plan, test, it) }
                if (value != null) {
                    eligible[index]++
                    if (value) resolved[index]++
                    val receipt = requireNotNull(sample).engineeringVerification
                    if (receipt?.bestAttemptPassed == true) best[index]++
                    if (receipt?.firstAttemptPassed == true) first[index]++
                    if (receipt?.finalArtifactPassed == true) final[index]++
                    if (sample.outcome == BenchmarkOutcome.PASSED && !value) falseCompletions[index]++
                }
                Triple(value, sample, run)
            }
            if (profiles.size == 2 && values.all { it.first != null }) {
                matched++
                val difference = (if (values[0].first == true) 1.0 else 0.0) - (if (values[1].first == true) 1.0 else 0.0)
                pairedDifferences.getOrPut(test.engineeringFamilyId ?: test.id) { mutableListOf() }.add(difference)
            } else if (profiles.size == 2) {
                val reason = values.firstOrNull { it.first == null }?.let { (_, sample, run) ->
                    when {
                        run == null || sample == null -> "MISSING_PAIRED_OBSERVATION"
                        benchmarkEngineeringFailureDomain(sample) != BenchmarkEngineeringFailureDomain.MODEL -> benchmarkEngineeringFailureDomain(sample).name
                        else -> "VERIFICATION_EVIDENCE_MISSING"
                    }
                } ?: "MISSING_PAIRED_OBSERVATION"
                exclude(reason)
            }
        }
    }
    // Repeats and related variants are clustered, not counted as independent task families.
    val familyMeans = pairedDifferences.values.map { it.average() }
    val mean = familyMeans.takeIf { it.isNotEmpty() }?.average()
    val interval = pairedFamilyInterval(familyMeans)
    val settingsVerified = runs.flatMap { it.samples }.let { it.isNotEmpty() && it.all { sample -> sample.effectiveSettingsConfirmed } }
    val complete = profiles.size == 2 && buckets.size == 2 * plan.repeats && runs.all { it.finished && !it.canceled && it.stoppedReason == null } && matched == planned
    val positive = familyMeans.count { it > 0 }
    val negative = familyMeans.count { it < 0 }
    val signP = exactPairedSignProbability(positive, negative)
    val margin = plan.practicalMarginPercentagePoints / 100.0
    val winner = when {
        mean == null || interval == null || signP > .05 -> null
        interval.first > margin -> profiles.firstOrNull()
        interval.second < -margin -> profiles.getOrNull(1)
        else -> null
    }
    val state = when {
        !validPlan || duplicateTrial -> BenchmarkEngineeringComparisonState.INCOMPARABLE
        profiles.size != 2 || !complete -> BenchmarkEngineeringComparisonState.INCOMPLETE
        familyMeans.size < plan.minimumIndependentFamilies || plan.repeats < plan.minimumRepeats -> BenchmarkEngineeringComparisonState.INSUFFICIENT_EVIDENCE
        plan.executionPolicy == BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1 && !settingsVerified -> BenchmarkEngineeringComparisonState.INSUFFICIENT_EVIDENCE
        winner != null -> BenchmarkEngineeringComparisonState.SUPERIOR_ON_SUITE
        else -> BenchmarkEngineeringComparisonState.INCONCLUSIVE
    }
    val reason = when (state) {
        BenchmarkEngineeringComparisonState.INCOMPARABLE -> "Frozen task, profile, source, permission or trial identity differs. These runs cannot establish a winner."
        BenchmarkEngineeringComparisonState.INCOMPLETE -> "$matched/$planned matched task observations. Missing, unsupported and infrastructure results remain visible and cannot establish a winner."
        BenchmarkEngineeringComparisonState.INSUFFICIENT_EVIDENCE -> if (familyMeans.size < plan.minimumIndependentFamilies || plan.repeats < plan.minimumRepeats) {
            "${familyMeans.size}/${plan.minimumIndependentFamilies} independent families and ${plan.repeats}/${plan.minimumRepeats} repeats. More observations are required for a superiority claim."
        } else {
            "The deterministic comparison lacks confirmed effective settings. Observed task results are retained without a model winner."
        }
        BenchmarkEngineeringComparisonState.SUPERIOR_ON_SUITE -> "The conservative paired family interval clears the ${plan.practicalMarginPercentagePoints}% practical margin and the two-sided sign check. This conclusion applies to this lane, suite and frozen profile settings."
        BenchmarkEngineeringComparisonState.INCONCLUSIVE -> "The evidence does not establish a difference beyond the ${plan.practicalMarginPercentagePoints}% practical margin. Equal results do not prove universal equivalence."
    }
    return BenchmarkEngineeringComparison(
        plan.cohortId, plan.lane, state,
        if (state == BenchmarkEngineeringComparisonState.SUPERIOR_ON_SUITE) "Stronger on this suite" else "No established winner",
        reason, profiles, names, resolved, eligible, planned, matched, familyMeans.size,
        mean?.times(100), interval?.first?.times(100), interval?.second?.times(100),
        plan.practicalMarginPercentagePoints, winner.takeIf { state == BenchmarkEngineeringComparisonState.SUPERIOR_ON_SUITE },
        exclusions, settingsVerified, first, final, falseCompletions, best
    )
}

/**
 * Two-sided Hoeffding bound for independent family means in [-1, 1]. Unlike a
 * percentile bootstrap, unanimous observed wins cannot yield a zero-width interval.
 * Related variants and repeats remain inside a family rather than inflating n.
 */
internal fun pairedFamilyInterval(values: List<Double>): Pair<Double, Double>? {
    if (values.size < 2 || values.any { !it.isFinite() || abs(it) > 1 }) return null
    val mean = values.average()
    val radius = sqrt(2.0 * ln(2.0 / .05) / values.size)
    return (mean - radius).coerceAtLeast(-1.0) to (mean + radius).coerceAtMost(1.0)
}

/** Exact two-sided binomial sign gate also requires a consistent direction across independent families. */
internal fun exactPairedSignProbability(positive: Int, negative: Int): Double {
    val n = positive + negative
    if (n == 0) return 1.0
    val k = minOf(positive, negative)
    var probability = Math.pow(.5, n.toDouble())
    var sum = probability
    for (i in 1..k) {
        probability *= (n - i + 1).toDouble() / i
        sum += probability
    }
    return (2 * sum).coerceAtMost(1.0)
}
