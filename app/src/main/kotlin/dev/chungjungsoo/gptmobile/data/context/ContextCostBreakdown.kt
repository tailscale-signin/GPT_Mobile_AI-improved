package dev.chungjungsoo.gptmobile.data.context

/** Byte-based planning estimates, never a substitute for provider-reported billed usage. */
data class ContextCostBreakdown(val system: Int, val toolSchemas: Int, val currentMessage: Int, val history: Int, val attachments: Int) {
    val total: Int get() = listOf(system, toolSchemas, currentMessage, history, attachments).sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    fun diagnostic(): String = "systemTokens=$system · toolSchemaTokens=$toolSchemas · currentMessageTokens=$currentMessage · historyTokens=$history · attachmentTokens=$attachments · totalTokens=$total · estimated=true"
}
