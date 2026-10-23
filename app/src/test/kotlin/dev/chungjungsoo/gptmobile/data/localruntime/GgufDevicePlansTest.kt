package dev.chungjungsoo.gptmobile.data.localruntime

import dev.melo.gguf.GgufNativeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufDevicePlansTest {
    private fun sample(threads: Int, prompt: Int, prefill: Double, decode: Double) = GgufDeviceSample("cpu", 0, threads, prompt, prefill, decode, 256, 64, true)

    @Test fun autoUsesSeparateRepeatedPrefillAndDecodeMeasurements() {
        val samples = listOf(sample(2, 2, 8.0, 12.0), sample(4, 8, 24.0, 9.0), sample(8, 4, 18.0, 7.0)).flatMap { listOf(it, it) }
        assertEquals(8, GgufDevicePlans.threads(samples, true))
        assertEquals(2, GgufDevicePlans.threads(samples, false))
        assertNull(GgufDevicePlans.threads(samples.map { it.copy(outputTokens = 2) }, false))
        assertEquals(3, GgufCpuThreads.resolve(3, 8, 2))
        assertEquals(2, GgufCpuThreads.resolve(0, 8, 2))
    }

    @Test fun onlyRealGpuOutputAndActualOffloadedLayersQualify() {
        val base = sample(4, 4, 10.0, 10.0).copy(backend = "opencl", layers = 8)
        assertNull(GgufDevicePlans.gpu(listOf(base.copy(layers = 0), base.copy(outputTokens = 0))))
        assertEquals(base, GgufDevicePlans.gpu(listOf(base)))
    }

    @Test fun typedGpuFailuresRetainOriginalCauseAndRespectCancellation() {
        for (category in listOf("DEVICE_UNAVAILABLE", "SHADER_COMPILATION", "ALLOCATION", "UNSUPPORTED_OPERATION")) {
            val failure = GgufNativeException(category, "original driver failure")
            assertTrue(failure.isRecoverableGgufGpuLoadFailure())
            assertEquals("original driver failure", failure.message)
        }
        assertFalse(IllegalStateException("wrapper", kotlinx.coroutines.CancellationException()).isRecoverableGgufGpuLoadFailure())
        assertFalse(GgufNativeException("UNKNOWN", "bad template").isRecoverableGgufGpuLoadFailure())
    }
}
