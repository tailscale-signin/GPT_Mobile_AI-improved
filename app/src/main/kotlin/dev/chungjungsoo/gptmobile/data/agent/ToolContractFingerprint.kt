package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Documentation changes do not revoke a compatible invocation contract. */
internal object ToolContractFingerprint {
    private val annotations = setOf("title", "description", "$" + "comment", "examples", "deprecated")
    private val schemaMaps = setOf("properties", "patternProperties", "$" + "defs", "definitions", "dependentSchemas")
    private val schemaValues = setOf("items", "additionalItems", "additionalProperties", "unevaluatedItems", "unevaluatedProperties", "contains", "propertyNames", "not", "if", "then", "else")
    private val schemaArrays = setOf("allOf", "anyOf", "oneOf", "prefixItems")

    fun schema(value: JsonObject): String = digest(normalize(value).toString())

    private fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.toSortedMap().filterKeys { it !in annotations }.mapValues { (key, child) ->
                when {
                    key in schemaMaps && child is JsonObject -> JsonObject(child.toSortedMap().mapValues { normalize(it.value) })
                    key in schemaValues -> if (child is JsonArray) JsonArray(child.map(::normalize)) else normalize(child)
                    key in schemaArrays && child is JsonArray -> JsonArray(child.map(::normalize))
                    key in setOf("required", "enum", "type") && child is JsonArray -> JsonArray(child.map(::canonical).sortedBy(JsonElement::toString))
                    else -> canonical(child)
                }
            }
        )
        else -> canonical(value)
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
