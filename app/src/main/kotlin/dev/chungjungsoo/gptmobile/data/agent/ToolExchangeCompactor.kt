package dev.chungjungsoo.gptmobile.data.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

/**
 * Builds a bounded replay view of tool exchanges for subsequent model rounds.
 *
 * Raw results remain in-memory for diagnostics/UI. Only the provider replay is compacted,
 * preventing every tool round from re-sending the complete accumulated payload.
 */
internal object ToolExchangeCompactor {
    private const val MIN_RESULT_TOKENS = 64
    private const val CHARS_PER_TOKEN = 3
    private const val OMITTED_RESULT = "[Earlier consumed tool result omitted from primary replay.]"
    private const val DUPLICATE_RESULT = "[Earlier duplicate tool result omitted; newest identical result retained.]"

    fun compact(
        exchanges: List<AgentToolExchange>,
        maxReplayTokens: Int,
        maxResultTokens: Int
    ): List<AgentToolExchange> {
        if (exchanges.isEmpty() || maxReplayTokens == Int.MAX_VALUE) return exchanges

        val hardBudget = maxReplayTokens.coerceAtLeast(1)
        val perResultBudget = maxResultTokens.coerceAtLeast(MIN_RESULT_TOKENS)
        val fingerprints = exchanges.flatMap { it.results }.map { fingerprint(render(it.content)) }
        val lastOccurrence = fingerprints.withIndex().associate { (index, value) -> value to index }
        var resultIndex = 0
        var remaining = hardBudget

        // Reserve call metadata first so tool-call/result pairing is never broken.
        exchanges.forEach { exchange ->
            remaining -= exchange.calls.sumOf { ContextTokenEstimate.estimate(it.name + it.arguments.toString()) + 24 }
        }
        remaining = remaining.coerceAtLeast(0)

        // Spend replay allowance newest-first. Once exhausted, older consumed
        // payloads become tiny placeholders instead of each receiving a minimum
        // slice that can silently grow the next provider request past budget.
        val resultBudgets = IntArray(fingerprints.size)
        for (index in fingerprints.indices.reversed()) {
            val duplicate = lastOccurrence[fingerprints[index]] != index
            if (duplicate || remaining < MIN_RESULT_TOKENS) continue
            val allowance = minOf(perResultBudget, remaining)
            resultBudgets[index] = allowance
            remaining = (remaining - allowance).coerceAtLeast(0)
        }

        val compacted = exchanges.map { exchange ->
            val compactedResults = exchange.results.map { result ->
                val index = resultIndex++
                val raw = render(result.content)
                val duplicate = lastOccurrence[fingerprints[index]] != index
                val maxChars = resultBudgets[index] * CHARS_PER_TOKEN
                when {
                    result.isError -> result.copy(content = ToolResultContent.Text(raw.take(240)))
                    duplicate -> result.copy(content = ToolResultContent.Text(DUPLICATE_RESULT))
                    resultBudgets[index] == 0 -> result.copy(content = ToolResultContent.Text(OMITTED_RESULT))
                    raw.length <= maxChars -> result
                    else -> result.copy(content = ToolResultContent.Text(compactText(raw, maxChars)))
                }
            }
            exchange.copy(results = compactedResults)
        }

        // Result compaction alone cannot bound replay when dozens of tool-call argument
        // objects accumulate. If metadata still exceeds the hard budget, retain complete
        // call/result pairs newest-first and evict older pairs. This makes maxReplayTokens
        // an actual upper bound instead of a warning threshold.
        return if (estimateTokens(compacted) <= hardBudget) compacted else hardBound(compacted, hardBudget)
    }

    private fun hardBound(exchanges: List<AgentToolExchange>, hardBudget: Int): List<AgentToolExchange> {
        var remaining = hardBudget
        val retained = mutableListOf<AgentToolExchange>()
        for (exchange in exchanges.asReversed()) {
            val resultByCallId = exchange.results.associateBy { it.callId }
            val keptPairs = mutableListOf<Pair<ProviderEvent.ToolCall, AgentToolResult>>()
            for (call in exchange.calls.asReversed()) {
                val result = resultByCallId[call.callId] ?: continue
                var keptCall = call
                var keptResult = result
                var cost = estimateTokens(listOf(AgentToolExchange(listOf(keptCall), listOf(keptResult))))
                if (cost > remaining) {
                    // Arguments and consumed payload are no longer needed to execute the call;
                    // preserving the id/name/result pairing is sufficient for provider replay.
                    keptCall = call.copy(arguments = buildJsonObject { })
                    keptResult = result.copy(content = ToolResultContent.Text(OMITTED_RESULT))
                    cost = estimateTokens(listOf(AgentToolExchange(listOf(keptCall), listOf(keptResult))))
                }
                if (cost <= remaining) {
                    keptPairs += keptCall to keptResult
                    remaining -= cost
                }
                if (remaining <= 0) break
            }
            if (keptPairs.isNotEmpty()) {
                val ordered = keptPairs.asReversed()
                retained += AgentToolExchange(ordered.map { it.first }, ordered.map { it.second })
            }
            if (remaining <= 0) break
        }
        return retained.asReversed()
    }

    fun estimateTokens(exchanges: List<AgentToolExchange>): Int =
        exchanges.sumOf { exchange ->
            exchange.calls.sumOf { ContextTokenEstimate.estimate(it.name + it.arguments.toString()) + 24 } +
                exchange.results.sumOf { ContextTokenEstimate.estimate(render(it.content)) + 24 }
        }

    private fun compactText(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val marker = "\n\n[Earlier tool result compacted after consumption.]\n\n"
        val available = (maxChars - marker.length).coerceAtLeast(64)
        val head = available * 2 / 3
        val tail = available - head
        return value.take(head) + marker + value.takeLast(tail)
    }

    private fun render(content: ToolResultContent): String = when (content) {
        is ToolResultContent.Text -> content.text
        is ToolResultContent.Json -> Json.encodeToString(content.value)
        is ToolResultContent.ResourceLinks -> Json.encodeToString(content.links.map { it.uri })
    }

    private fun fingerprint(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}

private object ContextTokenEstimate {
    fun estimate(text: String): Int = (text.toByteArray(StandardCharsets.UTF_8).size + 2) / 3
}
