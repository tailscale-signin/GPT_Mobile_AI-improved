package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.concurrent.CancellationException

internal enum class GgufFailureCategory { CANCELLED, DEVICE_UNAVAILABLE, SHADER_COMPILATION, MODEL_LOAD, ALLOCATION, UNSUPPORTED_OPERATION, NATIVE_PROCESS_EXIT, UNKNOWN }

internal fun Throwable.ggufFailureCategory(): GgufFailureCategory {
    val causes = generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    if (causes.any { it is CancellationException }) return GgufFailureCategory.CANCELLED
    val native = causes.filterIsInstance<dev.melo.gguf.GgufNativeException>().firstOrNull()
    native?.category?.let { category -> GgufFailureCategory.entries.firstOrNull { it.name == category }?.let { return it } }
    // Compatibility for older JNI builds; new native paths carry a typed category.
    val message = causes.joinToString("\n") { it.message.orEmpty() }.lowercase()
    return when {
        "createcomputepipeline" in message -> GgufFailureCategory.SHADER_COMPILATION
        "gguf vulkan gpu offload is unavailable" in message -> GgufFailureCategory.DEVICE_UNAVAILABLE
        else -> GgufFailureCategory.UNKNOWN
    }
}
internal fun Throwable.isRecoverableGgufGpuLoadFailure(): Boolean = ggufFailureCategory() in setOf(
    GgufFailureCategory.DEVICE_UNAVAILABLE,
    GgufFailureCategory.SHADER_COMPILATION,
    GgufFailureCategory.ALLOCATION,
    GgufFailureCategory.MODEL_LOAD,
    GgufFailureCategory.UNSUPPORTED_OPERATION
)
internal fun Throwable.isVulkanComputePipelineFailure(): Boolean = ggufFailureCategory() == GgufFailureCategory.SHADER_COMPILATION
