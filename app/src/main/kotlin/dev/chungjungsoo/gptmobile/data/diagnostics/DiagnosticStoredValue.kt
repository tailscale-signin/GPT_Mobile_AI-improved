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
    private var remainingNodes = diagnosticNodeBudget(maximumCharacters)

    init {
        require(maximumCharacters in 1..1_048_576)
    }

    fun text(value: String): JsonElement = projectText(value, 0)
    fun element(value: JsonElement): JsonElement = projectElement(value, 0)

    private fun projectText(value: String, depth: Int, original: JsonPrimitive? = null): JsonElement {
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
        return original ?: JsonPrimitive(value)
    }

    private fun projectElement(value: JsonElement, depth: Int): JsonElement {
        if (depth > 24) return missing("NESTED_DIAGNOSTIC_DEPTH_LIMIT")
        if (--remainingNodes < 0) return missing("DIAGNOSTIC_NODE_LIMIT")
        return when (value) {
            is JsonObject -> {
                var fields: MutableMap<String, JsonElement>? = null
                var processed = 0
                fun copyPrefix() = value.entries.asSequence().take(processed).associateTo(linkedMapOf()) { (key, child) -> key to child }
                for ((key, child) in value) {
                    if (ArchivedTextCodec.PREFIX in key) {
                        if (fields == null) fields = copyPrefix()
                        omitted("ARCHIVED_FIELD_NAME_NOT_EXPORTED")
                        processed++
                        continue
                    }
                    if (key.length > remainingCharacters || remainingNodes <= 0) {
                        if (fields == null) fields = copyPrefix()
                        omitted("DIAGNOSTIC_NODE_OR_CHARACTER_LIMIT")
                        break
                    }
                    remainingCharacters -= key.length
                    val safe = projectElement(child, depth + 1)
                    if (safe !== child && fields == null) fields = copyPrefix()
                    fields?.put(key, safe)
                    processed++
                }
                fields?.let(::JsonObject) ?: value
            }
            is JsonArray -> {
                var rows: MutableList<JsonElement>? = null
                for ((index, child) in value.withIndex()) {
                    if (remainingNodes <= 0) {
                        if (rows == null) rows = value.take(index).toMutableList()
                        omitted("DIAGNOSTIC_NODE_LIMIT")
                        break
                    }
                    val safe = projectElement(child, depth + 1)
                    if (safe !== child && rows == null) rows = value.take(index).toMutableList()
                    rows?.add(safe)
                }
                rows?.let(::JsonArray) ?: value
            }
            is JsonPrimitive -> if (value.isString) projectText(value.content, depth + 1, value) else value
        }
    }

    private fun missing(reason: String): JsonPrimitive {
        omitted(reason)
        return JsonPrimitive("[diagnostic value omitted: $reason]")
    }
}

/** A measured source row remains bounded independently of its JSON punctuation density. */
internal fun diagnosticNodeBudget(maximumCharacters: Int): Int = minOf(65_536, maxOf(128, maximumCharacters / 2))

/** Count JSON values, not separators or object keys, before allocating a tree. */
internal fun diagnosticJsonShapeFailure(value: String, maximumNodes: Int = 65_536, maximumDepth: Int = 24): String? {
    var depth = 0
    var nodes = 0
    var quoted = false
    var escaped = false
    var primitive = false
    val objectKey = BooleanArray(maximumDepth.coerceAtLeast(0) + 1)
    val objectContainer = BooleanArray(objectKey.size)
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
                '"' -> {
                    if (depth == 0 || !objectContainer[depth] || !objectKey[depth]) nodes++
                    quoted = true
                    primitive = false
                }
                '{', '[' -> {
                    depth++
                    nodes++
                    if (depth > maximumDepth) return "NESTED_DIAGNOSTIC_DEPTH_LIMIT"
                    objectContainer[depth] = character == '{'
                    objectKey[depth] = character == '{'
                    primitive = false
                }
                '}', ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    primitive = false
                }
                ':' -> {
                    objectKey[depth] = false
                    primitive = false
                }
                ',' -> {
                    objectKey[depth] = objectContainer[depth]
                    primitive = false
                }
                ' ', '\t', '\r', '\n' -> primitive = false
                else -> if (!primitive) {
                    nodes++
                    primitive = true
                }
            }
            if (nodes > maximumNodes) return "DIAGNOSTIC_NODE_LIMIT"
        }
    }
    return null
}

/** Never emit encoded archives, including archives nested inside JSON text or arrays. */
internal fun diagnosticStoredValue(value: String, maximumCharacters: Int, omitted: (String) -> Unit): JsonElement =
    DiagnosticValueProjector(maximumCharacters, omitted).text(value)
