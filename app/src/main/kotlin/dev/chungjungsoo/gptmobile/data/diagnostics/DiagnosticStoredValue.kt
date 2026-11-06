package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.chat.ArchivedTextCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One projection is shared by all cells in a row, bounding aggregate archive expansion. */
internal class DiagnosticValueProjector(
    maximumCharacters: Int,
    private val omitted: (String) -> Unit
) {
    private var remainingCharacters = maximumCharacters
    private var remainingExpansionCharacters = maximumCharacters
    private var remainingParseCharacters = maximumCharacters.toLong() * 4
    private var remainingNodes = minOf(8192, maxOf(128, maximumCharacters / 4))

    init {
        require(maximumCharacters in 1..1_048_576)
    }

    fun text(value: String): JsonElement = projectText(value, 0)
    fun element(value: JsonElement): JsonElement = projectElement(value, 0)

    private fun projectText(value: String, depth: Int): JsonElement {
        if (depth > 24) return missing("NESTED_DIAGNOSTIC_DEPTH_LIMIT")
        if (value.length > remainingCharacters) return missing("RECORD_CHARACTER_LIMIT")
        if (value.startsWith(ArchivedTextCodec.PREFIX)) {
            val expected = value.substring(ArchivedTextCodec.PREFIX.length).substringBefore(':').toLongOrNull()
            if (expected == null || expected !in 0..minOf(remainingCharacters, remainingExpansionCharacters).toLong()) return missing("ARCHIVE_EXPANSION_LIMIT")
            remainingExpansionCharacters -= expected.toInt()
            val decoded = ArchivedTextCodec.decode(value)
            if (decoded == value) return missing("ARCHIVE_DECODE_FAILED")
            return projectText(decoded, depth + 1)
        }
        val trimmed = value.trimStart()
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            if (value.length > remainingParseCharacters) return missing("NESTED_DIAGNOSTIC_PARSE_LIMIT")
            remainingParseCharacters -= value.length
            val shapeFailure = diagnosticJsonShapeFailure(value, remainingNodes, 24 - depth)
            if (shapeFailure != null) return missing(shapeFailure)
            val parsed = try {
                Json.parseToJsonElement(value)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (parsed != null) return projectElement(parsed, depth + 1)
        }
        // A malformed or quoted container must never smuggle an opaque encoded secret.
        if (ArchivedTextCodec.PREFIX in value) return missing("EMBEDDED_ARCHIVE_NOT_EXPORTED")
        remainingCharacters -= value.length
        return JsonPrimitive(value)
    }

    private fun projectElement(value: JsonElement, depth: Int): JsonElement {
        if (depth > 24) return missing("NESTED_DIAGNOSTIC_DEPTH_LIMIT")
        if (--remainingNodes < 0) return missing("DIAGNOSTIC_NODE_LIMIT")
        return when (value) {
            is JsonObject -> {
                val fields = linkedMapOf<String, JsonElement>()
                for ((key, child) in value) {
                    if (ArchivedTextCodec.PREFIX in key) {
                        omitted("ARCHIVED_FIELD_NAME_NOT_EXPORTED")
                        continue
                    }
                    if (key.length > remainingCharacters || remainingNodes <= 0) {
                        omitted("DIAGNOSTIC_NODE_OR_CHARACTER_LIMIT")
                        break
                    }
                    remainingCharacters -= key.length
                    fields[key] = projectElement(child, depth + 1)
                }
                JsonObject(fields)
            }
            is JsonArray -> {
                val rows = ArrayList<JsonElement>(minOf(value.size, remainingNodes.coerceAtLeast(0)))
                for (child in value) {
                    if (remainingNodes <= 0) {
                        omitted("DIAGNOSTIC_NODE_LIMIT")
                        break
                    }
                    rows += projectElement(child, depth + 1)
                }
                JsonArray(rows)
            }
            is JsonPrimitive -> if (value.isString) projectText(value.content, depth + 1) else value
        }
    }

    private fun missing(reason: String): JsonPrimitive {
        omitted(reason)
        return JsonPrimitive("[diagnostic value omitted: $reason]")
    }
}

/** Check nesting and shape before a tree parser can allocate a pathological object graph. */
internal fun diagnosticJsonShapeFailure(value: String, maximumNodes: Int = 8192, maximumDepth: Int = 24): String? {
    var depth = 0
    var nodes = 0
    var quoted = false
    var escaped = false
    for (character in value) {
        if (quoted) {
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '"') {
                quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    nodes++
                    if (depth > maximumDepth) return "NESTED_DIAGNOSTIC_DEPTH_LIMIT"
                }
                '}', ']' -> depth--
                ',', ':' -> nodes++
            }
            if (nodes > maximumNodes) return "DIAGNOSTIC_NODE_LIMIT"
        }
    }
    return null
}

/** Never emit encoded archives, including archives nested inside JSON text or arrays. */
internal fun diagnosticStoredValue(value: String, maximumCharacters: Int, omitted: (String) -> Unit): JsonElement =
    DiagnosticValueProjector(maximumCharacters, omitted).text(value)
