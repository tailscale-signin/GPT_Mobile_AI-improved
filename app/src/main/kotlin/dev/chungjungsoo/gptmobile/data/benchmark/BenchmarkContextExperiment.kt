package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

/** Optional experiment identity; legacy model comparisons retain their original identity. */
@Serializable
data class BenchmarkContextExperiment(
    val variants: List<String> = listOf("CURRENT", "REFERENCE", "TASK", "SEMANTIC"),
    val budgetBytes: Int = 12_000,
    val protocol: String = "public-fixture-context-v1"
) {
    fun valid(): Boolean = protocol == "public-fixture-context-v1" &&
        variants.size in 2..4 &&
        variants.first() == "CURRENT" &&
        variants.distinct() == variants &&
        variants.all { it in VARIANTS } &&
        budgetBytes in 4096..24_000

    companion object {
        val VARIANTS = listOf("CURRENT", "REFERENCE", "TASK", "SEMANTIC")
    }
}

internal fun benchmarkEngineeringQueueOrder(plan: BenchmarkEngineeringPlan): List<Triple<String, Int, String?>> =
    (0 until plan.repeats).flatMap { repeat ->
        val reversed = plan.ordering == BenchmarkEngineeringOrdering.COUNTERBALANCED && repeat % 2 == 1
        val profiles = if (reversed) plan.profileUids.reversed() else plan.profileUids
        val variants: List<String?> = plan.contextExperiment?.variants ?: listOf(null)
        val arms = if (reversed) variants.reversed() else variants
        profiles.flatMap { uid -> arms.map { Triple(uid, repeat, it) } }
    }

@Serializable
data class BenchmarkContextSourceIdentity(
    val sourceDigest: String,
    val referenceDigest: String,
    val declaredSourceCount: Int,
    val manifestFileCount: Int
) {
    fun valid(): Boolean = listOf(sourceDigest, referenceDigest).all { it.matches(Regex("[0-9a-f]{64}")) } &&
        declaredSourceCount in 1..128 &&
        manifestFileCount in declaredSourceCount..130
}

/** Contains no source, prompt, filenames, hidden test inputs or expected answers. */
@Serializable
data class BenchmarkContextReceipt(
    val variant: String,
    val manifestDigest: String,
    val sourceSha: String,
    val sourceIdentityKind: String = "FROZEN_FIXTURE_MANIFEST",
    val referenceDigest: String? = null,
    val indexDigest: String? = null,
    val indexIdentityScope: String = "FROZEN_PUBLIC_FILE_MANIFEST_NO_PERSISTENT_SYMBOL_INDEX",
    val promptDigest: String,
    val selectedPathCount: Int,
    val selectedSourceCount: Int,
    val declaredSourceCount: Int,
    val selectedTestCount: Int,
    val contextBytes: Int,
    val estimatedContextTokens: Int,
    val tokenMeasurement: String = "UTF8_BYTES_DIVIDED_BY_THREE_ESTIMATE",
    val coldPreparationMs: Long,
    val warmPreparationMs: Long,
    val coldSourceReads: Int,
    val warmSourceReads: Int,
    val warmCacheHits: Int,
    val repeatedSourceReads: Int,
    val retrievalMode: String,
    val revisionVerified: Boolean,
    val dispatched: Boolean = false,
    val firstVerifiedPatchMs: Long? = null,
    val relevantFileRecall: Double? = null,
    val testRecall: Double? = null,
    val recallScope: String = "PUBLIC_SOURCE_COVERAGE_ONLY_NO_INDEPENDENT_RELEVANCE_LABELS",
    val preparationScope: String = "LOCAL_SELECTOR_COLD_AND_WARM_CACHE_PROBES_NOT_MODEL_OR_NETWORK_COLD_START"
) {
    fun boundTo(plan: BenchmarkEngineeringPlan, taskId: String, arm: String): Boolean =
        variant == arm &&
            variant in plan.contextExperiment?.variants.orEmpty() &&
            manifestDigest == plan.contextManifestDigests[taskId] &&
            sourceIdentityKind == "FROZEN_FIXTURE_MANIFEST" &&
            sourceSha == plan.contextSourceIdentities[taskId]?.sourceDigest &&
            indexDigest == sourceSha &&
            referenceDigest == plan.contextSourceIdentities[taskId]?.referenceDigest &&
            declaredSourceCount == plan.contextSourceIdentities[taskId]?.declaredSourceCount &&
            promptDigest.matches(Regex("[0-9a-f]{64}")) &&
            revisionVerified &&
            dispatched
}

@Serializable
data class BenchmarkContextArmMetrics(
    val variant: String,
    val recordedTasks: Int,
    val verifiedTasks: Int,
    val completedTasks: Int,
    val completionEvidenceContradictions: Int,
    val medianContextBytes: Double?,
    val medianColdPreparationMs: Double?,
    val medianWarmPreparationMs: Double?,
    val sourceReads: Int,
    val warmCacheHits: Int,
    val repeatedSourceReads: Int,
    val declaredSourceCoverage: Double?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val elapsedMs: Long?,
    val estimatedApiCostMicros: Long?,
    val currency: String?,
    val retrievalModes: List<String>,
    val staleOrUnboundReceipts: Int,
    val medianTimeToFirstVerifiedPatchMs: Double? = null,
    val falseCompletionCount: Int? = null,
    val completionClaimAssessment: String = "NOT_MEASURED_STRUCTURED_PATCH_TASK",
    val relevantFileRecall: Double? = null,
    val testRecall: Double? = null
)

@Serializable
data class BenchmarkContextComparison(
    val experimentId: String,
    val cohortId: String,
    val profileUid: String,
    val profileName: String,
    val variant: String,
    val baseline: BenchmarkContextArmMetrics,
    val candidate: BenchmarkContextArmMetrics,
    val matchedPairs: Int,
    val plannedPairs: Int,
    val independentFamilies: Int,
    val differencePercentagePoints: Double?,
    val lower95: Double?,
    val upper95: Double?,
    val state: String,
    val reason: String,
    val policy: String = "same-profile-context-paired-v1",
    val scope: String = "Frozen public fixture context, unchanged profile/source/tasks/budget; source coverage is not independently labelled relevance"
)

fun benchmarkContextComparisons(history: List<BenchmarkRun>): List<BenchmarkContextComparison> = history
    .filter { it.engineeringPlan?.contextExperiment != null }.groupBy { it.engineeringPlan!!.id }.values.flatMap { runs ->
        val plan = requireNotNull(runs.first().engineeringPlan)
        val experiment = requireNotNull(plan.contextExperiment)
        val manifest = runs.firstOrNull { it.caseManifest != null }?.caseManifest.orEmpty()
        val uid = plan.profileUids.singleOrNull().orEmpty()
        val valid = plan.valid() &&
            uid.isNotEmpty() &&
            plan.matches(manifest, requireExternalReceipts = true) &&
            plan.contextManifestDigests.keys == manifest.map { it.engineeringTaskId }.toSet() &&
            runs.all {
                it.engineeringPlan?.valid() == true &&
                    it.engineeringPlan.identityDigest() == plan.identityDigest() &&
                    it.profileUid == uid &&
                    it.configKey == plan.profileConfigKeys[uid] &&
                    it.appCommit == plan.sourceCommit &&
                    it.caseManifest == manifest &&
                    it.executionPolicy == plan.executionPolicy &&
                    it.engineeringContextVariant in experiment.variants &&
                    it.engineeringRepeatIndex in 0 until plan.repeats
            }
        val buckets = runs.groupBy { it.engineeringContextVariant to it.engineeringRepeatIndex }
        val duplicate = buckets.values.any { it.size != 1 }
        fun sample(variant: String, repeat: Int, test: BenchmarkCase): BenchmarkSample? =
            buckets[variant to repeat]?.singleOrNull()?.samples?.singleOrNull { it.testId == test.id }
        fun result(variant: String, repeat: Int, test: BenchmarkCase): Boolean? {
            // Retain individual facts below, but mismatched or ambiguous trials
            // cannot contribute a paired delta merely because their receipts match.
            if (!valid || duplicate) return null
            val sample = sample(variant, repeat, test) ?: return null
            if (sample.contextReceipt?.boundTo(plan, test.engineeringTaskId.orEmpty(), variant) != true) return null
            return benchmarkEngineeringTaskResult(plan, test, sample)
        }
        val metrics = experiment.variants.associateWith { variant ->
            benchmarkContextArmMetrics(runs.filter { it.engineeringContextVariant == variant }, manifest, plan, variant)
        }
        experiment.variants.drop(1).map { variant ->
            val differences = linkedMapOf<String, MutableList<Double>>()
            var pairs = 0
            for (repeat in 0 until plan.repeats) {
                for (test in manifest) {
                    val a = result("CURRENT", repeat, test)
                    val b = result(variant, repeat, test)
                    if (a != null && b != null) {
                        pairs++
                        differences.getOrPut(test.engineeringFamilyId ?: test.id) { mutableListOf() }
                            .add((if (b) 1.0 else 0.0) - (if (a) 1.0 else 0.0))
                    }
                }
            }
            val means = differences.values.map { it.average() }
            val interval = pairedFamilyInterval(means)
            val expected = manifest.size * plan.repeats
            val sufficient = means.size >= plan.minimumIndependentFamilies && plan.repeats >= plan.minimumRepeats
            val complete = buckets.size == experiment.variants.size * plan.repeats && runs.all { it.finished && !it.canceled && it.stoppedReason == null }
            val settingsVerified = runs.flatMap { it.samples }.let { it.isNotEmpty() && it.all { sample -> sample.effectiveSettingsConfirmed } }
            val state = when {
                !valid || duplicate -> "IDENTITY_MISMATCH"
                pairs != expected || !complete -> "INCOMPLETE"
                variant == "SEMANTIC" && metrics.getValue(variant).retrievalModes.any { it != "LOCAL_SEMANTIC_AND_LEXICAL" } -> "SEMANTIC_FALLBACK_RECORDED"
                !sufficient -> "INSUFFICIENT_EVIDENCE"
                plan.executionPolicy == BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1 && !settingsVerified -> "SETTINGS_UNCONFIRMED"
                else -> "DESCRIPTIVE_PAIRED_RESULT"
            }
            BenchmarkContextComparison(
                plan.id, plan.cohortId, uid, runs.first().profileName, variant,
                metrics.getValue("CURRENT"), metrics.getValue(variant), pairs, expected, means.size,
                means.takeIf { it.isNotEmpty() }?.average()?.times(100), interval?.first?.times(100), interval?.second?.times(100), state,
                when (state) {
                    "IDENTITY_MISMATCH" -> "Frozen source, model, context arm or trial identity differs; these observations cannot establish a comparison."
                    "INCOMPLETE" -> "Only $pairs/$expected paired tasks have bound context and independently evaluable outcomes."
                    "INSUFFICIENT_EVIDENCE" -> "${means.size}/${plan.minimumIndependentFamilies} independent families; ${plan.repeats}/${plan.minimumRepeats} repeats. No superiority conclusion."
                    "SEMANTIC_FALLBACK_RECORDED" -> "Some selected semantic trials used lexical fallback. These observations do not establish a local semantic ranking advantage."
                    "SETTINGS_UNCONFIRMED" -> "Requested model settings match, but execution did not confirm all effective settings."
                    else -> "Descriptive paired family evidence for this frozen suite. Compare measured effort; this experiment does not assign a capability or superiority score."
                }
            )
        }
    }

private fun benchmarkContextArmMetrics(runs: List<BenchmarkRun>, cases: List<BenchmarkCase>, plan: BenchmarkEngineeringPlan, variant: String): BenchmarkContextArmMetrics {
    val samples = runs.flatMap { run -> run.samples.map { run to it } }
    val tests = cases.associateBy { it.id }
    val bound = samples.filter { (_, sample) -> sample.contextReceipt?.boundTo(plan, tests[sample.testId]?.engineeringTaskId.orEmpty(), variant) == true }
    val evaluated = bound.mapNotNull { (_, sample) -> tests[sample.testId]?.let { benchmarkEngineeringTaskResult(plan, it, sample)?.let { value -> sample to value } } }
    val receipts = bound.map { requireNotNull(it.second.contextReceipt) }
    fun median(values: List<Double>) = values.takeIf { it.isNotEmpty() }?.let(DynamicScoreEngine::median)
    fun total(values: List<Long?>): Long? {
        if (values.isEmpty() || values.any { it == null || it < 0 }) return null
        var sum = 0L
        for (value in values) {
            val next = requireNotNull(value)
            if (Long.MAX_VALUE - sum < next) return null
            sum += next
        }
        return sum
    }
    val effort = runs.map(::benchmarkTaskEfficiency)
    val currencies = effort.mapNotNull { it.currency }.distinct()
    return BenchmarkContextArmMetrics(
        variant, samples.size, evaluated.size, evaluated.count { it.second }, evaluated.count { it.first.outcome == BenchmarkOutcome.PASSED && !it.second },
        median(receipts.map { it.contextBytes.toDouble() }), median(receipts.map { it.coldPreparationMs.toDouble() }), median(receipts.map { it.warmPreparationMs.toDouble() }),
        receipts.sumOf { it.coldSourceReads + it.warmSourceReads }, receipts.sumOf { it.warmCacheHits }, receipts.sumOf { it.repeatedSourceReads },
        receipts.filter { it.declaredSourceCount > 0 }.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { it.selectedSourceCount }.toDouble() / list.sumOf { it.declaredSourceCount } },
        total(effort.map { it.allInInputTokens }), total(effort.map { it.allInOutputTokens }), total(effort.map { it.allInElapsedMs }),
        total(effort.map { it.allInApiCostEstimateMicros }).takeIf { currencies.size == 1 }, currencies.singleOrNull(), receipts.map { it.retrievalMode }.distinct(), samples.size - bound.size,
        medianTimeToFirstVerifiedPatchMs = median(evaluated.filter { it.second }.mapNotNull { it.first.contextReceipt?.firstVerifiedPatchMs?.toDouble() })
    )
}
