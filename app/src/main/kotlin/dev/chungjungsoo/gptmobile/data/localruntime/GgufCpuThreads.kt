package dev.chungjungsoo.gptmobile.data.localruntime

/** Resolves the GGUF thread setting; zero is the persisted Auto value. */
internal object GgufCpuThreads {
    fun resolve(requested: Int?, availableProcessors: Int): Int =
        requested
            ?.takeIf { it > 0 }
            ?.coerceIn(1, 64)
            ?: availableProcessors.coerceIn(1, 8)
}
