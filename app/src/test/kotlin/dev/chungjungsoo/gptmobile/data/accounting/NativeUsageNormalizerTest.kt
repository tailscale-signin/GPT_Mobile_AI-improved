package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeUsageNormalizerTest {
    private fun metrics(id: String, input: Int = 240, output: Int = 37) = LocalInferenceMetrics(segmentId = id, native = NativeInferenceMetrics(input, output, 0.0, 0.0))

    @Test fun duplicateReceiptsAndDistinctSegmentsAreCountedOnce() {
        val usage = NativeUsageNormalizer()
        repeat(3) { assertEquals(37, usage.accept(metrics("one"), "fallback", "LLAMA_CPP").outputTokens) }
        val final = usage.accept(metrics("two", 12, 8), "fallback", "LLAMA_CPP")
        assertEquals(252, final.inputTokens)
        assertEquals(45, final.outputTokens)
        assertEquals("runtime_reported", final.outputSource)
        assertNull(final.decodeTokensPerSecond)
    }

    @Test fun exactCountsReplaceEstimatesEvenWhenSmallerOrZero() {
        val usage = NativeUsageNormalizer()
        assertEquals("estimated", usage.accept(LocalInferenceMetrics(segmentId = "one", estimatedTokens = 500), "fallback", null).outputSource)
        assertEquals(37, usage.accept(metrics("one"), "fallback", null).outputTokens)
        assertEquals(37, usage.accept(metrics("two", 0, 0), "fallback", null).outputTokens)
    }

    @Test fun negativeNativeCountsRemainUnknownAndIntegerBoundsSaturate() {
        val usage = NativeUsageNormalizer()
        assertNull(usage.accept(metrics("invalid", -1, 2), "fallback", null).inputTokens)
        val other = NativeUsageNormalizer()
        other.accept(metrics("one", Int.MAX_VALUE, Int.MAX_VALUE), "fallback", null)
        assertEquals(Int.MAX_VALUE, other.accept(metrics("two"), "fallback", null).totalTokens)
    }
}
