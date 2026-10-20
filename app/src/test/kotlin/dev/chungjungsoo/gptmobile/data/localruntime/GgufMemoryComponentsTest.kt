package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufMemoryComponentsTest {
    @Test fun paddedContextBatchAndBackendAreSeparateComponents() {
        val small = ggufMemoryComponents(1000, 513, 1, 64, 64, 1)
        assertEquals(768L * 256 * 1024, small.getValue("kvCache"))
        val large = ggufMemoryComponents(1000, 513, 1, 256, 128, 4, "gpu")
        assertTrue(large.getValue("computeWorkspace") > small.getValue("computeWorkspace"))
        assertEquals(1000L, large.getValue("backendOverlap"))
        assertTrue(large.totalMemoryBytes() > small.totalMemoryBytes())
    }

    @Test fun measuredComponentsCannotReduceConservativeUnknownGeometry() {
        val observation = NativeAllocationObservation(contextTokens = 1024, batchTokens = 256, microBatchTokens = 128, hostModelBytes = 10, hostContextBytes = 10, hostComputeBytes = 10, deviceModelBytes = 0, deviceContextBytes = 0, deviceComputeBytes = 0, modelTensorBytes = 10, processPssBytes = null)
        val estimated = ggufMemoryComponents(1000, 1024, 1)
        assertEquals(estimated, ggufMemoryComponents(1000, 1024, 1, measured = observation))
        val measured = observation.copy(hostComputeBytes = estimated.getValue("computeWorkspace") + 1024)
        assertTrue(ggufMemoryComponents(1000, 1024, 1, measured = measured).totalMemoryBytes() > estimated.totalMemoryBytes())
    }

    @Test fun incompleteOrNegativeNativeReceiptsAreRejected() {
        assertTrue(runCatching { NativeAllocationObservation(contextTokens = 512, batchTokens = 0, microBatchTokens = 0, hostModelBytes = -1, hostContextBytes = 0, hostComputeBytes = 0, deviceModelBytes = 0, deviceContextBytes = 0, deviceComputeBytes = 0, modelTensorBytes = 0, processPssBytes = null).validated() }.isFailure)
    }
}
