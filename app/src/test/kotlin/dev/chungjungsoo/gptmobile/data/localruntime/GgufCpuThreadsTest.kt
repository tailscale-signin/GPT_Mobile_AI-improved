package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Test

class GgufCpuThreadsTest {
    @Test
    fun autoUsesAvailableProcessorsUpToSafeDefaultLimit() {
        assertEquals(6, GgufCpuThreads.resolve(0, 6))
        assertEquals(8, GgufCpuThreads.resolve(0, 12))
    }

    @Test
    fun autoHandlesMissingAndInvalidProcessorCounts() {
        assertEquals(1, GgufCpuThreads.resolve(null, 0))
        assertEquals(1, GgufCpuThreads.resolve(-1, -4))
    }

    @Test
    fun explicitThreadCountIsPreservedWithinNativeLimit() {
        assertEquals(3, GgufCpuThreads.resolve(3, 8))
        assertEquals(64, GgufCpuThreads.resolve(128, 8))
    }
}
