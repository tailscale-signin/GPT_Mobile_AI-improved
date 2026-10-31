package dev.chungjungsoo.gptmobile.core

/** Pure accounting: provider/runtime counters remain distinct from estimates. */
fun mergeTokenCount(previous: Int?, incoming: Int?, cumulative: Boolean): Int? {
    val retained = previous?.takeIf { it >= 0 }
    if (incoming == null || incoming < 0) return retained
    return if (cumulative) maxOf(retained ?: 0, incoming) else ((retained ?: 0).toLong() + incoming).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Exact ceil((input * inputRate + output * outputRate) / 1,000,000), without floating point. */
fun tokenCostMicros(input: Long, output: Long, inputRate: Long, outputRate: Long): Long {
    require(input >= 0 && output >= 0 && inputRate >= 0 && outputRate >= 0)
    val scale = 1_000_000L
    fun add(a: Long, b: Long): Long {
        if (a > Long.MAX_VALUE - b) throw ArithmeticException("Token cost exceeds supported range")
        return a + b
    }
    fun multiply(a: Long, b: Long): Long {
        if (b != 0L && a > Long.MAX_VALUE / b) throw ArithmeticException("Token cost exceeds supported range")
        return a * b
    }
    fun term(tokens: Long, rate: Long): Pair<Long, Long> {
        val smallProduct = (tokens % scale) * (rate % scale)
        val whole = add(add(multiply(tokens / scale, rate), multiply(tokens % scale, rate / scale)), smallProduct / scale)
        return whole to smallProduct % scale
    }
    val a = term(input, inputRate)
    val b = term(output, outputRate)
    return add(add(a.first, b.first), (a.second + b.second + scale - 1) / scale)
}

data class TokenObservation(val id: String, val provider: String, val model: String, val input: Int, val output: Int, val estimated: Boolean, val kind: String, val startedAt: Long)

/** Display groups never mix exact model identifiers or replace monetary reservations. */
data class ModelTokenTotals(val provider: String, val model: String, val requestCount: Int, val inputTokens: Long, val outputTokens: Long, val estimated: Boolean, val kinds: List<String>) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

fun compareTokenObservations(observations: List<TokenObservation>): List<ModelTokenTotals> = observations.distinctBy { it.id }.sortedBy { it.startedAt }
    .groupBy { Triple(it.provider.trim(), it.model.trim(), it.id.takeIf { _ -> it.provider.isBlank() || it.model.isBlank() }) }
    .values.map { requests ->
        ModelTokenTotals(requests.first().provider.trim(), requests.first().model.trim(), requests.size, requests.sumOf { it.input.toLong().coerceAtLeast(0) }, requests.sumOf { it.output.toLong().coerceAtLeast(0) }, requests.any { it.estimated }, requests.map { it.kind }.distinct().sorted())
    }
