package dev.chungjungsoo.gptmobile.data.context

/** Unverified advertised contexts cannot expand replay beyond a conservative accepted ceiling. */
internal object ReplayAllowance {
    fun tokens(context: Int, input: Int, output: Int, fallback: Int): Int {
        val ceiling = if (context > 0) context.coerceAtMost(32_768) else fallback.coerceIn(0, 4096)
        val available = (ceiling.toLong() - input.coerceAtLeast(0) - output.coerceAtLeast(0) - 1024).coerceAtLeast(0)
        return available.coerceAtMost(24_000).toInt()
    }
}
