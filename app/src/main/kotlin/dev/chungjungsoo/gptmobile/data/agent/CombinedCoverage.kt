package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Deterministic coverage hints, never a claim verifier. The original contributions remain authoritative. */
internal class CombinedCoverage(envelope: String) {
    private data class Item(val id: String, val text: String, val urls: List<String>)
    private val items: List<Item>
    val inventoryTruncated: Boolean
    init {
        val contributions = runCatching { (Json.parseToJsonElement(envelope) as? JsonObject)?.get("contributions") as? JsonArray }.getOrNull().orEmpty()
        val collected = mutableListOf<Item>()
        var omitted = false
        contributions.forEachIndexed { contributor, element ->
            val source = element as? JsonObject ?: return@forEachIndexed
            val response = (source["response"] as? JsonPrimitive)?.content.orEmpty()
            response.lineSequence().filter { it.isNotBlank() }.forEachIndexed { paragraph, text ->
                if (collected.size >= 256) {
                    omitted = true
                } else {
                    val urls = Regex("https?://[^\\s<>\\\"]+").findAll(text).take(12).map { it.value.trimEnd(')', '.', ',') }.toList()
                    collected += Item("C${contributor + 1}:P${paragraph + 1}", text.take(1200), urls)
                    if (text.length > 1200) omitted = true
                }
            }
        }
        items = collected
        inventoryTruncated = omitted
    }

    fun manifest(answer: String): JsonObject = buildJsonObject {
        put("verification", "TEXT_COVERAGE_HINTS_ONLY; external claims require supplied evidence")
        put("inventoryTruncated", inventoryTruncated)
        put(
            "items",
            JsonArray(
                items.map { item ->
                    buildJsonObject {
                        put("id", item.id)
                        put("status", if (answer.contains(item.text)) "EXACT_TEXT_PRESENT" else "UNVERIFIED_OR_PENDING")
                        put("sourceReferencesPresent", item.urls.isNotEmpty() && item.urls.all(answer::contains))
                        val references = item.urls.filter { it.length <= 160 }.take(2)
                        put("urls", JsonArray(references.map(::JsonPrimitive)))
                        put("omittedSourceReferenceCount", item.urls.size - references.size)
                    }
                }
            )
        )
    }

    fun pendingOutline(answer: String): String = items.asSequence().filterNot { answer.contains(it.text) }
        .take(24).joinToString("\n") { "${it.id}: ${it.text.take(240)}" }.take(7000)

    fun continuationContext(answer: String): String = buildString {
        appendLine("Committed headings (continue the same shared outline):")
        answer.lineSequence().filter { it.startsWith("#") }.take(64).forEach { appendLine(it.take(180)) }
        appendLine("Coverage checklist: candidates may already be paraphrased; audit before adding, preserve conflicts, do not duplicate or treat model text as verified evidence.")
        appendLine(pendingOutline(answer))
        appendLine("Last committed text (earlier sections remain saved; never restart):")
        append(answer.takeLast(12_000))
    }
}
