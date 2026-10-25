package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionUsage

/** Repeated cumulative usage packets are observations, not additional charges. */
internal fun ChatCompletionUsage?.trustedTotalTokens(): Long? {
    if (this == null) return null
    if (listOfNotNull(promptTokens, completionTokens, totalTokens).any { it < 0 }) return null
    val sum = if (promptTokens != null && completionTokens != null) promptTokens.toLong() + completionTokens else null
    if (sum != null && totalTokens != null && sum != totalTokens.toLong()) return null
    return (sum ?: totalTokens?.toLong())?.takeIf { it > 0 }
}

/** A partial stream's cumulative packet is not a final usage receipt. */
internal class FreeTokenUsageReceipt {
    private var total: Long? = null
    private var consistent = true
    private var coversLatestPayload = false
    private var complete = false

    fun observe(usage: ChatCompletionUsage?, hasPayload: Boolean) {
        if (hasPayload) coversLatestPayload = false
        if (usage == null) return
        val value = usage.trustedTotalTokens()
        if (value == null || value < (total ?: 0)) {
            consistent = false
            return
        }
        total = value
        coversLatestPayload = true
    }

    fun completed(successful: Boolean) {
        complete = successful
    }

    fun settledTokens(): Long? = total?.takeIf { complete && consistent && coversLatestPayload }
}
