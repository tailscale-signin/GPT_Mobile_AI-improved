package dev.chungjungsoo.gptmobile.data.privacy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyBenchmarkTest {
    @Test fun fictionalCorpusRequiresPrivacyIntentRestorationAndIsolationTogether() = runBlocking {
        val result = PrivacyBenchmark.run(iterations = 1)
        assertTrue(result.failures.toString(), result.passed)
        assertEquals(result.expectedPrivateValues, result.protectedValues)
        assertEquals(result.expectedPublicPhrases, result.preservedPhrases)
        assertEquals(result.cases, result.exactRestorations)
        assertEquals(result.cases, result.timingSamples)
        assertEquals("SYNTHETIC_LOCAL_CPU", result.measurementScope)
        assertEquals("NOT_MEASURED", result.providerAnswerUtility)
        assertTrue(result.maskingP95Micros >= result.maskingMedianMicros)
        assertTrue(result.restorationP95Micros >= result.restorationMedianMicros)
    }

    @Test fun latencyPercentilesUseObservedSamplesWithoutInventingASpeedThreshold() {
        assertEquals(3L, PrivacyBenchmark.percentile(listOf(10, 1, 3, 8, 2), 0.5))
        assertEquals(10L, PrivacyBenchmark.percentile(listOf(10, 1, 3, 8, 2), 0.95))
        assertEquals(1L, PrivacyBenchmark.percentile(listOf(1), 0.95))
        assertEquals(5L, PrivacyBenchmark.percentile(listOf(1, 9), 0.5))
    }

    @Test(expected = CancellationException::class)
    fun cancellationDoesNotReturnAPartialSuccess() = runBlocking {
        coroutineScope {
            cancel()
            PrivacyBenchmark.run()
        }
        Unit
    }
}
