package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class AssistSpan(val id: String, val locator: String, val text: String, val startUtf16: Int = 0, val endUtf16: Int = text.length)

@Serializable
data class AssistHandoff(
    val schemaVersion: Int = 1,
    val artifactId: String,
    val rootWorkId: String,
    val snapshotHash: String,
    val producerInvocationId: String?,
    val selectedIds: List<String>,
    val validation: String = "exact_source_selection",
    val limitation: String = "Partial selection; not independent verification. Full original evidence is retained."
)

/** Deterministic immutable source spans; helper text never substitutes for source bytes. */
internal object AssistHandoffContract {
    fun spans(source: JsonElement): List<AssistSpan> {
        val spans = mutableListOf<AssistSpan>()
        fun visit(node: JsonElement, path: String, depth: Int = 0) {
            require(depth <= 32) { "UNSUPPORTED_EVIDENCE_DEPTH" }
            when (node) {
                is JsonObject -> node.entries.sortedBy { it.key }.filterNot { it.key in setOf("error", "instructions", "system_prompt", "assistHandoff", "proposedQueries") }.forEach { (key, value) -> visit(value, "$path/${key.replace("~", "~0").replace("/", "~1")}", depth + 1) }
                is JsonArray -> node.forEachIndexed { index, value -> visit(value, "$path/$index", depth + 1) }
                is JsonPrimitive -> if (node.isString && node.content.isNotBlank()) {
                    val text = node.content
                    var start = 0
                    while (start < text.length) {
                        var end = minOf(text.length, start + 1600)
                        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                        val content = text.substring(start, end)
                        spans += AssistSpan(researchHash("$path|$start|$end|$content"), path, content, start, end)
                        start = end
                    }
                }
            }
        }
        visit(source, "")
        return spans
    }
    fun select(source: List<AssistSpan>, ids: List<String>): List<AssistSpan>? {
        if (ids.isEmpty() || ids.size > 8 || ids.distinct().size != ids.size) return null
        val byId = source.associateBy { it.id }
        if (ids.any { it !in byId }) return null
        return ids.map { byId.getValue(it) }
    }
    fun producerIds(result: dev.chungjungsoo.gptmobile.data.agent.AgentToolResult): Set<String> =
        producerIds(result.content) + result.assistMetadata.flatMap { metadata ->
            val handoffs = metadata["assistHandoffs"] as? JsonArray ?: JsonArray(listOfNotNull(metadata["assistHandoff"]))
            handoffs.mapNotNull { ((it as? JsonObject)?.get("producerInvocationId") as? JsonPrimitive)?.content?.takeUnless { value -> value == "null" || value.isBlank() } }
        }

    fun producerIds(content: ToolResultContent): Set<String> {
        val root = ToolResultEnvelope.element(content) as? JsonObject ?: return emptySet()
        val handoff = root["assistHandoff"] as? JsonObject ?: return emptySet()
        return setOfNotNull((handoff["producerInvocationId"] as? JsonPrimitive)?.content?.takeUnless { it == "null" || it.isBlank() })
    }
}
