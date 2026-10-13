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
data class AssistSpan(val id: String, val locator: String, val text: String)

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
        fun visit(node: JsonElement, path: String) {
            when (node) {
                is JsonObject -> node.entries.sortedBy { it.key }.forEach { (key, value) -> visit(value, "$path/${key.replace("~", "~0").replace("/", "~1")}") }
                is JsonArray -> node.forEachIndexed { index, value -> visit(value, "$path/$index") }
                is JsonPrimitive -> if (node.isString && node.content.length in 80..4000 && spans.size < 24) spans += AssistSpan(researchHash("$path|${node.content}").take(24), path, node.content)
            }
        }
        visit(source, "")
        return spans
    }
    fun select(source: List<AssistSpan>, ids: List<String>): List<AssistSpan>? {
        if (ids.isEmpty() || ids.size > 8 || ids.distinct().size != ids.size) return null
        val byId = source.associateBy { it.id }
        if (ids.any { it !in byId }) return null
        return ids.map { byId.getValue(it) }.takeIf { it.sumOf { span -> span.text.length } <= 4000 }
    }
    fun producerIds(content: ToolResultContent): Set<String> {
        val root = ToolResultEnvelope.element(content) as? JsonObject ?: return emptySet()
        val handoff = root["assistHandoff"] as? JsonObject ?: return emptySet()
        return setOfNotNull((handoff["producerInvocationId"] as? JsonPrimitive)?.content?.takeUnless { it == "null" || it.isBlank() })
    }
}
