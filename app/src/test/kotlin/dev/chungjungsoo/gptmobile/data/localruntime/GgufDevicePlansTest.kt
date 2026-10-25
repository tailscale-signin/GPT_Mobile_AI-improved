package dev.chungjungsoo.gptmobile.data.localruntime

import dev.melo.gguf.GgufNativeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufDevicePlansTest {
    private fun experiment(): List<GgufDeviceSample> =
        buildList {
            for (threads in listOf(2, 4, 6, 8)) {
                for (repeat in 0..2) {
                    for (regime in listOf("cold", "cache_hot")) {
                        add(
                            GgufDeviceSample(
                                "cpu",
                                0,
                                threads,
                                threads,
                                if (threads ==
                                    6
                                ) {
                                    24.0
                                } else {
                                    12.0
                                },
                                if (threads == 2) 15.0 else 10.0,
                                if (regime == "cold") 256 else 1,
                                64,
                                true,
                                experiment = CpuExperimentTrial("experiment", "a".repeat(64), threads, regime, repeat),
                                nativeGenerationId = "$threads-$repeat-$regime",
                                engineInstanceId = "engine-$threads-$repeat",
                                totalPromptTokens = 256,
                                renderedPromptDigest = "fnv1a64:0123456789abcdef",
                                reusedPromptTokens =
                                if (regime ==
                                    "cold"
                                ) {
                                    0
                                } else {
                                    255
                                },
                                cpuTimeMs = 1000,
                                thermalState = "NORMAL",
                                terminalKind = "output_limit"
                            )
                        )
                    }
                }
            }
        }

    @Test fun autoUsesCompleteControlledPrefillAndDecodeCohorts() {
        val samples = experiment()
        assertEquals(6, GgufDevicePlans.threads(samples, true))
        assertEquals(2, GgufDevicePlans.threads(samples, false))
        assertNull(GgufDevicePlans.threads(samples.filter { it.threads != 8 }, true))
        assertNull(GgufDevicePlans.threads(samples.map { it.copy(experiment = null) }, false))
        assertNull(GgufDevicePlans.threads(samples.map { it.copy(outputTokens = 2) }, false))
        assertNull(GgufDevicePlans.threads(samples.map { it.copy(thermalState = null) }, false))
        assertEquals(3, GgufCpuThreads.resolve(3, 8, 2))
    }

    @Test fun mixedFixturesAndUnprovenHotCacheCannotSelectAutomaticPresets() {
        val samples = experiment()
        assertNull(
            GgufDevicePlans.threads(
                samples.map {
                    it.copy(experiment = it.experiment!!.copy(fixtureDigest = it.threads.toString().repeat(64)))
                },
                true
            )
        )
        assertNull(
            GgufDevicePlans.threads(
                samples.map {
                    if (it.experiment!!.cacheRegime ==
                        "cache_hot"
                    ) {
                        it.copy(reusedPromptTokens = null)
                    } else {
                        it
                    }
                },
                false
            )
        )
    }

    @Test fun gpuRequiresActualOffloadRepeatedNativeWorkAndCancellationRecovery() {
        val first = experiment().first().copy(backend = "opencl", layers = 8, nativeGenerationId = "gpu-1")
        val recovered = first.copy(nativeGenerationId = "gpu-2", cancellationRecovered = true)
        assertNull(GgufDevicePlans.gpu(listOf(first)))
        assertNull(GgufDevicePlans.gpu(listOf(first, recovered.copy(layers = 0))))
        assertNull(GgufDevicePlans.gpu(listOf(first, recovered.copy(cancellationRecovered = false))))
        assertNotNull(GgufDevicePlans.gpu(listOf(first, recovered)))
    }

    @Test fun cancelledOffloadRemainsRecordedWithoutQualifyingHigherLayers() {
        val directory = kotlin.io.path.createTempDirectory("gguf-offload-evidence").toFile()
        try {
            val plans = GgufDevicePlans(directory)
            val key = "a".repeat(64)
            val cancelled = experiment().first().copy(
                backend = "gpu",
                layers = 8,
                prefill = 0.0,
                decode = 0.0,
                measuredPromptTokens = 0,
                outputTokens = 0,
                nativeGenerationId = "cancelled-before-prefill",
                terminalKind = "cancelled"
            )
            plans.record(key, cancelled)
            val retained = plans.read(key)
            assertEquals(listOf(cancelled), retained)
            assertFalse(GgufDevicePlans.hasProductiveGpuGeneration(retained, "gpu", 8))
            assertNull(GgufDevicePlans.gpu(retained))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun higherOffloadRequiresProductiveMatchingBackendAndNativeEvidence() {
        val completed = experiment().first().copy(backend = "gpu", layers = 8, nativeGenerationId = "gpu-completed")
        assertTrue(GgufDevicePlans.hasProductiveGpuGeneration(listOf(completed), "gpu", 8))
        assertTrue(GgufDevicePlans.hasProductiveGpuGeneration(listOf(completed.copy(terminalKind = "eos")), "gpu", 8))
        assertFalse(GgufDevicePlans.hasProductiveGpuGeneration(listOf(completed), "opencl", 8))
        assertFalse(GgufDevicePlans.hasProductiveGpuGeneration(listOf(completed.copy(layers = 4)), "gpu", 8))
        val incomplete = listOf(
            completed.copy(terminalKind = "cancelled"),
            completed.copy(terminalKind = "native_error"),
            completed.copy(nativeGenerationId = null),
            completed.copy(engineInstanceId = null),
            completed.copy(measuredPromptTokens = 0),
            completed.copy(outputTokens = 0),
            completed.copy(prefill = 0.0),
            completed.copy(decode = 0.0)
        )
        incomplete.forEach {
            assertFalse("Incomplete GPU evidence must not qualify escalation: $it", GgufDevicePlans.hasProductiveGpuGeneration(listOf(it), "gpu", 8))
        }
        // Manual escalation retains its single productive-generation gate; Auto still
        // requires repetition and demonstrated recovery after a cancellation.
        assertNull(GgufDevicePlans.gpu(listOf(completed)))
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
