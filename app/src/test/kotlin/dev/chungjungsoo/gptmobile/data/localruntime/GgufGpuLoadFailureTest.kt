package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufGpuLoadFailureTest {
    @Test fun cancellationInsideAWrappedGpuFailureCannotRestartGeneration() {
        val cancelled = java.util.concurrent.CancellationException("vk::Device::createComputePipeline canceled")
        val wrapped = IllegalStateException("GGUF Vulkan GPU offload is unavailable", cancelled)
        assertFalse(wrapped.isRecoverableGgufGpuLoadFailure())
        assertFalse(wrapped.isVulkanComputePipelineFailure())
    }

    @Test fun cyclicCausesTerminateAndLoadOnlyFailuresCannotRetryGeneration() {
        val first = IllegalStateException("GPU failed")
        val second = IllegalStateException("driver failed", first)
        first.initCause(second)
        assertFalse(first.isVulkanComputePipelineFailure())
        val unavailable = IllegalStateException("GGUF Vulkan GPU offload is unavailable")
        assertTrue(unavailable.isRecoverableGgufGpuLoadFailure())
        assertFalse(unavailable.isVulkanComputePipelineFailure())
        assertTrue(IllegalStateException("vk::Device::createComputePipeline failed").isVulkanComputePipelineFailure())
    }

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
