package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufGpuLoadFailureTest {
    @Test fun missingGpuAndCaughtPipelineFailureAllowIndependentCpuLoad() {
        assertTrue(IllegalStateException("GGUF Vulkan GPU offload is unavailable on this device or build; select CPU").isRecoverableGgufGpuLoadFailure())
        assertTrue(IllegalStateException("load", IllegalStateException("vk::Device::createComputePipeline failed")).isRecoverableGgufGpuLoadFailure())
    }

    @Test fun nativeQuarantineCorruptModelsAndMemoryFailuresCannotTriggerRetry() {
        listOf("This artifact/runtime/backend was interrupted inside native code", "GGUF load failed: unsupported artifact", "MEMORY_ADMISSION_REJECTED", "GGUF context allocation failed or was canceled").forEach {
            assertFalse(IllegalStateException(it).isRecoverableGgufGpuLoadFailure())
        }
    }
}
