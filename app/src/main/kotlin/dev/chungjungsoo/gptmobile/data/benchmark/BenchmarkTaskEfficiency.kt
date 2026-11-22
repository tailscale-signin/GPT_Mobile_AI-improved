package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.pricing.ManualPrice
import dev.chungjungsoo.gptmobile.data.pricing.ModelPrice
import dev.chungjungsoo.gptmobile.data.pricing.PriceFreshness
import dev.chungjungsoo.gptmobile.data.pricing.billingProvider
import dev.chungjungsoo.gptmobile.data.pricing.decimalRate
import dev.chungjungsoo.gptmobile.data.pricing.priceContextKey
import dev.chungjungsoo.gptmobile.data.pricing.resolveProfilePrice
import java.math.RoundingMode
import java.util.Currency
import kotlinx.serialization.Serializable

/** Prices are frozen with the run; later settings changes must not rewrite old comparisons. */
@Serializable
data class BenchmarkPricingSnapshot(
    val profileUid: String,
    val provider: String,
    val model: String,
    val currency: String,
    val capturedAt: Long,
    /** Decode older allowance snapshots without promoting their UID-only identity to a route-bound rate. */
    val price: dev.chungjungsoo.gptmobile.data.accounting.ModelPrice? = null,
    val quote: ModelPrice? = null,
    val configKey: String? = null,
    val contextDigest: String? = null,
    val billingOrigin: String? = null,
    val requestModel: String? = null,
    val sourceKind: String? = null,
    val sourceDigest: String? = null
)

internal fun benchmarkPricingSnapshot(
    profile: PlatformV2,
    overrides: List<ManualPrice>,
    entries: List<ModelPrice>,
    now: Long,
    configKey: String,
    executionPolicy: BenchmarkExecutionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1
): BenchmarkPricingSnapshot {
    // Benchmarks use interactive requests. Batch rates and batch-only model aliases
    // cannot price that route. Sampling changes leave the saved provider selection intact.
    val request = profile.copy(
        batchMode = false,
        model = if (profile.compatibleType == ClientType.OPENROUTER && executionPolicy != BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1) profile.model.removeSuffix(":batch") else profile.model
    )
    val resolved = resolveProfilePrice(request, overrides, entries, now)?.takeIf { it.sourceUrl.isNotBlank() && benchmarkCurrency(it.currency) != null }
    return BenchmarkPricingSnapshot(
        profile.uid, profile.compatibleType.name, profile.model, resolved?.currency.orEmpty(), now,
        quote = resolved?.copy(sourceUrl = "", extras = resolved.extras.toMap()),
        configKey = configKey,
        contextDigest = benchmarkHash(priceContextKey(request)),
        billingOrigin = billingProvider(request) ?: "CUSTOM",
        requestModel = request.model,
        sourceKind = resolved?.source?.name,
        sourceDigest = resolved?.sourceUrl?.let(::benchmarkHash)
    )
}

private fun benchmarkCurrency(value: String): String? = value.takeIf { it.matches(Regex("[A-Z]{3}")) && runCatching { Currency.getInstance(it) }.isSuccess }

/** A per-run fixture result, never a claim about real-world tasks, invoices or device energy. */
@Serializable
data class BenchmarkTaskEfficiency(
    val recordedTasks: Int,
    val successfulTasks: Int,
    val unsuccessfulTasks: Int,
    val excludedWarmups: Int,
    val excludedUnattempted: Int,
    val elapsedMs: Long?,
    val elapsedMsPerSuccessfulTask: Double?,
    val retryMs: Long?,
    val apiCostEstimateMicros: Long?,
    val apiCostEstimateMicrosPerSuccessfulTask: Double?,
    val currency: String?,
    val pricedTasks: Int,
    val missingTimingTasks: Int,
    val costExclusions: Map<String, Int>,
    val provisional: Boolean,
    val policyVersion: String = "benchmark-task-efficiency-v1",
    val scope: String = "recorded_non_warmup_fixtures_including_failed_attempts"
)

fun benchmarkTaskEfficiency(run: BenchmarkRun): BenchmarkTaskEfficiency {
    val measured = run.samples.filterNot { it.warmup }
    val samples = measured.filterNot {
        it.outcome in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.UNSUPPORTED) &&
            it.requestDispatched == false &&
            it.requestAdmitted != true &&
            !it.hasTaskDispatchEvidence()
    }
    val passed = samples.count { it.outcome == BenchmarkOutcome.PASSED }
    val durations = samples.map { (it.endToEndDurationMs ?: it.durationMs.takeIf { duration -> duration > 0 })?.takeIf { duration -> duration >= 0 } }
    val elapsed = if (samples.isEmpty()) null else exactNonnegativeSum(durations)
    val retries = if (samples.isEmpty()) null else exactNonnegativeSum(samples.map { it.retryDurationMs.takeIf { duration -> duration >= 0 } })
    val costs = samples.map { benchmarkSampleApiCost(run, it) }
    val cost = if (samples.isEmpty()) null else exactNonnegativeSum(costs.map { it.first })
    return BenchmarkTaskEfficiency(
        recordedTasks = samples.size,
        successfulTasks = passed,
        unsuccessfulTasks = samples.size - passed,
        excludedWarmups = run.samples.count { it.warmup },
        excludedUnattempted = measured.size - samples.size,
        elapsedMs = elapsed,
        elapsedMsPerSuccessfulTask = elapsed?.takeIf { passed > 0 }?.toDouble()?.div(passed),
        retryMs = retries,
        apiCostEstimateMicros = cost,
        apiCostEstimateMicrosPerSuccessfulTask = cost?.takeIf { passed > 0 }?.toDouble()?.div(passed),
        currency = run.pricingSnapshot?.currency?.let(::benchmarkCurrency),
        pricedTasks = costs.count { it.first != null },
        missingTimingTasks = durations.count { it == null },
        costExclusions = costs.mapNotNull { it.second }.groupingBy { it }.eachCount(),
        provisional = !run.finished || run.canceled || run.stoppedReason != null
    )
}

private fun benchmarkSampleApiCost(run: BenchmarkRun, sample: BenchmarkSample): Pair<Long?, String?> {
    // Delegation includes several independently priced models. Do not apply the primary price
    // to their aggregate usage or hide unreported contributor usage in a partial cost total.
    if (sample.delegation != null || run.mode == BenchmarkMode.DELEGATION) return null to "participant_usage_not_fully_attributed"
    if (sample.requestDispatched == false && !sample.hasTaskDispatchEvidence()) return 0L to null
    if (run.local && run.provider == "LITERT_LM") return 0L to null
    val snapshot = run.pricingSnapshot ?: return null to "price_snapshot_missing"
    if (snapshot.profileUid != run.profileUid || snapshot.provider != run.provider || snapshot.model != run.model) return null to "price_identity_mismatch"
    if (snapshot.configKey == null || snapshot.contextDigest?.matches(Regex("[0-9a-f]{64}")) != true) return null to "price_context_missing"
    if (snapshot.configKey != run.configKey || snapshot.capturedAt != run.startedAt) return null to "price_identity_mismatch"
    val price = snapshot.quote ?: return null to "context_bound_price_missing"
    if (benchmarkCurrency(snapshot.currency) == null || price.currency != snapshot.currency) return null to "currency_unknown"
    if (price.model != snapshot.requestModel || price.provider != snapshot.billingOrigin || !price.valid() || price.freshness(snapshot.capturedAt) == PriceFreshness.EXPIRED || snapshot.sourceKind != price.source.name || snapshot.sourceDigest?.matches(Regex("[0-9a-f]{64}")) != true) return null to "price_evidence_invalid"
    if (price.routingReference || price.conditionalPricing) return null to "route_or_conditional_price_unconfirmed"
    if (sample.reconnectAttempts > 0) return null to "reconnect_usage_incomplete"
    if (sample.estimatedTokens || sample.inputTokenConfidence != "final_reported" || sample.outputTokenConfidence != "final_reported" || sample.inputTokens < 0 || sample.outputTokens < 0) return null to "final_usage_missing"
    // Currency per million tokens × tokens is already micro-currency. Round only
    // once after both token lanes; preserve decimal precision until the final bound.
    val cost = runCatching {
        requireNotNull(decimalRate(price.inputPerMillion)).multiply(sample.inputTokens.toBigDecimal())
            .add(requireNotNull(decimalRate(price.outputPerMillion)).multiply(sample.outputTokens.toBigDecimal()))
            .setScale(0, RoundingMode.CEILING).longValueExact()
    }.getOrNull()?.takeIf { it >= 0 }
    return if (cost != null) cost to null else null to "cost_out_of_range"
}

private fun BenchmarkSample.hasTaskDispatchEvidence(): Boolean = knownExecuted() ||
    requestDispatched == true ||
    physicalRequestIds.isNotEmpty() ||
    providerHttpStatus != null ||
    (delegation?.physicalRequests ?: 0) > 0 ||
    inputTokens > 0 &&
    inputTokenConfidence == "final_reported" ||
    outputTokens > 0 &&
    outputTokenConfidence == "final_reported"

private fun exactNonnegativeSum(values: List<Long?>): Long? {
    var total = 0L
    for (value in values) {
        if (value == null || value < 0 || Long.MAX_VALUE - total < value) return null
        total += value
    }
    return total
}
