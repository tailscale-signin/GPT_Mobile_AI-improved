package dev.chungjungsoo.gptmobile.data.localruntime

/** Only caught, recoverable GPU load failures may retry on the independent CPU backend. */
internal fun Throwable.isRecoverableGgufGpuLoadFailure(): Boolean =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(8).any {
        val message = it.message.orEmpty()
        message.contains("vk::Device::createComputePipeline", ignoreCase = true) ||
            message.contains("GGUF Vulkan GPU offload is unavailable", ignoreCase = true)
    }
