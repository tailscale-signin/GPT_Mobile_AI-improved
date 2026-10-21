package dev.chungjungsoo.gptmobile.data.agent.provider

/** Completion proof is independent from stage acceptance. Never repair an interrupted stream. */
internal object ReasoningRepairPolicy {
    fun nextCap(current: Int, transportComplete: Boolean, transportFailure: Boolean, attempted: Boolean, providerCeiling: Int? = null, userCap: Int? = null): Int? {
        if (!transportComplete || transportFailure || attempted || current <= 0) return null
        val next = minOf(current.toLong() * 2L, Int.MAX_VALUE.toLong(), providerCeiling?.takeIf { it > 0 }?.toLong() ?: Long.MAX_VALUE, userCap?.takeIf { it > 0 }?.toLong() ?: Long.MAX_VALUE).toInt()
        return next.takeIf { it > current }
    }
}
