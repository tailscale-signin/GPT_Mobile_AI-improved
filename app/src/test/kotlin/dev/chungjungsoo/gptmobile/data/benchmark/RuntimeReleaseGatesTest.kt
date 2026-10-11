package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReleaseGatesTest {
    private val complete = RuntimeReleaseEvidence(true, 500, 500, 0, 0, 50, 10, 20, true, true, true, true, true, 1.2, 1.1, 1.0, .8)

    @Test fun `quick measurements cannot qualify native cooperation`() {
        assertFalse(RuntimeReleaseGates.failures(RuntimeReleaseEvidence(), LocalExecutionMode.TENSOR_GPU_NPU).isEmpty())
        assertTrue(RuntimeReleaseGates.failures(complete, LocalExecutionMode.TENSOR_GPU_NPU).isEmpty())
        assertFalse(RuntimeReleaseGates.failures(complete.copy(pairedConfidenceLower = 1.0), LocalExecutionMode.TENSOR_GPU_NPU).isEmpty())
    }

    @Test fun `helpers need workflow gain without hurting primary decode`() {
        assertTrue(RuntimeReleaseGates.failures(complete.copy(sustainedDecodeRatio = .97), LocalExecutionMode.BOUNDED_HELPER).isEmpty())
        assertFalse(RuntimeReleaseGates.failures(complete.copy(sustainedDecodeRatio = .90), LocalExecutionMode.BOUNDED_HELPER).isEmpty())
        assertFalse(RuntimeReleaseGates.failures(complete.copy(qualityEquivalent = false), LocalExecutionMode.SINGLE).isEmpty())
    }
}
