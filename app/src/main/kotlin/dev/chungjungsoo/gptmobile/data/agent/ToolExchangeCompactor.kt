package dev.chungjungsoo.gptmobile.data.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

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
    private const val CONSUMED_RESULT = "[Earlier tool result already consumed by the model; durable evidence retained.]"
    private const val DUPLICATE_RESULT = "[Earlier duplicate tool result omitted; newest identical result retained.]"

    /** Per-run identity cache: immutable result bodies are serialized once across rounds.
     * Bounded in both entries and characters; never shared between chats/accounts. */
    class RenderCache(private val maxCharacters: Int = 2 * 1024 * 1024, private val maxEntries: Int = 256) {
        private val values = java.util.IdentityHashMap<ToolResultContent, String>()
        private val collected = java.lang.ref.ReferenceQueue<ToolResultContent>()
        private val measurements = linkedMapOf<ContentIdentity, Measurement>()
        private var characters = 0
        init {
            require(maxCharacters >= 0 && maxEntries > 0)
        }

        // The small digest/count must not pin an oversized body after the runner
        // replaces it with a compacted observation. Equality remains identity-only;
        // structural JSON/string equality would repeat the expensive body scan.
        private class ContentIdentity(content: ToolResultContent, queue: java.lang.ref.ReferenceQueue<ToolResultContent>? = null) : java.lang.ref.WeakReference<ToolResultContent>(content, queue) {
            private val identity = System.identityHashCode(content)
            override fun hashCode(): Int = identity
            override fun equals(other: Any?): Boolean = this === other || other is ContentIdentity && get()?.let { it === other.get() } == true
        }

        private fun releaseCollected() {
            while (true) measurements.remove(collected.poll() ?: break)
        }

        internal fun measure(content: ToolResultContent): Measurement {
            releaseCollected()
            measurements[ContentIdentity(content)]?.let { return it }
            val measurement = measurement(render(content))
            if (measurements.size >= maxEntries) measurements.remove(measurements.keys.iterator().next())
            measurements[ContentIdentity(content, collected)] = measurement
            return measurement
        }

        internal fun render(content: ToolResultContent): String {
            values[content]?.let { return it }
            val rendered = ToolExchangeCompactor.render(content)
            if (rendered.length > maxCharacters / 2) return rendered
            while (values.isNotEmpty() && (characters + rendered.length > maxCharacters || values.size >= maxEntries)) {
                val entry = values.entries.iterator()
                characters -= entry.next().value.length
                entry.remove()
            }
            values[content] = rendered
            characters += rendered.length
            return rendered
        }
        internal fun retainedCharacters(): Int = characters
        internal fun retainedMeasurements(): Int {
            releaseCollected()
            return measurements.size
        }
        fun clear() {
            values.clear()
            measurements.clear()
            releaseCollected()
            characters = 0
        }
    }

    internal data class Measurement(val tokens: Int, val digest: String)

    private fun measurement(rendered: String): Measurement {
        val bytes = rendered.toByteArray(StandardCharsets.UTF_8)
        return Measurement(((bytes.size.toLong() + 2) / 3).toInt(), digest(bytes))
    }

    fun compact(
        exchanges: List<AgentToolExchange>,
        maxReplayTokens: Int,
        maxResultTokens: Int,
        consumedCallIds: Set<String> = emptySet(),
        renderCache: RenderCache? = null
    ): List<AgentToolExchange> {
        if (exchanges.isEmpty() || maxReplayTokens == Int.MAX_VALUE) return exchanges

        val hardBudget = maxReplayTokens.coerceAtLeast(1)
        val perResultBudget = maxResultTokens.coerceAtLeast(MIN_RESULT_TOKENS)
        val flattenedResults = exchanges.flatMap { it.results }
        // Counts and digests survive projections; render only results that receive
        // space in this round. The cache is private to this runner and bounded.
        val measurements = flattenedResults.map { renderCache?.measure(it.content) ?: measurement(render(it.content)) }
        var fingerprintIndex = 0
        val fingerprints = exchanges.flatMap { exchange ->
            val calls = exchange.calls.associateBy { it.callId }
            exchange.results.map { result ->
                val call = calls[result.callId]
                // A missing call is never deduplicated with an unrelated result.
                fingerprint("${call?.name ?: result.callId}|${call?.arguments}|${result.isError}|${result.errorCode}|${measurements[fingerprintIndex++].digest}")
            }
        }
        val lastOccurrence = fingerprints.withIndex().associate { (index, value) -> value to index }
        // A completed provider round proves that these exact bodies were already
        // delivered. Keep their call/result boundary, error metadata, and any
        // bounded semantic summary, but do not keep paying for a large raw body on
        // every later round. Durable evidence remains in the checkpoint/evidence
        // stores and is independent from this provider replay projection.
        val consumedPayloads = BooleanArray(flattenedResults.size) { index ->
            flattenedResults[index].callId in consumedCallIds && measurements[index].tokens > MIN_RESULT_TOKENS
        }
        var resultIndex = 0
        var remaining = hardBudget

        // Old call arguments must not consume every token before the newest
        // result is admitted. hardBound removes older complete pairs when needed.
        val metadataCost = exchanges.sumOf { exchange ->
            exchange.calls.sumOf { ContextTokenEstimate.estimate(it.name + it.arguments.toString()).toLong() + 24 }
        }
        remaining = (remaining - minOf(metadataCost, hardBudget / 2L).toInt()).coerceAtLeast(0)

        // Spend replay allowance newest-first. Once exhausted, older consumed
        // payloads become tiny placeholders instead of each receiving a minimum
        // slice that can silently grow the next provider request past budget.
        val resultBudgets = IntArray(fingerprints.size)
        for (index in fingerprints.indices.reversed()) {
            val duplicate = lastOccurrence[fingerprints[index]] != index
            if (duplicate || consumedPayloads[index] || remaining < MIN_RESULT_TOKENS) continue
            val actualCost = measurements[index].tokens.coerceAtLeast(1)
            val allowance = minOf(perResultBudget, remaining, actualCost)
            resultBudgets[index] = allowance
            // Small successful results no longer reserve an entire 512/800-token slice.
            remaining = (remaining - allowance - 24).coerceAtLeast(0)
        }

        val compacted = exchanges.mapIndexed { exchangeIndex, exchange ->
            val compactedResults = exchange.results.map { result ->
                val index = resultIndex++
                val duplicate = lastOccurrence[fingerprints[index]] != index
                val maxChars = resultBudgets[index] * CHARS_PER_TOKEN
                val raw by lazy(LazyThreadSafetyMode.NONE) { renderCache?.render(result.content) ?: render(result.content) }
                val semantic by lazy(LazyThreadSafetyMode.NONE) { semanticContent(result) }
                when {
                    duplicate -> result.copy(content = ToolResultContent.Text(DUPLICATE_RESULT))
                    consumedPayloads[index] -> result.copy(content = semantic ?: ToolResultContent.Text(CONSUMED_RESULT))
                    resultBudgets[index] == 0 -> result.copy(content = semantic ?: ToolResultContent.Text(OMITTED_RESULT))
                    result.executionReceipt?.get("retained_original_complete") == JsonPrimitive(true) && raw.length > maxChars && semantic != null && render(requireNotNull(semantic)).length <= maxChars ->
                        result.copy(content = requireNotNull(semantic))
                    GitHubReplayRecovery.envelope(result.content) != null ->
                        if (raw.length <= maxChars) result else result.copy(content = GitHubReplayRecovery.compact(result.content, maxChars, result.callId in consumedCallIds) ?: result.content)
                    result.isError && exchangeIndex < exchanges.lastIndex ->
                        result.copy(content = ToolResultContent.Text(compactText(raw, minOf(maxChars, 480))))
                    recoveryPage(result.content) != null -> result.copy(content = compactRecoveryPage(requireNotNull(recoveryPage(result.content)), maxChars))
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
        return if (estimateTokens(compacted, renderCache) <= hardBudget) compacted else hardBound(compacted, hardBudget, renderCache)
    }

    /** Small immutable facts survive projection; the hard bound below still governs replay size. */
    private fun semanticContent(result: AgentToolResult): ToolResultContent? {
        val source = GitHubReplayRecovery.envelope(result.content)
        val summary = (result.executionReceipt?.get("semantic_summary") ?: source?.get("semantic_summary")) as? JsonObject ?: return null
        if (summary.toString().length > 2400) return null
        return ToolResultContent.Json(
            buildJsonObject {
                put("schema", JsonPrimitive("github.evidence-summary.v1"))
                put("semantic_summary", summary)
                put("receipt_reference", JsonPrimitive("tool-batch/${result.callId}"))
                put("previously_delivered", JsonPrimitive(true))
                result.executionReceipt?.get("retained_original_complete")?.let { put("retained_original_complete", it) }
            }
        )
    }

    private fun recoveryPage(content: ToolResultContent): JsonObject? =
        (content as? ToolResultContent.Json)?.value?.let { it as? JsonObject }?.takeIf {
            it["saved_work"] is JsonPrimitive && it["corpus_revision"] != null && it["next_offset"] != null
        }

    /** Keep the exact unread offset when compacting a saved page; never skip its omitted tail. */
    private fun compactRecoveryPage(page: JsonObject, maxCharacters: Int): ToolResultContent {
        if (page.toString().length <= maxCharacters) return ToolResultContent.Json(page)
        val text = (page["saved_work"] as JsonPrimitive).content
        val offset = (page["offset"] as? JsonPrimitive)?.intOrNull ?: return ToolResultContent.Json(page)
        val metadata = page - "saved_work"
        var length = minOf(text.length, (maxCharacters - JsonObject(metadata).toString().length - 100).coerceAtLeast(0))
        while (true) {
            if (length in 1 until text.length && text[length - 1].isHighSurrogate() && text[length].isLowSurrogate()) length--
            val compact = JsonObject(
                metadata + mapOf(
                    "saved_work" to JsonPrimitive(text.take(length)),
                    "next_offset" to JsonPrimitive(offset + length),
                    "complete" to JsonPrimitive(false),
                    "replay_truncated" to JsonPrimitive(true)
                )
            )
            if (compact.toString().length <= maxCharacters || length == 0) return ToolResultContent.Json(compact)
            length /= 2
        }
    }

    private fun hardBound(exchanges: List<AgentToolExchange>, hardBudget: Int, renderCache: RenderCache?): List<AgentToolExchange> {
        var remaining = hardBudget
        val retained = mutableListOf<AgentToolExchange>()
        for (exchange in exchanges.asReversed()) {
            val resultByCallId = exchange.results.associateBy { it.callId }
            val keptPairs = mutableListOf<Pair<ProviderEvent.ToolCall, AgentToolResult>>()
            for (call in exchange.calls.asReversed()) {
                val result = resultByCallId[call.callId] ?: continue
                var keptCall = call
                var keptResult = result
                var cost = estimateTokens(listOf(AgentToolExchange(listOf(keptCall), listOf(keptResult))), renderCache)
                if (cost > remaining) {
                    // Arguments and consumed payload are no longer needed to execute the call;
                    // preserving the id/name/result pairing is sufficient for provider replay.
                    keptCall = call.copy(arguments = buildJsonObject { }, originalInvocationFingerprint = ToolHandoffReceipt.fingerprint(call))
                    keptResult = result.copy(content = semanticContent(result) ?: ToolResultContent.Text(OMITTED_RESULT))
                    cost = estimateTokens(listOf(AgentToolExchange(listOf(keptCall), listOf(keptResult))), renderCache)
                    if (cost > remaining) {
                        keptResult = result.copy(content = ToolResultContent.Text(OMITTED_RESULT))
                        cost = estimateTokens(listOf(AgentToolExchange(listOf(keptCall), listOf(keptResult))), renderCache)
                    }
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

    fun estimateTokens(exchanges: List<AgentToolExchange>, renderCache: RenderCache? = null): Int =
        exchanges.sumOf { exchange ->
            exchange.calls.sumOf { ContextTokenEstimate.estimate(it.name + it.arguments.toString()).toLong() + 24 } +
                exchange.results.sumOf { (renderCache?.measure(it.content)?.tokens ?: ContextTokenEstimate.estimate(render(it.content))).toLong() + 24 }
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

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

    private fun fingerprint(value: String): String = digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        "0123456789abcdef"[value ushr 4].toString() + "0123456789abcdef"[value and 0xf]
    }
}

private object ContextTokenEstimate {
    fun estimate(text: String): Int = ((text.toByteArray(StandardCharsets.UTF_8).size.toLong() + 2) / 3).toInt()
}
