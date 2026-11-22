package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.pricing.ManualPrice
import dev.chungjungsoo.gptmobile.data.pricing.ModelPrice
import dev.chungjungsoo.gptmobile.data.pricing.PriceSource
import dev.chungjungsoo.gptmobile.data.pricing.priceContextKey
import dev.chungjungsoo.gptmobile.data.pricing.reviewTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkTaskEfficiencyTest {
    private val profile = PlatformV2(uid = "profile", name = "Profile", model = "model")
    private val now = requireNotNull(reviewTime("2026-11-22")) + 1_000
    private val price = ModelPrice("OPENAI", "model", "1", "2", source = PriceSource.ACCOUNT_OVERRIDE, sourceUrl = "Private contract reference", reviewedOn = "2026-11-22", validUntil = now + 86_400_000)
    private val pricing = capture(price)

    private fun capture(candidate: ModelPrice, selected: PlatformV2 = profile) = benchmarkPricingSnapshot(selected, listOf(ManualPrice(priceContextKey(profile), candidate)), emptyList(), now, "config")

    private fun sample(id: String, outcome: BenchmarkOutcome = BenchmarkOutcome.PASSED, duration: Long = 100) = BenchmarkSample(
        id, id, "task", outcome, durationMs = duration, inputTokens = 10, outputTokens = 20,
        estimatedTokens = false, inputTokenConfidence = "final_reported", outputTokenConfidence = "final_reported",
        requestDispatched = true
    )

    private fun unattempted(id: String, outcome: BenchmarkOutcome) = sample(id, outcome).copy(requestDispatched = false, inputTokens = 0, outputTokens = 0, estimatedTokens = true, inputTokenConfidence = "unknown", outputTokenConfidence = "unknown")

    private fun run(vararg samples: BenchmarkSample) = BenchmarkRun(
        "run", "profile", "Profile", "OPENAI", "model", "config", false, BenchmarkMode.QUICK, now,
        samples.toList(), pricingSnapshot = pricing
    )

    @Test fun failedAnswersContributeEffortAndCostWithoutBecomingSuccesses() {
        val result = benchmarkTaskEfficiency(run(sample("pass"), sample("fail", BenchmarkOutcome.FAILED, 300)))
        assertEquals(2, result.recordedTasks)
        assertEquals(1, result.successfulTasks)
        assertEquals(1, result.unsuccessfulTasks)
        assertEquals(400L, result.elapsedMs)
        assertEquals(400.0, result.elapsedMsPerSuccessfulTask!!, 0.0)
        assertEquals(100L, result.apiCostEstimateMicros)
        assertEquals(100.0, result.apiCostEstimateMicrosPerSuccessfulTask!!, 0.0)
    }

    @Test fun noSuccessfulFixtureDoesNotBecomeZeroCostOrZeroTimePerSuccess() {
        val result = benchmarkTaskEfficiency(run(sample("fail", BenchmarkOutcome.FAILED)))
        assertEquals(100L, result.elapsedMs)
        assertEquals(50L, result.apiCostEstimateMicros)
        assertNull(result.elapsedMsPerSuccessfulTask)
        assertNull(result.apiCostEstimateMicrosPerSuccessfulTask)
    }

    @Test fun warmupsAndUnattemptedFixturesDoNotChangeThePopulation() {
        val result = benchmarkTaskEfficiency(
            run(sample("pass"), sample("warmup", duration = 1_000).copy(warmup = true), unattempted("skip", BenchmarkOutcome.SKIPPED), unattempted("unsupported", BenchmarkOutcome.UNSUPPORTED))
        )
        assertEquals(1, result.recordedTasks)
        assertEquals(1, result.excludedWarmups)
        assertEquals(2, result.excludedUnattempted)
        assertEquals(100L, result.elapsedMs)
        assertEquals(50L, result.apiCostEstimateMicros)
    }

    @Test fun skippedInflightWorkRetainsItsTimeAndCost() {
        val skipped = sample("skipped-in-flight", BenchmarkOutcome.SKIPPED, 400)
        val result = benchmarkTaskEfficiency(run(sample("pass"), skipped))
        assertEquals(2, result.recordedTasks)
        assertEquals(1, result.successfulTasks)
        assertEquals(1, result.unsuccessfulTasks)
        assertEquals(0, result.excludedUnattempted)
        assertEquals(500.0, result.elapsedMsPerSuccessfulTask!!, 0.0)
        assertEquals(100L, result.apiCostEstimateMicros)
    }

    @Test fun retriesAreAlreadyInsideElapsedTimeAndIncompleteRetryUsageIsNotPriced() {
        val result = benchmarkTaskEfficiency(run(sample("retry", duration = 450).copy(retryDurationMs = 150, reconnectAttempts = 1)))
        assertEquals(450.0, result.elapsedMsPerSuccessfulTask!!, 0.0)
        assertEquals(150L, result.retryMs)
        assertNull(result.apiCostEstimateMicros)
        assertEquals(1, result.costExclusions["reconnect_usage_incomplete"])
    }

    @Test fun endToEndTimingIncludesAdmissionAndCleanupWhenRecorded() {
        val result = benchmarkTaskEfficiency(run(sample("pass").copy(endToEndDurationMs = 250)))
        assertEquals(250L, result.elapsedMs)
    }

    @Test fun unknownTimingOrUsageNeverProducesAnOptimisticPartialTotal() {
        val result = benchmarkTaskEfficiency(run(sample("pass"), sample("unknown", BenchmarkOutcome.ERROR, 0).copy(inputTokenConfidence = "unknown")))
        assertEquals(1, result.missingTimingTasks)
        assertNull(result.elapsedMs)
        assertNull(result.elapsedMsPerSuccessfulTask)
        assertEquals(1, result.pricedTasks)
        assertNull(result.apiCostEstimateMicros)
        assertNull(result.apiCostEstimateMicrosPerSuccessfulTask)
    }

    @Test fun localInferenceHasNoApiChargeWithoutClaimingItsTokenUsageWasMeasured() {
        val local = run(sample("local").copy(estimatedTokens = true, inputTokenConfidence = "unknown"))
            .copy(local = true, provider = "LITERT_LM", pricingSnapshot = null)
        val result = benchmarkTaskEfficiency(local)
        assertEquals(0L, result.apiCostEstimateMicros)
        assertEquals(0.0, result.apiCostEstimateMicrosPerSuccessfulTask!!, 0.0)
    }

    @Test fun rejectedBeforeDispatchHasZeroApiChargeButRetainsElapsedEffort() {
        val rejected = unattempted("rejected", BenchmarkOutcome.ERROR).copy(durationMs = 60)
        val result = benchmarkTaskEfficiency(run(sample("pass"), rejected))
        assertEquals(160L, result.elapsedMs)
        assertEquals(50L, result.apiCostEstimateMicros)
        assertEquals(2, result.recordedTasks)
    }

    @Test fun reportedUsageCannotBeErasedByAContradictoryUndispatchedFlag() {
        val contradictory = sample("reported", BenchmarkOutcome.SKIPPED).copy(requestDispatched = false)
        val result = benchmarkTaskEfficiency(run(sample("pass"), contradictory))
        assertEquals(2, result.recordedTasks)
        assertEquals(0, result.excludedUnattempted)
        assertEquals(100L, result.apiCostEstimateMicros)
        val incomplete = contradictory.copy(outputTokenConfidence = "unknown")
        assertNull(benchmarkTaskEfficiency(run(sample("pass"), incomplete)).apiCostEstimateMicros)
    }

    @Test fun delegationDoesNotPriceSeveralModelsAtThePrimaryRate() {
        val result = benchmarkTaskEfficiency(run(sample("delegate")).copy(mode = BenchmarkMode.DELEGATION))
        assertNull(result.apiCostEstimateMicros)
        assertEquals(1, result.costExclusions["participant_usage_not_fully_attributed"])
    }

    @Test fun historicalAndMismatchedPriceSnapshotsStayUnknown() {
        assertNull(benchmarkTaskEfficiency(run(sample("legacy")).copy(pricingSnapshot = null)).apiCostEstimateMicros)
        val mismatched = run(sample("wrong-model")).copy(pricingSnapshot = pricing.copy(model = "another-model"))
        assertEquals(1, benchmarkTaskEfficiency(mismatched).costExclusions["price_identity_mismatch"])
    }

    @Test fun priceCaptureRejectsExpiredFutureWrongModelAndInvalidRates() {
        assertNull(capture(price.copy(reviewedOn = "2026-10-01")).quote)
        assertNull(capture(price.copy(reviewedOn = "2026-11-23")).quote)
        assertNull(capture(price.copy(model = "another-model")).quote)
        assertNull(capture(price.copy(inputPerMillion = "-1")).quote)
        assertNull(capture(price.copy(currency = "ZZZ")).quote)
        assertNull(capture(price.copy(sourceUrl = "")).quote)
        assertEquals(price.copy(sourceUrl = ""), capture(price).quote)
    }

    @Test fun captureBindsManualPricesToTheExactConnectionRouteAndModel() {
        listOf(
            profile.copy(apiUrl = "https://another.example/v1"),
            profile.copy(compatibleType = ClientType.ANTHROPIC),
            profile.copy(providerConnectionUid = "different-connection"),
            profile.copy(openRouterRouting = "different-routing")
        ).forEach { changed -> assertNull(capture(price, changed).quote) }
        assertEquals(benchmarkHash(priceContextKey(profile)), pricing.contextDigest)
        assertEquals("ACCOUNT_OVERRIDE", pricing.sourceKind)
        assertEquals(benchmarkHash(price.sourceUrl), pricing.sourceDigest)
        assertEquals("", pricing.quote?.sourceUrl)
    }

    @Test fun snapshotRatesStayImmutableWhenTheSavedAccountRateChanges() {
        val updated = capture(price.copy(inputPerMillion = "4", currency = "EUR"))
        assertEquals("1", pricing.quote?.inputPerMillion)
        assertEquals("USD", pricing.currency)
        assertEquals("4", updated.quote?.inputPerMillion)
        assertEquals("EUR", updated.currency)
        assertEquals(50L, benchmarkTaskEfficiency(run(sample("original"))).apiCostEstimateMicros)
    }

    @Test fun changedRunContextOrUnboundLegacyPricesCannotBecomeCostEvidence() {
        assertEquals("price_identity_mismatch", benchmarkTaskEfficiency(run(sample("changed")).copy(configKey = "other-route")).costExclusions.keys.single())
        assertEquals("price_identity_mismatch", benchmarkTaskEfficiency(run(sample("changed")).copy(startedAt = now + 1)).costExclusions.keys.single())
        val legacy = BenchmarkPricingSnapshot("profile", "OPENAI", "model", "USD", now, dev.chungjungsoo.gptmobile.data.accounting.ModelPrice("model", 1_000_000, 2_000_000, "Legacy allowance", now))
        assertEquals("price_context_missing", benchmarkTaskEfficiency(run(sample("legacy")).copy(pricingSnapshot = legacy)).costExclusions.keys.single())
    }

    @Test fun batchAccountRatesCannotPriceTheInteractiveBenchmarkRoute() {
        val batchProfile = profile.copy(batchMode = true)
        val batchRate = ManualPrice(priceContextKey(batchProfile), price)
        val snapshot = benchmarkPricingSnapshot(batchProfile, listOf(batchRate), emptyList(), now, "config")
        assertNull(snapshot.quote)
        assertEquals(benchmarkHash(priceContextKey(profile)), snapshot.contextDigest)
        val openRouter = profile.copy(compatibleType = ClientType.OPENROUTER, model = "model:batch", batchMode = true)
        assertEquals("model", benchmarkPricingSnapshot(openRouter, emptyList(), emptyList(), now, "config").requestModel)
        assertEquals("model:batch", benchmarkPricingSnapshot(openRouter, emptyList(), emptyList(), now, "config", BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1).requestModel)
    }

    @Test fun catalogRatesNeedTheRecognizedBillingOriginAndConfirmedConditions() {
        val catalog = price.copy(source = PriceSource.REVIEWED)
        val snapshot = benchmarkPricingSnapshot(profile, emptyList(), listOf(catalog), now, "config")
        assertEquals("REVIEWED", snapshot.sourceKind)
        assertEquals(50L, benchmarkTaskEfficiency(run(sample("catalog")).copy(pricingSnapshot = snapshot)).apiCostEstimateMicros)
        assertNull(benchmarkPricingSnapshot(profile.copy(apiUrl = "https://proxy.example/v1"), emptyList(), listOf(catalog), now, "config").quote)
        listOf(catalog.copy(routingReference = true), catalog.copy(conditionalPricing = true)).forEach { reference ->
            val uncertain = benchmarkPricingSnapshot(profile, emptyList(), listOf(reference), now, "config")
            val result = benchmarkTaskEfficiency(run(sample("uncertain")).copy(pricingSnapshot = uncertain))
            assertNull(result.apiCostEstimateMicros)
            assertEquals(1, result.costExclusions["route_or_conditional_price_unconfirmed"])
        }
    }

    @Test fun decimalRatesArePreservedAndRoundedOnlyAfterBothTokenLanes() {
        val precise = capture(price.copy(inputPerMillion = "0.300000000000000000000001", outputPerMillion = "0.3"))
        val oneEach = sample("fraction").copy(inputTokens = 1, outputTokens = 1)
        assertEquals("0.300000000000000000000001", precise.quote?.inputPerMillion)
        assertEquals(1L, benchmarkTaskEfficiency(run(oneEach).copy(pricingSnapshot = precise)).apiCostEstimateMicros)
    }

    @Test fun costOverflowAndContradictoryQuoteEvidenceStayUnknown() {
        val enormous = capture(price.copy(inputPerMillion = "1000000000000"))
        val overflow = benchmarkTaskEfficiency(run(sample("overflow").copy(inputTokens = 10_000_000)).copy(pricingSnapshot = enormous))
        assertNull(overflow.apiCostEstimateMicros)
        assertEquals(1, overflow.costExclusions["cost_out_of_range"])
        val invalid = pricing.copy(quote = pricing.quote?.copy(provider = "ANOTHER_PROVIDER"))
        assertEquals(1, benchmarkTaskEfficiency(run(sample("invalid")).copy(pricingSnapshot = invalid)).costExclusions["price_evidence_invalid"])
    }

    @Test fun overflowStaysUnknownAndInterruptedResultsAreMarkedPartial() {
        val result = benchmarkTaskEfficiency(run(sample("one", duration = Long.MAX_VALUE), sample("two", duration = 1)).copy(canceled = true))
        assertNull(result.elapsedMs)
        assertTrue(result.provisional)
        assertFalse(benchmarkTaskEfficiency(run(sample("finished"))).provisional)
    }

    @Test fun emptyRunsDoNotReportZeroAsAnObservation() {
        val result = benchmarkTaskEfficiency(run())
        assertEquals(0, result.recordedTasks)
        assertNull(result.elapsedMs)
        assertNull(result.retryMs)
        assertNull(result.apiCostEstimateMicros)
    }
}
