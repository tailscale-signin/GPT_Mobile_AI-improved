package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceRelevance
import dev.chungjungsoo.gptmobile.data.github.GitHubSemanticSummary
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
    private const val OMITTED_RESULT = "[Tool result omitted from primary replay to fit its budget.]"
    private const val CONSUMED_RESULT = "[Earlier tool result already consumed by the model; durable evidence retained.]"
    private const val DUPLICATE_RESULT = "[Duplicate tool result omitted; identical observation retained.]"

    internal fun isReplayPlaceholder(content: ToolResultContent): Boolean = (content as? ToolResultContent.Text)?.text in setOf(OMITTED_RESULT, CONSUMED_RESULT, DUPLICATE_RESULT)

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

    /**
     * Project result bodies for a Gateway handoff without evicting any call/result
     * row or rewriting original invocation identity. Owed raw saved pages must be
     * admitted separately; a summary is never proof of their delivery.
     */
    fun compactProtocol(exchanges: List<AgentToolExchange>, maxReplayTokens: Int, maxResultTokens: Int): List<AgentToolExchange> {
        if (exchanges.isEmpty()) return exchanges
        val calls = exchanges.flatMap { it.calls }
        val results = exchanges.flatMap { it.results }
        require(calls.map { it.callId }.distinct().size == calls.size && results.map { it.callId }.distinct().size == results.size && results.map { it.callId }.toSet() == calls.map { it.callId }.toSet()) {
            "Pending protocol must contain unique paired calls and results."
        }
        if (estimateTokens(exchanges) <= maxReplayTokens) return exchanges
        val grouped = compact(listOf(AgentToolExchange(calls, results)), maxReplayTokens, maxResultTokens).single()
        val projected = grouped.results.associateBy { it.callId }
        return exchanges.map { exchange ->
            exchange.copy(
                results = exchange.results.map { original ->
                    val result = projected.getValue(original.callId)
                    if (!original.isError && original.executionReceipt?.get("saved_view_cursor_issued") == JsonPrimitive(true) && original.executionReceipt["saved_view_body_bytes"] != null) {
                        // This native page may still be owed to the model. A protocol
                        // projection is never a delivery acknowledgement; admission
                        // must refuse or privately read an oversized exact page.
                        original
                    } else if (result.content == original.content) {
                        result
                    } else {
                        result.copy(
                            content = ToolResultContent.Json(
                                buildJsonObject {
                                    put("schema", JsonPrimitive("github.protocol-result.v1"))
                                    put("receipt_reference", JsonPrimitive("tool-batch/${result.callId}"))
                                    put("is_error", JsonPrimitive(result.isError))
                                    result.errorCode?.let { put("error_code", JsonPrimitive(it)) }
                                    val sourceDigest = (original.executionReceipt?.get("source_result_sha256") as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                                    put("source_result_sha256", JsonPrimitive(sourceDigest ?: measurement(render(original.content)).digest))
                                    put("projection_only", JsonPrimitive(true))
                                    put("content", (result.content as? ToolResultContent.Json)?.value ?: JsonPrimitive(render(result.content)))
                                }
                            )
                        )
                    }
                }
            )
        }
    }

    fun compact(
        exchanges: List<AgentToolExchange>,
        maxReplayTokens: Int,
        maxResultTokens: Int,
        consumedCallIds: Set<String> = emptySet(),
        renderCache: RenderCache? = null,
        resultTokenAllowances: Map<String, Int> = emptyMap(),
        taskRequest: String? = null,
        priorityCallIds: Set<String> = emptySet(),
        requiredSavedPageDigests: Map<String, String?> = emptyMap()
    ): List<AgentToolExchange> {
        if (exchanges.isEmpty() || maxReplayTokens == Int.MAX_VALUE) return exchanges

        val hardBudget = maxReplayTokens.coerceAtLeast(1)
        val perResultBudget = maxResultTokens.coerceAtLeast(MIN_RESULT_TOKENS)
        val flattenedResults = exchanges.flatMap { it.results }
        // Counts and digests survive projections; render only results that receive
        // space in this round. The cache is private to this runner and bounded.
        val measurements = flattenedResults.map { renderCache?.measure(it.content) ?: measurement(render(it.content)) }
        // A different call can return identical bytes. Delivery still needs the admitted call's own body.
        val requiredBodies = if (requiredSavedPageDigests.isEmpty()) {
            emptySet()
        } else {
            flattenedResults.indices.filterTo(mutableSetOf()) { index ->
                val id = flattenedResults[index].callId
                id in resultTokenAllowances && requiredSavedPageDigests[id] == measurements[index].digest
            }
        }
        val metadataCost = exchanges.sumOf { exchange ->
            exchange.calls.sumOf { call -> ContextTokenEstimate.estimate((call.originalInvocation?.name ?: call.name) + (call.originalInvocation?.arguments ?: call.arguments).toString()).toLong() + 24 }
        }
        val needsRanking = metadataCost + measurements.sumOf { it.tokens.toLong() + 24 } > hardBudget
        val relevance = taskRequest?.takeIf { needsRanking && it.isNotBlank() }?.let(::GitHubEvidenceRelevance)
        val scores = mutableMapOf<String, Int>()
        val pendingIndex = exchanges.indexOfLast { !SavedViewRecoveryBatch.isComplete(it) }.takeIf { it >= 0 } ?: exchanges.lastIndex
        val pendingIds = exchanges[pendingIndex].calls.mapTo(mutableSetOf()) { it.callId }
        var fingerprintIndex = 0
        val fingerprints = exchanges.flatMap { exchange ->
            val calls = exchange.calls.associateBy { it.callId }
            exchange.results.map { result ->
                val call = calls[result.callId]
                if (relevance != null) {
                    val projected = (result.content as? ToolResultContent.Json)?.value as? JsonObject
                    val summary = (result.executionReceipt?.get("semantic_summary") ?: projected?.get("semantic_summary")) as? JsonObject
                    scores[result.callId] = relevance.score(call?.arguments, summary)
                }
                // hardBound reorders complete call/result pairs after result allocation.
                // Carry the same priority into that pass when only one saved page fits.
                when {
                    fingerprintIndex in requiredBodies -> scores[result.callId] = Int.MAX_VALUE
                    result.callId in resultTokenAllowances -> scores[result.callId] = Int.MAX_VALUE - 1
                    result.callId in priorityCallIds -> scores[result.callId] = Int.MAX_VALUE - 2
                    GitHubContinuationMemory.control(result) != null -> scores[result.callId] = Int.MAX_VALUE - 3
                }
                // A missing call is never deduplicated with an unrelated result.
                fingerprint("${call?.name ?: result.callId}|${call?.arguments}|${result.isError}|${result.errorCode}|${measurements[fingerprintIndex++].digest}")
            }
        }

        // Prefer the pending handoff, a reserved saved page, or a specifically cited original.
        // Otherwise retain the most recent identical call as before.
        fun retentionPriority(index: Int) = if (index in requiredBodies) {
            4
        } else {
            when (flattenedResults[index].callId) {
                in pendingIds -> 3
                in resultTokenAllowances -> 2
                in priorityCallIds -> 1
                else -> 0
            }
        }
        val retainedOccurrence = mutableMapOf<String, Int>()
        for ((index, key) in fingerprints.withIndex()) {
            val previous = retainedOccurrence[key]
            if (previous == null || retentionPriority(index) >= retentionPriority(previous)) {
                retainedOccurrence[key] = index
            }
        }
        // A completed provider round proves that these exact bodies were already
        // delivered. Keep their call/result boundary, error metadata, and any
        // bounded semantic summary, but do not keep paying for a large raw body on
        // every later round. Complete GitHub pages stay eligible for the bounded
        // replay allowance so follow-up rounds can use their exact evidence.
        val consumedPayloads = BooleanArray(flattenedResults.size) { index ->
            val result = flattenedResults[index]
            result.callId in consumedCallIds &&
                result.callId !in priorityCallIds &&
                measurements[index].tokens > MIN_RESULT_TOKENS &&
                GitHubReplayRecovery.envelope(result.content)?.get("local_page_complete") != JsonPrimitive(true)
        }
        var resultIndex = 0
        var remaining = hardBudget

        // Old call arguments must not consume every token before the newest
        // result is admitted. hardBound removes older complete pairs when needed.
        val largestAdmittedResult = flattenedResults.filter { it.callId !in consumedCallIds }
            .maxOfOrNull { resultTokenAllowances[it.callId]?.coerceIn(0, hardBudget) ?: 0 } ?: 0
        val metadataReserve = if (largestAdmittedResult == 0) hardBudget / 2L else minOf(hardBudget / 2L, (hardBudget.toLong() - largestAdmittedResult - 24).coerceAtLeast(0))
        remaining = (remaining - minOf(metadataCost, metadataReserve).toInt()).coerceAtLeast(0)

        // Keep saved-page reservations and the active handoff first. Among historical
        // observations, spend the same fixed budget on task matches, with recency breaking ties.
        val resultBudgets = IntArray(fingerprints.size)
        val newestFirst = fingerprints.indices.reversed()
        val order = if (scores.isEmpty() && resultTokenAllowances.isEmpty() && priorityCallIds.isEmpty()) {
            newestFirst.toList()
        } else {
            newestFirst.sortedWith(
                compareByDescending<Int> { it in requiredBodies }
                    .thenByDescending { flattenedResults[it].callId in resultTokenAllowances }
                    .thenByDescending { flattenedResults[it].callId in pendingIds }
                    .thenByDescending { scores[flattenedResults[it].callId] ?: 0 }
            )
        }
        for (index in order) {
            val duplicate = index !in requiredBodies && retainedOccurrence[fingerprints[index]] != index
            if (duplicate || consumedPayloads[index] || remaining < MIN_RESULT_TOKENS) continue
            val actualCost = measurements[index].tokens.coerceAtLeast(1)
            val admittedResult = resultTokenAllowances[flattenedResults[index].callId]?.coerceIn(0, hardBudget) ?: perResultBudget
            val allowance = minOf(maxOf(perResultBudget, admittedResult), remaining, actualCost)
            resultBudgets[index] = allowance
            // Small successful results no longer reserve an entire 512/800-token slice.
            remaining = (remaining - allowance - 24).coerceAtLeast(0)
        }

        val compacted = exchanges.mapIndexed { exchangeIndex, exchange ->
            val compactedResults = exchange.results.map { result ->
                val index = resultIndex++
                val duplicate = index !in requiredBodies && retainedOccurrence[fingerprints[index]] != index
                val maxChars = resultBudgets[index] * CHARS_PER_TOKEN
                val raw by lazy(LazyThreadSafetyMode.NONE) { renderCache?.render(result.content) ?: render(result.content) }
                val semantic by lazy(LazyThreadSafetyMode.NONE) { semanticContent(result, result.callId in consumedCallIds) }
                val summaryProjection = ((result.content as? ToolResultContent.Json)?.value as? JsonObject)?.get("schema") == JsonPrimitive("github.evidence-summary.v1")
                when {
                    index in requiredBodies && resultBudgets[index] >= measurements[index].tokens -> result
                    duplicate -> result.copy(content = ToolResultContent.Text(DUPLICATE_RESULT))
                    summaryProjection && semantic != null -> result.copy(content = requireNotNull(semantic))
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
        // call/result pairs in relevance order. The latest real handoff
        // remains exact; an irreducible pending batch must fail replay admission instead
        // of changing its arguments or losing a terminal result.
        return if (estimateTokens(compacted, renderCache) <= hardBudget) compacted else hardBound(compacted, hardBudget, consumedCallIds, renderCache, pendingIndex, scores)
    }

    /** Small immutable facts survive projection; the hard bound below still governs replay size. */
    private fun semanticContent(result: AgentToolResult, consumed: Boolean): ToolResultContent? {
        GitHubContinuationMemory.control(result)?.let { return ToolResultContent.Json(it) }
        val source = GitHubReplayRecovery.envelope(result.content) ?: (result.content as? ToolResultContent.Json)?.value as? JsonObject
        val summary = (result.executionReceipt?.get("semantic_summary") ?: source?.get("semantic_summary")) as? JsonObject ?: return null
        // Retaining a source or rendering its summary is not proof of model delivery.
        // The runner checkpoints consumption only after a successful provider round
        // received the exact body. A delivered summary must not become a full-source
        // delivery claim on a later projection or after process restoration.
        val deliveryScope = when {
            !consumed -> "UNCONFIRMED"
            source?.get("schema") != JsonPrimitive("github.evidence-summary.v1") -> "FULL_RESULT"
            source["prior_delivery_scope"] == JsonPrimitive("FULL_RESULT") -> "FULL_RESULT"
            else -> "SEMANTIC_SUMMARY"
        }
        return ToolResultContent.Json(
            buildJsonObject {
                put("schema", JsonPrimitive("github.evidence-summary.v1"))
                put("semantic_summary", GitHubSemanticSummary.bounded(summary))
                put("receipt_reference", JsonPrimitive("tool-batch/${result.callId}"))
                put("previously_delivered", JsonPrimitive(consumed))
                put("prior_delivery_scope", JsonPrimitive(deliveryScope))
                (result.executionReceipt?.get("retained_original_complete") ?: source?.get("retained_original_complete"))?.let { put("retained_original_complete", it) }
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

    private fun hardBound(exchanges: List<AgentToolExchange>, hardBudget: Int, consumedCallIds: Set<String>, renderCache: RenderCache?, pendingIndex: Int, scores: Map<String, Int>): List<AgentToolExchange> {
        // Automatic saved-view reads may follow a still-pending provider batch.
        // Preserve that whole batch before spending space on local or older history.
        var pending = exchanges[pendingIndex]
        if (estimateTokens(listOf(pending), renderCache) > hardBudget) {
            pending = pending.copy(results = pending.results.map { result -> result.copy(content = semanticContent(result, result.callId in consumedCallIds) ?: ToolResultContent.Text(OMITTED_RESULT)) })
        }
        if (estimateTokens(listOf(pending), renderCache) > hardBudget) {
            pending = pending.copy(results = pending.results.map { it.copy(content = ToolResultContent.Text(OMITTED_RESULT)) })
        }
        val pendingCost = estimateTokens(listOf(pending), renderCache)
        if (pendingCost > hardBudget) return listOf(pending)
        var remaining = hardBudget - pendingCost
        val retained = mutableMapOf(pendingIndex to pending)
        val historicalCalls = exchanges.indices.reversed().filter { it != pendingIndex }.flatMap { index ->
            val results = exchanges[index].results.associateBy { it.callId }
            exchanges[index].calls.asReversed().mapNotNull { call -> results[call.callId]?.let { Triple(index, call, it) } }
        }
        val kept = mutableMapOf<Int, MutableMap<String, AgentToolResult>>()
        val orderedCalls = if (scores.isEmpty()) historicalCalls else historicalCalls.sortedByDescending { scores[it.second.callId] ?: 0 }
        for ((index, call, result) in orderedCalls) {
            var keptResult = result
            var cost = estimateTokens(listOf(AgentToolExchange(listOf(call), listOf(keptResult))), renderCache)
            if (cost > remaining) {
                // A fingerprint field does not replace the original wire arguments.
                // Shrink result bodies or evict whole historical pairs instead.
                keptResult = result.copy(content = semanticContent(result, result.callId in consumedCallIds) ?: ToolResultContent.Text(OMITTED_RESULT))
                cost = estimateTokens(listOf(AgentToolExchange(listOf(call), listOf(keptResult))), renderCache)
                if (cost > remaining) {
                    keptResult = result.copy(content = ToolResultContent.Text(OMITTED_RESULT))
                    cost = estimateTokens(listOf(AgentToolExchange(listOf(call), listOf(keptResult))), renderCache)
                }
            }
            if (cost <= remaining) {
                kept.getOrPut(index) { mutableMapOf() }[call.callId] = keptResult
                remaining -= cost
            }
            if (remaining <= 0) break
        }
        for ((index, results) in kept) {
            val calls = exchanges[index].calls.filter { it.callId in results }
            retained[index] = AgentToolExchange(calls, calls.map { results.getValue(it.callId) })
        }
        return retained.toSortedMap().values.toList()
    }

    fun estimateTokens(exchanges: List<AgentToolExchange>, renderCache: RenderCache? = null): Int =
        exchanges.sumOf { exchange ->
            exchange.calls.sumOf { call -> ContextTokenEstimate.estimate((call.originalInvocation?.name ?: call.name) + (call.originalInvocation?.arguments ?: call.arguments).toString()).toLong() + 24 } +
                exchange.results.sumOf { (renderCache?.measure(it.content)?.tokens ?: ContextTokenEstimate.estimate(render(it.content))).toLong() + 24 }
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun compactText(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val marker = "\n\n[Tool result shortened to fit the replay budget.]\n\n"
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
