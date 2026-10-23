package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.concurrent.CancellationException

/** Only caught, recoverable GPU load failures may retry on the independent CPU backend. */
internal fun Throwable.isRecoverableGgufGpuLoadFailure(): Boolean = hasRecoverableGpuCause(allowUnavailable = true)

internal fun Throwable.isVulkanComputePipelineFailure(): Boolean = hasRecoverableGpuCause(allowUnavailable = false)

private fun Throwable.hasRecoverableGpuCause(allowUnavailable: Boolean): Boolean {
    val causes = generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    if (causes.any { it is CancellationException }) return false
    return causes.any {
        val message = it.message.orEmpty()
        message.contains("vk::Device::createComputePipeline", ignoreCase = true) ||
            allowUnavailable &&
            message.contains("GGUF Vulkan GPU offload is unavailable", ignoreCase = true)
    }
}
