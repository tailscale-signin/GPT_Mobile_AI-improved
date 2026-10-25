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
        val observation =
            NativeAllocationObservation(
                contextTokens = 1024,
                batchTokens = 256,
                microBatchTokens = 128,
                hostModelBytes = 10,
                hostContextBytes = 10,
                hostComputeBytes = 10,
                deviceModelBytes = 0,
                deviceContextBytes = 0,
                deviceComputeBytes = 0,
                modelTensorBytes = 10,
                processPssBytes = null
            )
        val estimated = ggufMemoryComponents(1000, 1024, 1)
        assertEquals(estimated, ggufMemoryComponents(1000, 1024, 1, measured = observation))
        val measured = observation.copy(hostComputeBytes = estimated.getValue("computeWorkspace") + 1024)
        assertTrue(ggufMemoryComponents(1000, 1024, 1, measured = measured).totalMemoryBytes() > estimated.totalMemoryBytes())
    }

    @Test fun incompleteOrNegativeNativeReceiptsAreRejected() {
        assertTrue(
            runCatching {
                NativeAllocationObservation(
                    contextTokens = 512,
                    batchTokens = 0,
                    microBatchTokens = 0,
                    hostModelBytes = -1,
                    hostContextBytes = 0,
                    hostComputeBytes = 0,
                    deviceModelBytes = 0,
                    deviceContextBytes = 0,
                    deviceComputeBytes = 0,
                    modelTensorBytes = 0,
                    processPssBytes = null
                ).validated()
            }.isFailure
        )
    }

    @Test fun validatedGeometryAndExactOwnershipCalibrationReplaceTheObsoleteKvFloor() {
        val mib = 1024L * 1024
        val old = ggufMemoryComponents(1024 * mib, 8192, 1)
        val geometry = ggufMemoryComponents(1024 * mib, 8192, 18 * 1024, geometryKnown = true)
        assertEquals(144 * mib, geometry.getValue("kvCache"))
        assertTrue(geometry.totalMemoryBytes() < old.totalMemoryBytes())
        val native =
            NativeAllocationObservation(
                schemaVersion = 2,
                contextTokens = 8192,
                batchTokens = 256,
                microBatchTokens = 128,
                hostModelBytes = 1024 * mib,
                hostContextBytes = 144 * mib,
                hostComputeBytes = 72 * mib,
                deviceModelBytes = 0,
                deviceContextBytes = 0,
                deviceComputeBytes = 0,
                modelTensorBytes = 1024 * mib,
                processPssBytes =
                5_000 * mib,
                hostAccessibleModelBytes = 1024 * mib,
                hostAccessibleContextBytes = 144 * mib,
                hostAccessibleComputeBytes = 72 * mib,
                generationObserved = true
            )
        val calibrated = ggufMemoryComponents(1024 * mib, 8192, 1, measured = native)
        assertEquals(144 * mib, calibrated.getValue("kvCache"))
        assertEquals(72 * mib, calibrated.getValue("computeWorkspace"))
        assertTrue(calibrated.getValue("uncertaintyMargin") >= 64 * mib)
        assertEquals(
            calibrated,
            ggufMemoryComponents(1024 * mib, 8192, 1, measured = native.copy(processPssBytes = null, hostAccessibleModelBytes = 0))
        )
        assertEquals(
            2L * 1024 * mib,
            ggufMemoryComponents(1024 * mib, 8192, 1, measured = native.copy(contextTokens = 4096)).getValue("kvCache")
        )
    }

    @Test fun cpuRepackedAndSharedOwnershipDoNotDependOnHostAccessibility() {
        val native =
            NativeAllocationObservation(
                schemaVersion = 2,
                contextTokens = 512,
                batchTokens = 256,
                microBatchTokens = 128,
                hostModelBytes = 251,
                hostContextBytes = 10,
                hostComputeBytes = 20,
                deviceModelBytes = 0,
                deviceContextBytes = 0,
                deviceComputeBytes = 0,
                modelTensorBytes = 100,
                processPssBytes = 1000,
                sharedModelBytes = 50,
                hostAccessibleModelBytes = 0
            ).validated()
        assertEquals(281L, native.cpuOwnedBytes)
        assertEquals(0L, native.gpuOwnedBytes)
        assertEquals(50L, native.sharedOwnedBytes)
        assertEquals(301L, native.modelOwnedBytes)
    }
}
