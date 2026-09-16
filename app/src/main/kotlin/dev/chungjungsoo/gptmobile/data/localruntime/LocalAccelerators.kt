package dev.chungjungsoo.gptmobile.data.localruntime

object LocalAccelerators {
    const val CPU = "cpu"
    const val GPU = "gpu"
    const val NPU = "npu"

    fun normalize(accelerator: String): String {
        return when (accelerator.lowercase()) {
            "cpu", "arm", "aarch64" -> CPU
            "gpu", "opencl", "opengl" -> GPU
            "npu", "hexagon", "qualcomm" -> NPU
            else -> CPU
        }
    }

    fun shouldApplySampler(accelerator: String): Boolean {
        return normalize(accelerator) != CPU
    }
}