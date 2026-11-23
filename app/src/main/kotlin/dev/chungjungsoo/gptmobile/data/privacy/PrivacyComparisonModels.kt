package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession

enum class PrivacyComparisonArm { OFF, ON }
enum class PrivacyComparisonStatus { RUNNING, COMPLETE, INCOMPLETE, CANCELLED }
enum class PrivacyComparisonLegStatus { COMPLETE, PARTIAL, FAILED, BLOCKED, TIMED_OUT, CANCELLED }
enum class PrivacyComparisonFailure {
    PROFILE_CHANGED,
    POLICY_CHANGED,
    UNSUPPORTED_PROFILE,
    INITIALIZATION_FAILED,
    PROVIDER_FAILED,
    INCOMPLETE_STREAM,
    UNEXPECTED_TOOL,
    OUTPUT_LIMIT,
    TIMEOUT,
    OVERALL_TIMEOUT,
    CANCELLED,
    WARMUP_FAILED
}

class PrivacyComparisonBlockedException(val code: PrivacyComparisonFailure) : IllegalStateException(code.name)

/** Missing observations stay null. Receipt offsets refer only to the compiled fictional prompt. */
data class PrivacyComparisonMeasurements(
    val inputReceipt: PrivacyProtectionReceipt? = null,
    val maskingNanos: Long? = null,
    val restorationNanos: Long? = null,
    val firstUpstreamAnswerMillis: Long? = null,
    val firstRestoredAnswerMillis: Long? = null,
    val wireOutcome: String? = null,
    val routeKind: String? = null,
    val comparisonLimitation: String? = null
)

data class PrivacyComparisonSession(
    val session: AgentProviderSession,
    val policy: PrivacyModeState? = null,
    val measurements: () -> PrivacyComparisonMeasurements = { PrivacyComparisonMeasurements() },
    val authorityRevision: String? = null
)

data class PrivacyComparisonCriterion(val id: String, val label: String, val passed: Boolean)

data class PrivacyComparisonDetection(
    val expectedValues: Int,
    val protectedValues: Int,
    val publicPhrases: Int,
    val preservedPublicPhrases: Int
) {
    val missedValues: Int get() = expectedValues - protectedValues
    val alteredPublicPhrases: Int get() = publicPhrases - preservedPublicPhrases
}

/** Nearest-rank sample percentiles; absent measurements never become zero. */
data class PrivacyComparisonPercentiles(val sampleCount: Int, val p50: Long?, val p95: Long?) {
    companion object {
        internal fun from(values: List<Long?>): PrivacyComparisonPercentiles {
            val sorted = values.filterNotNull().filter { it >= 0 }.sorted()
            fun rank(percent: Int): Long? = if (sorted.isEmpty()) null else sorted[(sorted.size * percent + 99) / 100 - 1]
            return PrivacyComparisonPercentiles(sorted.size, rank(50), rank(95))
        }
    }
}

data class PrivacyComparisonArmTiming(
    val arm: PrivacyComparisonArm,
    val firstTextMs: PrivacyComparisonPercentiles,
    val completionMs: PrivacyComparisonPercentiles,
    val maskingNanos: PrivacyComparisonPercentiles,
    val restorationNanos: PrivacyComparisonPercentiles
)

/** Only observed matching pairs; different runtimes or route combinations stay separate. */
data class PrivacyComparisonTimingGroup(
    val runtime: String,
    val outputTokens: Int,
    val offRoute: String,
    val onRoute: String,
    val pairedCases: Int,
    val arms: List<PrivacyComparisonArmTiming>
)

data class PrivacyComparisonLeg(
    val caseId: String,
    val arm: PrivacyComparisonArm,
    val status: PrivacyComparisonLegStatus,
    val answer: String,
    val criteria: List<PrivacyComparisonCriterion> = emptyList(),
    val firstTextMs: Long? = null,
    val completionMs: Long? = null,
    val elapsedMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val measurements: PrivacyComparisonMeasurements = PrivacyComparisonMeasurements(),
    val detection: PrivacyComparisonDetection? = null,
    val policy: PrivacyModeState? = null,
    val actualRuntime: String? = null,
    val effectiveOutputTokens: Int? = null,
    val configurationChanged: Boolean = false,
    val authorityRevision: String? = null,
    val failure: PrivacyComparisonFailure? = null
) {
    val taskCriteriaPassed: Boolean get() = status == PrivacyComparisonLegStatus.COMPLETE && criteria.isNotEmpty() && criteria.all { it.passed }
}

data class PrivacyComparisonResult(
    val runId: String,
    val status: PrivacyComparisonStatus = PrivacyComparisonStatus.RUNNING,
    val legs: List<PrivacyComparisonLeg> = emptyList(),
    val warmupCompleted: Int = 0,
    val activeCaseId: String? = null,
    val activeArm: PrivacyComparisonArm? = null,
    val failure: PrivacyComparisonFailure? = null
) {
    val scoredCompleted: Int get() = legs.count { it.status == PrivacyComparisonLegStatus.COMPLETE }
    val scoredTotal: Int get() = PrivacyComparisonCase.scored.size * PrivacyComparisonArm.entries.size
    val warmupTotal: Int get() = PrivacyComparisonArm.entries.size

    fun legsFor(case: PrivacyComparisonCase): List<PrivacyComparisonLeg> = legs.filter { it.caseId == case.id }

    fun timingSummaries(): List<PrivacyComparisonTimingGroup> {
        data class Key(val runtime: String, val outputTokens: Int, val offRoute: String, val onRoute: String)
        val pairs = PrivacyComparisonCase.scored.mapNotNull { case ->
            if (pairStatus(case) != "OBSERVED_CONFIGURATION_MATCHES") return@mapNotNull null
            val pair = legsFor(case)
            val off = pair.single { it.arm == PrivacyComparisonArm.OFF }
            val on = pair.single { it.arm == PrivacyComparisonArm.ON }
            val key = Key(
                requireNotNull(off.actualRuntime),
                requireNotNull(off.effectiveOutputTokens),
                off.measurements.routeKind ?: return@mapNotNull null,
                on.measurements.routeKind ?: return@mapNotNull null
            )
            key to (off to on)
        }
        return pairs.groupBy({ it.first }, { it.second }).map { (key, group) ->
            // Both arms use the same sample population for each answer-time metric.
            val firstTextPairs = group.filter { (off, on) -> off.firstTextMs?.let { it >= 0 } == true && on.firstTextMs?.let { it >= 0 } == true }
            val completionPairs = group.filter { (off, on) -> off.completionMs?.let { it >= 0 } == true && on.completionMs?.let { it >= 0 } == true }
            PrivacyComparisonTimingGroup(
                key.runtime,
                key.outputTokens,
                key.offRoute,
                key.onRoute,
                group.size,
                PrivacyComparisonArm.entries.map { arm ->
                    fun Pair<PrivacyComparisonLeg, PrivacyComparisonLeg>.leg() = if (arm == PrivacyComparisonArm.OFF) first else second
                    PrivacyComparisonArmTiming(
                        arm,
                        PrivacyComparisonPercentiles.from(firstTextPairs.map { it.leg().firstTextMs }),
                        PrivacyComparisonPercentiles.from(completionPairs.map { it.leg().completionMs }),
                        PrivacyComparisonPercentiles.from(group.map { it.leg().measurements.maskingNanos }),
                        PrivacyComparisonPercentiles.from(group.map { it.leg().measurements.restorationNanos })
                    )
                }
            )
        }
    }

    /** Same requested profile is a binding guarantee; actual backend evidence may be absent. */
    fun pairStatus(case: PrivacyComparisonCase): String {
        val pair = legsFor(case)
        if (pair.size != 2 || pair.map { it.arm }.toSet().size != 2 || pair.any { it.status != PrivacyComparisonLegStatus.COMPLETE }) return "INCOMPLETE"
        if (pair.any { it.measurements.comparisonLimitation != null }) return "CONFIGURATION_NOT_COMPARABLE"
        val runtimes = pair.mapNotNull { it.actualRuntime }
        val ceilings = pair.mapNotNull { it.effectiveOutputTokens }
        if (pair.any { it.configurationChanged } || runtimes.distinct().size > 1 || ceilings.distinct().size > 1) return "OBSERVED_CONFIGURATION_DIFFERS"
        return if (runtimes.size == 2 && ceilings.size == 2) "OBSERVED_CONFIGURATION_MATCHES" else "REQUESTED_CONFIGURATION_MATCHES_OBSERVATION_INCOMPLETE"
    }
}
