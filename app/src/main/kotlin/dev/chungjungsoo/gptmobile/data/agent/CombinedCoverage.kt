package dev.chungjungsoo.gptmobile.data.agent

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
        val root = CombinedClaimInventoryWire.parseEnvelope(envelope)
        val contributions = (root?.get("contributions") as? JsonArray).orEmpty()
        val collected = mutableListOf<Item>()
        CombinedClaimInventoryWire.decode(root).forEach { row ->
            val text = (row["text"] as? JsonPrimitive)?.content.orEmpty()
            val id = (row["id"] as? JsonPrimitive)?.content.orEmpty()
            if (id.isNotBlank() && text.isNotBlank()) collected += Item(id, text, emptyList())
        }
        contributions.forEachIndexed { contributor, element ->
            val source = element as? JsonObject ?: return@forEachIndexed
            val response = (source["response"] as? JsonPrimitive)?.content.orEmpty()
            response.lineSequence().filter { it.isNotBlank() }.forEachIndexed { paragraph, text ->
                val urls = Regex("https?://[^\\s<>\\\"]+").findAll(text).map { it.value.trimEnd(')', '.', ',') }.toList()
                collected += Item("C${contributor + 1}:P${paragraph + 1}", text, urls)
            }
        }
        items = collected
        inventoryTruncated = false
    }

    fun manifest(answer: String, offset: Int = 0, pageSize: Int = Int.MAX_VALUE): JsonObject = buildJsonObject {
        require(offset >= 0 && pageSize > 0)
        val page = items.drop(offset).take(pageSize)
        put("totalItems", items.size)
        put("offset", offset)
        put("remainingItems", (items.size - offset - page.size).coerceAtLeast(0))
        put("verification", "TEXT_COVERAGE_HINTS_ONLY; external claims require supplied evidence")
        put("inventoryTruncated", inventoryTruncated)
        put(
            "items",
            JsonArray(
                page.map { item ->
                    buildJsonObject {
                        put("id", item.id)
                        put("status", if (answer.contains(item.text)) "EXACT_TEXT_PRESENT" else "UNVERIFIED_OR_PENDING")
                        put("sourceReferencesPresent", item.urls.isNotEmpty() && item.urls.all(answer::contains))
                        val references = item.urls
                        put("urls", JsonArray(references.map(::JsonPrimitive)))
                        put("omittedSourceReferenceCount", item.urls.size - references.size)
                    }
                }
            )
        )
    }

    fun pendingOutline(answer: String): String {
        val pending = items.filterNot { answer.contains(it.text) }
        return "Unverified inventory items: ${pending.size}; preview shows ${minOf(24, pending.size)}. Full inventory remains stored.\n" +
            pending.take(24).joinToString("\n") { "${it.id}: ${it.text.take(240)}" }
    }

    fun continuationContext(answer: String): String = buildString {
        appendLine("Committed headings (continue the same shared outline):")
        answer.lineSequence().filter { it.startsWith("#") }.take(64).forEach { appendLine(it.take(180)) }
        appendLine("Coverage checklist: candidates may already be paraphrased; audit before adding, preserve conflicts, do not duplicate or treat model text as verified evidence.")
        appendLine(pendingOutline(answer))
        appendLine("Last committed text (earlier sections remain saved; never restart):")
        append(answer.takeLast(12_000))
    }
}
