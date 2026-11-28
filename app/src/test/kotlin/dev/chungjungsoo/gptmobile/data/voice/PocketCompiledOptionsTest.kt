package dev.chungjungsoo.gptmobile.data.voice

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Exercise the actual pinned dependency's option expansion, without loading a native model. */
class PocketCompiledOptionsTest {
    @Test fun gpuRequestRetainsOnlyGpuWithoutAnImplicitCpuRoute() {
        val options = pocketCompiledAccelerationOptions(Accelerator.GPU)
        assertEquals(setOf(Accelerator.GPU), options.getAccelerators())
        assertNull(options.qualcommOptions)
        assertNull(options.cpuOptions)
    }

    @Test fun npuRequestDoesNotAcquireTheLibrarysImplicitCpuFallback() {
        // A plain NPU request is the hazardous control: the actual library adds CPU.
        assertEquals(setOf(Accelerator.NPU, Accelerator.CPU), CompiledModel.Options(Accelerator.NPU).getAccelerators())
        val options = pocketCompiledAccelerationOptions(Accelerator.NPU)
        val expanded = options.getAccelerators()
        assertEquals(setOf(Accelerator.NPU), expanded - Accelerator.NONE)
        assertFalse(expanded.contains(Accelerator.CPU))
        assertEquals(0, Accelerator.NONE.value)
        assertEquals(setOf(0, 3), expanded.map { it.value }.toSet())
        assertNull(options.cpuOptions)
    }

    @Test fun strictNpuOptionsRetainTheQualcommCompilationAndPerformancePolicy() {
        val options = requireNotNull(pocketCompiledAccelerationOptions(Accelerator.NPU).qualcommOptions)
        assertEquals(true, options.enableJustInTime)
        assertEquals(CompiledModel.QualcommOptions.HtpPerformanceMode.SUSTAINED_HIGH_PERFORMANCE, options.htpPerformanceMode)
    }

    @Test fun cpuAndEmptyRequestsCannotMasqueradeAsAnAcceleratedPocketRoute() {
        for (requested in listOf(Accelerator.CPU, Accelerator.NONE)) {
            assertThrows(IllegalArgumentException::class.java) { pocketCompiledAccelerationOptions(requested) }
        }
    }
}
