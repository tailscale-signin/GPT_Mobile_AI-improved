package dev.chungjungsoo.gptmobile.data.agent

/** Exact weighted allocation; candidate density does not certify source truth. */
internal object CombinedSectionBudget {
    fun allocate(total: Int, claimCounts: List<Int>): List<Int> {
        require(total >= 0 && claimCounts.all { it >= 0 })
        if (claimCounts.isEmpty()) return emptyList()
        val floor = minOf(total / claimCounts.size, 12)
        val remainder = total.toLong() - floor.toLong() * claimCounts.size
        val weights = claimCounts.map { maxOf(1, it).toLong() }
        val sum = weights.sum()
        val result = weights.map { floor + (remainder * it / sum).toInt() }.toMutableList()
        val order = weights.indices.sortedWith(compareByDescending<Int> { remainder * weights[it] % sum }.thenBy { it })
        repeat(total - result.sum()) { result[order[it]]++ }
        return result
    }
}
