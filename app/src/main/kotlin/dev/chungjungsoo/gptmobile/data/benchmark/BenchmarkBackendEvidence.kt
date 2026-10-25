package dev.chungjungsoo.gptmobile.data.benchmark

/** Explicit registry aliases only. Unknown values never inherit CPU qualification. */
internal fun benchmarkBackendKind(value: String?): String? {
    val normalized = value?.trim()?.lowercase(java.util.Locale.ROOT) ?: return null
    return when {
        normalized.matches(Regex("cpu(?:[ _-]?[0-9]+)?")) -> "cpu"
        normalized.matches(Regex("(?:gpu|vulkan)(?:[ _-]?[0-9]+)?")) -> "gpu"
        normalized.matches(Regex("(?:opencl|igpu)(?:[ _-]?[0-9]+)?")) -> "opencl"
        normalized.matches(Regex("(?:npu|htp|qnn)(?:[ _-]?[0-9]+)?")) -> "npu"
        else -> null
    }
}
