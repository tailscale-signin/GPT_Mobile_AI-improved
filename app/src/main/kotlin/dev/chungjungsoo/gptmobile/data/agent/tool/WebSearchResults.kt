package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Handles structured MCP content, JSON text blocks, and the providers' labeled text/YAML. */
internal fun parseSearchPayload(text: String): JsonElement {
    runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { return it }
    // Brave returns one compact JSON object per MCP text block.
    val jsonLines = text.lineSequence().mapNotNull { line ->
        runCatching { Json.parseToJsonElement(line) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
    }.toList()
    return JsonArray(jsonLines + labeledSearchSources(text))
}

/** Preserve provider diagnostics through the MCP text/structured envelope. */
internal fun searchProviderPayload(value: JsonElement?, depth: Int = 0): JsonObject? {
    if (depth > 8) return null
    return when (value) {
        is JsonObject -> searchProviderPayload(value["structuredContent"], depth + 1)
            ?: value.takeIf { it["results"] is JsonArray || it["sources"] is JsonArray }
            ?: listOf("result", "data", "text", "content").firstNotNullOfOrNull { searchProviderPayload(value[it], depth + 1) }
        is JsonArray -> value.firstNotNullOfOrNull { searchProviderPayload(it, depth + 1) }
        is JsonPrimitive -> if (value.isString) runCatching { Json.parseToJsonElement(value.content) }.getOrNull()?.let { searchProviderPayload(it, depth + 1) } else null
        else -> null
    }
}

internal fun extractSearchSources(value: JsonElement?, depth: Int = 0): List<JsonObject> {
    if (depth > 8) return emptyList()
    return when (value) {
        is JsonArray -> value.flatMap { extractSearchSources(it, depth + 1) }
        is JsonObject -> {
            if (value.stringValue("status") in setOf("unavailable", "failed", "error")) return emptyList()
            val url = value.stringValue("url", "link", "uri")
            if (url != null) {
                listOf(
                    buildJsonObject {
                        put("url", url)
                        put("title", value.stringValue("title", "name") ?: url)
                        val highlights = (value["highlights"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("\n")
                        put("snippet", value.stringValue("snippet", "description", "content", "text", "raw_content") ?: highlights.orEmpty())
                        value.stringValue("publishedDate", "published_date", "published_at", "date")?.let { put("publishedDate", it) }
                        value.stringValue("engine")?.let { put("engine", it) }
                        (value["engines"] as? JsonArray)?.let { put("engines", it) }
                        for (key in listOf("similarSources", "similarSourceCount", "publisher", "language", "date_source", "published_age", "retrieved_at")) value[key]?.let { put(key, it) }
                    }
                )
            } else {
                listOf("sources", "results", "result", "data", "web", "organic", "organic_results", "search_results", "items", "content", "text", "structuredContent", "resourceLinks", "engines", "detail")
                    .flatMap { extractSearchSources(value[it], depth + 1) }
            }
        }
        is JsonPrimitive -> if (value.isString) extractSearchSources(parseSearchPayload(value.content), depth + 1) else emptyList()
        else -> emptyList()
    }
}

private fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
}

private val sourceField = Regex("^(?:\\[\\d+]\\s*)?([A-Za-z][A-Za-z ]*):\\s*(.*)$")
private val markdownSource = Regex("^\\s*(?:#{1,6}\\s+|\\d+\\.\\s+|[-*]\\s+)?\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)")
private val resultHeading = Regex("^### \\d+\\. (.+)$")
private val boldField = Regex("^\\*\\*([A-Za-z ]+:)\\*\\*\\s*")

private fun labeledSearchSources(text: String): List<JsonObject> {
    val results = mutableListOf<JsonObject>()
    val fields = linkedMapOf<String, String>()
    var activeField: String? = null
    fun finish() {
        if (!fields["url"].isNullOrBlank()) results += JsonObject(fields.mapValues { JsonPrimitive(it.value.trim()) })
        fields.clear()
        activeField = null
    }
    for (line in text.lineSequence()) {
        // Tavily image results are a separate section, not web sources.
        if (line == "Images:") break
        if (line.trim() == "---") {
            finish()
            continue
        }
        val heading = resultHeading.matchEntire(line)
        if (heading != null) {
            finish()
            fields["title"] = heading.groupValues[1]
            continue
        }
        val match = sourceField.matchEntire(line.replace(boldField, "$1 "))
        if (match != null) {
            val key = when (match.groupValues[1].lowercase()) {
                "title" -> "title"
                "url", "url source", "link" -> "url"
                "description", "snippet", "content", "content snippet", "text", "highlights" -> "snippet"
                "published", "published date", "publisheddate" -> "publishedDate"
                else -> null
            }
            if ((key == "title" && "title" in fields && "url" in fields) || (key == "url" && "url" in fields)) finish()
            activeField = if (key == "url" || key == "publishedDate") "snippet" else key
            if (key != null) fields[key] = unquoteSearchScalar(match.groupValues[2])
        } else {
            val link = markdownSource.find(line)
            if (link != null) {
                finish()
                fields["title"] = link.groupValues[1]
                fields["url"] = link.groupValues[2]
                activeField = "snippet"
            } else {
                activeField?.let { key -> fields[key] = listOf(fields[key].orEmpty(), line.trim()).filter { it.isNotEmpty() }.joinToString("\n") }
            }
        }
    }
    finish()
    return results
}

private fun unquoteSearchScalar(value: String): String {
    val text = value.trim()
    if (text in setOf("|", "|-", "|+", ">", ">-", ">+")) return ""
    if (text.startsWith('"') && text.endsWith('"')) {
        return (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonPrimitive)?.content ?: text.removeSurrounding("\"")
    }
    if (text.startsWith('\'') && text.endsWith('\'')) return text.removeSurrounding("'").replace("''", "'")
    return text
}
