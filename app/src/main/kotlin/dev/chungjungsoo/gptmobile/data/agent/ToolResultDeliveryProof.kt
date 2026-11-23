package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.serialization.json.JsonPrimitive

/** Consumption of a call does not prove which of its replay projections was delivered. */
internal object ToolResultDeliveryProof {
    const val MAX_RESULTS = 1024
    private val sha256 = Regex("[a-f0-9]{64}")

    fun valid(digest: String): Boolean = sha256.matches(digest)

    fun digest(content: ToolResultContent, cache: ToolExchangeCompactor.RenderCache? = null): String =
        if (cache != null && content !is ToolResultContent.ResourceLinks) cache.measure(content).digest else GitHubEvidenceBook.digest(ToolHandoffReceipt.payload(content))

    fun capture(results: Collection<AgentToolResult>, cache: ToolExchangeCompactor.RenderCache? = null): Map<String, String> = buildMap {
        for (result in results) {
            if (!successful(result)) continue
            put(result.callId, digest(result.content, cache))
            while (size > MAX_RESULTS) remove(keys.first())
        }
    }

    /** Only the exact delivered projection may authorize consumption during replay. */
    fun qualifiedConsumed(exchanges: List<AgentToolExchange>, consumed: Set<String>, delivered: Map<String, String>, cache: ToolExchangeCompactor.RenderCache? = null): Set<String> = exchanges
        .flatMap { it.results }
        .filter { result ->
            if (result.callId !in consumed || !matches(result, delivered, cache)) return@filter false
            val body = GitHubReplayRecovery.envelope(result.content)
            // A delivered recovery wrapper still represents omitted source. Keep
            // its same-offset recovery available instead of treating it as full content.
            body?.get("schema") != JsonPrimitive("github.repeat.v1") &&
                body?.get("replay_compacted") != JsonPrimitive(true) &&
                body?.get("observationTruncated") != JsonPrimitive(true)
        }.mapTo(mutableSetOf()) { it.callId }

    fun matches(result: AgentToolResult, delivered: Map<String, String>, cache: ToolExchangeCompactor.RenderCache? = null): Boolean =
        successful(result) && delivered[result.callId]?.let { valid(it) && it == digest(result.content, cache) } == true

    private fun successful(result: AgentToolResult): Boolean = !result.isError &&
        result.errorCode !in ToolResultEnvelope.unknownOutcomeCodes &&
        result.executionReceipt?.get("requires_reconciliation") != JsonPrimitive(true)
}
