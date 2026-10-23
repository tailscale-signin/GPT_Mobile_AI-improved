package dev.chungjungsoo.gptmobile.data.localruntime

/** Resolves the GGUF thread setting; zero is the persisted Auto value. */
internal object GgufCpuThreads {
    fun candidates(availableProcessors: Int): List<Int> = listOf(2, 4, availableProcessors.coerceAtMost(8)).map { it.coerceIn(1, maxOf(1, availableProcessors)) }.distinct()

    fun resolve(requested: Int?, availableProcessors: Int, measured: Int? = null): Int =
        requested
            ?.takeIf { it > 0 }
            ?.coerceIn(1, 64)
            ?: measured?.takeIf { it in 1..availableProcessors.coerceAtMost(64) }
            ?: availableProcessors.coerceIn(1, 4)
}
