package dev.chungjungsoo.gptmobile.core

import java.math.BigInteger
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMathTest {
    @Test fun exactPricingMatchesIndependentBigIntegerOracleAtLargeValuesAndRoundingBoundaries() {
        val random = Random(7)
        val cases = listOf(longArrayOf(1, 1, 1, 1), longArrayOf(Long.MAX_VALUE, 0, 1_000_000, 0), longArrayOf(Long.MAX_VALUE, 1, 1_000_000, 1), longArrayOf(0, 0, Long.MAX_VALUE, Long.MAX_VALUE)) +
            List(1000) { longArrayOf(random.nextLong(0, Long.MAX_VALUE), random.nextLong(0, Long.MAX_VALUE), random.nextLong(0, 1_000_001), random.nextLong(0, 1_000_001)) }
        for ((input, output, inputRate, outputRate) in cases) {
            val numerator = BigInteger.valueOf(input) * BigInteger.valueOf(inputRate) + BigInteger.valueOf(output) * BigInteger.valueOf(outputRate)
            val expected = runCatching { (numerator + BigInteger.valueOf(999_999)).divide(BigInteger.valueOf(1_000_000)).longValueExact() }
            val actual = runCatching { tokenCostMicros(input, output, inputRate, outputRate) }
            assertEquals(expected.getOrNull(), actual.getOrNull())
            assertEquals(expected.isFailure, actual.isFailure)
        }
    }

    @Test fun usageRejectsNegativeValuesDeduplicatesSnapshotsAndSaturatesDeltas() {
        assertEquals(null, mergeTokenCount(-5, null, true))
        assertEquals(100, mergeTokenCount(100, 100, true))
        assertEquals(100, mergeTokenCount(100, -1, false))
        assertEquals(Int.MAX_VALUE, mergeTokenCount(Int.MAX_VALUE, 10, false))
        assertTrue(runCatching { tokenCostMicros(-1, 0, 1, 1) }.isFailure)
    }

    @Test fun modelComparisonsPreserveTokenizerIdentityAndPreferCurrentReceipts() {
        val current = TokenObservation("request", "provider", "Model-Q4", 10, 20, false, "primary", 1)
        val rows = compareTokenObservations(listOf(current, current.copy(input = 999), current.copy(id = "other", model = "Model-Q8", estimated = true)))
        assertEquals(2, rows.size)
        assertEquals(30L, rows.first().totalTokens)
        assertEquals(false, rows.first().estimated)
        assertEquals(true, rows.last().estimated)
    }

    @Test fun hardwareAndComplexityRoutingCannotInventDestinationsOrOverrideFixedSelection() {
        val candidates = listOf(InferenceCandidate("local", true), InferenceCandidate("approved-remote", false))
        assertEquals("local", InferenceRouting.choose(candidates, "local", false, false, true).profileId)
        assertEquals(null, InferenceRouting.choose(candidates, "local", false, true, false).profileId)
        assertEquals("approved-remote", InferenceRouting.choose(candidates, "local", true, true, false).profileId)
        assertEquals("approved-remote", InferenceRouting.choose(candidates, "", true, false, true).profileId)
        assertEquals(null, InferenceRouting.choose(candidates.take(1), "", true, true, false).profileId)
    }
}
