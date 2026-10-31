package dev.chungjungsoo.gptmobile.data.context

/** Allocate reply and actionable evidence together within the conservative route ceiling. */
internal object ReplayAllowance {
    data class Allocation(val output: Int, val replay: Int, val ceiling: Int)
    fun allocate(context: Int, input: Int, output: Int, fallback: Int): Allocation {
        val ceiling = if (context > 0) context.coerceAtMost(32_768) else fallback.coerceIn(0, 4096)
        val remaining = (ceiling.toLong() - input.coerceAtLeast(0) - 1024).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val evidence = minOf(4096, (remaining - 512).coerceAtLeast(0) / 2)
        val reply = minOf(output.coerceAtLeast(0), (remaining - evidence).coerceAtLeast(0))
        return Allocation(reply, (remaining - reply).coerceIn(0, 24_000), ceiling)
    }
    fun tokens(context: Int, input: Int, output: Int, fallback: Int): Int = allocate(context, input, output, fallback).replay
}
